using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Xml.Linq;
using MegaStream.Server.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MegaStream.Server.Tests;

public class DataProtectionCertificateTests
{
    [Theory]
    [InlineData("missing")]
    [InlineData("file-only")]
    [InlineData("password-only")]
    [InlineData("wrong-password")]
    [InlineData("missing-file")]
    [InlineData("malformed-pfx")]
    [InlineData("public-only")]
    [InlineData("ecdsa")]
    [InlineData("short-rsa")]
    [InlineData("oversized-file")]
    public void Production_rejects_unusable_certificate_without_exposing_path_password_or_provider_errors(string scenario)
    {
        using var files = new CertificateFiles();
        var password = "test-private-password-" + Guid.NewGuid().ToString("N");
        string? path = Path.Combine(files.Root, "private-certificate.pfx");
        string? configuredPassword = password;
        switch (scenario)
        {
            case "missing": path = null; configuredPassword = null; break;
            case "file-only": configuredPassword = null; break;
            case "password-only": path = null; break;
            case "missing-file": break;
            case "malformed-pfx": File.WriteAllBytes(path, [1, 2, 3, 4]); break;
            case "oversized-file": File.WriteAllBytes(path, new byte[1024 * 1024 + 1]); break;
            default:
                File.WriteAllBytes(path, CertificateBytes(scenario, password));
                if (scenario == "wrong-password") configuredPassword = password + "-incorrect";
                break;
        }
        var error = Assert.Throws<InvalidOperationException>(() => DataProtectionCertificate.Load(Configuration(path, configuredPassword), new TestEnvironment { EnvironmentName = "Production" }));
        Assert.Null(error.InnerException);
        Assert.DoesNotContain(files.Root, error.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(password, error.ToString(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("Development")]
    [InlineData("Testing")]
    public void Development_and_testing_allow_absent_but_not_partial_certificate_configuration(string environment)
    {
        var host = new TestEnvironment { EnvironmentName = environment };
        Assert.Null(DataProtectionCertificate.Load(Configuration(null, null), host));
        Assert.Throws<InvalidOperationException>(() => DataProtectionCertificate.Load(Configuration(null, "password"), host));
        using var files = new CertificateFiles();
        Assert.Throws<InvalidOperationException>(() => DataProtectionCertificate.Load(Configuration(Path.Combine(files.Root, "certificate.pfx"), null), host));
    }

    [Fact]
    public void Production_loads_rsa_private_certificate_for_key_protection()
    {
        using var files = new CertificateFiles();
        var path = Path.Combine(files.Root, "certificate.pfx");
        File.WriteAllBytes(path, CertificateBytes("rsa", "test-password"));
        using var certificate = DataProtectionCertificate.Load(Configuration(path, "test-password"), new TestEnvironment { EnvironmentName = "Production" });
        Assert.NotNull(certificate);
        Assert.True(certificate.HasPrivateKey);
        using var privateKey = certificate.GetRSAPrivateKey();
        Assert.NotNull(privateKey);
        Assert.True(privateKey.KeySize >= 2048);
    }

    [Fact]
    public void Persisted_key_ring_is_encrypted_and_new_provider_can_decrypt_after_certificate_reload()
    {
        using var files = new CertificateFiles();
        var certificatePath = Path.Combine(files.Root, "certificate.pfx");
        var keyDirectory = Directory.CreateDirectory(Path.Combine(files.Root, "key-ring"));
        File.WriteAllBytes(certificatePath, CertificateBytes("rsa", "restart-password"));
        var configuration = Configuration(certificatePath, "restart-password");
        var environment = new TestEnvironment { EnvironmentName = "Production" };
        string protectedPayload;
        using (var certificate = DataProtectionCertificate.Load(configuration, environment))
        using (var services = ProtectionProvider(certificate!, keyDirectory))
        {
            var protector = services.GetRequiredService<IDataProtectionProvider>().CreateProtector("restart-probe");
            protectedPayload = protector.Protect("payload-before-provider-restart");
        }
        var keyFiles = Directory.GetFiles(keyDirectory.FullName, "*.xml");
        Assert.NotEmpty(keyFiles);
        foreach (var path in keyFiles)
        {
            var xml = XDocument.Load(path);
            Assert.Contains(xml.Descendants(), element => element.Name.LocalName == "encryptedSecret");
            Assert.Contains(xml.Descendants(), element => element.Name.LocalName == "EncryptedData");
            Assert.DoesNotContain(xml.Descendants(), element => element.Name.LocalName == "masterKey");
        }
        // Both the original DI provider and certificate are disposed before this independent reload.
        using var reloadedCertificate = DataProtectionCertificate.Load(configuration, environment);
        using var restartedServices = ProtectionProvider(reloadedCertificate!, keyDirectory);
        var restartedProtector = restartedServices.GetRequiredService<IDataProtectionProvider>().CreateProtector("restart-probe");
        Assert.Equal("payload-before-provider-restart", restartedProtector.Unprotect(protectedPayload));
    }

    private static ServiceProvider ProtectionProvider(X509Certificate2 certificate, DirectoryInfo keyDirectory)
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddDataProtection().SetApplicationName("MegaStream.Certificate.IntegrationTest")
            .PersistKeysToFileSystem(keyDirectory).ProtectKeysWithCertificate(certificate);
        return services.BuildServiceProvider();
    }

    private static IConfiguration Configuration(string? path, string? password) => new ConfigurationBuilder()
        .AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["DATA_PROTECTION_CERTIFICATE_FILE"] = path,
            ["DATA_PROTECTION_CERTIFICATE_PASSWORD"] = password
        }).Build();

    private static byte[] CertificateBytes(string kind, string password)
    {
        using var rsa = kind == "ecdsa" ? null : RSA.Create(kind == "short-rsa" ? 1024 : 2048);
        using var ec = kind == "ecdsa" ? ECDsa.Create(ECCurve.NamedCurves.nistP256) : null;
        var request = rsa is not null
            ? new CertificateRequest("CN=MegaStream Test Certificate", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)
            : new CertificateRequest("CN=MegaStream Test Certificate", ec!, HashAlgorithmName.SHA256);
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        if (kind == "public-only")
        {
            using var publicCertificate = new X509Certificate2(certificate.Export(X509ContentType.Cert));
            return publicCertificate.Export(X509ContentType.Pfx, password);
        }
        return certificate.Export(X509ContentType.Pfx, password);
    }

    private sealed class CertificateFiles : IDisposable
    {
        public string Root { get; } = Path.Combine(AppContext.BaseDirectory, "TestResults", "certificates-" + Guid.NewGuid().ToString("N"));
        public CertificateFiles() => Directory.CreateDirectory(Root);
        public void Dispose() => Directory.Delete(Root, recursive: true);
    }
}
