using System.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace MegaStream.Server.Services;

public static class DataProtectionCertificate
{
    private const int MaximumPfxBytes = 1024 * 1024;

    // The caller owns the returned certificate and must retain it for the data-protection provider's lifetime.
    public static X509Certificate2? Load(IConfiguration configuration, IHostEnvironment environment)
    {
        var certificateFile = configuration["DATA_PROTECTION_CERTIFICATE_FILE"];
        var certificatePassword = configuration["DATA_PROTECTION_CERTIFICATE_PASSWORD"];
        var noFile = string.IsNullOrWhiteSpace(certificateFile);
        var noPassword = string.IsNullOrWhiteSpace(certificatePassword);
        if (noFile && noPassword && (environment.IsDevelopment() || environment.IsEnvironment("Testing")))
            return null;
        if (noFile || noPassword)
            throw InvalidConfiguration();

        byte[]? pfxBytes = null;
        X509Certificate2? certificate = null;
        try
        {
            pfxBytes = ReadPfx(certificateFile!);
            // .NET on macOS rejects EphemeralKeySet; DefaultKeySet uses its temporary keychain,
            // released with the certificate. Never request the persistent user keychain.
            var storageFlags = OperatingSystem.IsMacOS()
                ? X509KeyStorageFlags.DefaultKeySet
                : X509KeyStorageFlags.EphemeralKeySet;
            certificate = new X509Certificate2(pfxBytes, certificatePassword, storageFlags);
            if (!HasRsaPrivateKey(certificate))
            {
                certificate.Dispose();
                throw InvalidConfiguration();
            }
            return certificate;
        }
        catch (Exception exception) when (exception is CryptographicException or IOException or UnauthorizedAccessException
            or ArgumentException or NotSupportedException or SecurityException)
        {
            certificate?.Dispose();
            // Provider and filesystem errors may embed the secret path or PFX details; never attach them.
            throw InvalidConfiguration();
        }
        finally
        {
            if (pfxBytes is not null) CryptographicOperations.ZeroMemory(pfxBytes);
        }
    }

    private static byte[] ReadPfx(string certificateFile)
    {
        using var source = new FileStream(certificateFile, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (source.Length is <= 0 or > MaximumPfxBytes)
            throw InvalidConfiguration();
        var pfxBytes = new byte[(int)source.Length];
        var completed = false;
        try
        {
            source.ReadExactly(pfxBytes);
            if (source.ReadByte() != -1) throw InvalidConfiguration();
            completed = true;
            return pfxBytes;
        }
        finally
        {
            if (!completed) CryptographicOperations.ZeroMemory(pfxBytes);
        }
    }

    private static bool HasRsaPrivateKey(X509Certificate2 certificate)
    {
        if (!certificate.HasPrivateKey) return false;
        using var privateKey = certificate.GetRSAPrivateKey();
        return privateKey is not null && privateKey.KeySize >= 2048;
    }

    private static InvalidOperationException InvalidConfiguration() => new(
        "Data protection certificate configuration requires a readable PFX, its password, and an RSA private key of at least 2048 bits.");
}
