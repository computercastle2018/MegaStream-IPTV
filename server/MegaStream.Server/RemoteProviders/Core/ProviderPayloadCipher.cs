using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MegaStream.Server.Services;

namespace MegaStream.Server.RemoteProviders.Core;

public sealed class ProviderPayloadCipher : IDisposable
{
    private readonly byte[] key;
    public string KeyVersion { get; }
    public ProviderPayloadCipher(IConfiguration configuration)
    {
        var encoded = configuration["PROVIDER_SECRET_KEY"];
        var version = configuration["PROVIDER_SECRET_KEY_VERSION"] ?? "1";
        if (!int.TryParse(version, NumberStyles.None, CultureInfo.InvariantCulture, out var number) || number < 1)
            throw new InvalidOperationException("PROVIDER_SECRET_KEY_VERSION must be a positive integer.");
        KeyVersion = number.ToString(CultureInfo.InvariantCulture);
        if (encoded is null || encoded.Length != 44) throw new InvalidOperationException("PROVIDER_SECRET_KEY must be a base64-encoded 32-byte key.");
        try { key = Convert.FromBase64String(encoded); }
        catch (FormatException) { throw new InvalidOperationException("PROVIDER_SECRET_KEY must be a base64-encoded 32-byte key."); }
        if (key.Length != 32) throw new InvalidOperationException("PROVIDER_SECRET_KEY must be a base64-encoded 32-byte key.");
    }
    public byte[] Encrypt(Guid profileId, string type, long revision, RemoteProviderConfiguration configuration)
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(configuration);
        try
        {
            var result = new byte[12 + 16 + plaintext.Length];
            RandomNumberGenerator.Fill(result.AsSpan(0, 12));
            using var aes = new AesGcm(key, 16);
            aes.Encrypt(result.AsSpan(0, 12), plaintext, result.AsSpan(28), result.AsSpan(12, 16), Aad(profileId, type, revision));
            return result;
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }
    public RemoteProviderConfiguration Decrypt(RemoteProviderProfile profile)
    {
        if (profile.KeyVersion != KeyVersion || profile.EncryptedPayload.Length < 28 || profile.EncryptedPayload.Length > 32768) throw Unavailable();
        var plaintext = new byte[profile.EncryptedPayload.Length - 28];
        try
        {
            using var aes = new AesGcm(key, 16);
            aes.Decrypt(profile.EncryptedPayload.AsSpan(0, 12), profile.EncryptedPayload.AsSpan(28), profile.EncryptedPayload.AsSpan(12, 16), plaintext, Aad(profile.Id, profile.Type, profile.Revision));
            return JsonSerializer.Deserialize<RemoteProviderConfiguration>(plaintext) ?? throw Unavailable();
        }
        catch (CryptographicException) { throw Unavailable(); }
        catch (JsonException) { throw Unavailable(); }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }
    private static byte[] Aad(Guid id, string type, long revision) => Encoding.UTF8.GetBytes($"{id:D}|{type}|{revision.ToString(CultureInfo.InvariantCulture)}");
    private static DomainException Unavailable() => new(503, "provider_configuration_unavailable", "Provider configuration is temporarily unavailable.");
    public void Dispose() => CryptographicOperations.ZeroMemory(key);
}
