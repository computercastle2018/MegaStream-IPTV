using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace MegaStream.Server.Tests;

public class LeaseSignerTests
{
    internal static IConfiguration Configuration()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["SIGNING_KEY_PEM"] = key.ExportPkcs8PrivateKeyPem(),
            ["SIGNING_KEY_FILE"] = "",
            ["DEVICE_SECRET_PEPPER"] = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
        }).Build();
    }

    internal static byte[] Decode(string value) => Convert.FromBase64String(value.Replace('-', '+').Replace('_', '/') + new string('=', (4 - value.Length % 4) % 4));
    internal static JsonElement Payload(string lease) => JsonSerializer.Deserialize<JsonElement>(Decode(lease.Split('.')[1]));

    private static License ValidLicense(TestClock clock) => new()
    {
        ValidFrom = clock.Now.UtcDateTime.AddDays(-1), ValidUntil = clock.Now.UtcDateTime.AddDays(30), MaxInstallations = 2
    };

    [Fact]
    public void Public_jwk_verifies_p1363_signature_and_rejects_modified_payload()
    {
        var clock = new TestClock();
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var installation = new Installation { CredentialBinding = "public-test-credential-binding" };
        var license = ValidLicense(clock);
        license.Revision = 7;
        var lease = signer.Sign(installation, license);
        var payload = Payload(lease);
        Assert.Equal("https://megastrem.megastation.uk", payload.GetProperty("iss").GetString());
        Assert.Equal("megastream-android", payload.GetProperty("aud").GetString());
        Assert.Equal(installation.Id, payload.GetProperty("sub").GetGuid());
        Assert.Equal(license.Id, payload.GetProperty("lid").GetGuid());
        Assert.Equal(7, payload.GetProperty("lrv").GetInt32());
        Assert.Equal(installation.CredentialBinding, payload.GetProperty("credentialBinding").GetString());
        string[] expectedClaims = ["iss", "aud", "sub", "lid", "lrv", "credentialBinding", "iat", "nbf", "exp", "licenseStartsAt", "licenseEndsAt", "jti", "policyVersion", "decision"];
        Assert.Equal(expectedClaims.Order(StringComparer.Ordinal), payload.EnumerateObject().Select(property => property.Name).Order(StringComparer.Ordinal));
        Assert.Equal(clock.Now.AddDays(3).ToUnixTimeSeconds(), payload.GetProperty("exp").GetInt64());
        Assert.Equal(clock.Now.ToUnixTimeSeconds(), payload.GetProperty("iat").GetInt64());
        Assert.Equal(clock.Now.ToUnixTimeSeconds(), payload.GetProperty("nbf").GetInt64());
        Assert.Equal(new DateTimeOffset(license.ValidFrom).ToUnixTimeSeconds(), payload.GetProperty("licenseStartsAt").GetInt64());
        Assert.Equal(new DateTimeOffset(license.ValidUntil).ToUnixTimeSeconds(), payload.GetProperty("licenseEndsAt").GetInt64());
        Assert.NotEqual(Guid.Empty, payload.GetProperty("jti").GetGuid());
        Assert.Equal(1, payload.GetProperty("policyVersion").GetInt32());
        Assert.Equal("allowed", payload.GetProperty("decision").GetString());
        var parts = lease.Split('.');
        var jwk = JsonSerializer.SerializeToElement(signer.PublicJwk);
        Assert.False(jwk.TryGetProperty("d", out _));
        Assert.Equal("ES256", jwk.GetProperty("alg").GetString());
        Assert.Equal("P-256", jwk.GetProperty("crv").GetString());
        using var verifier = ECDsa.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = Decode(jwk.GetProperty("x").GetString()!), Y = Decode(jwk.GetProperty("y").GetString()!) }
        });
        var signature = Decode(parts[2]);
        Assert.Equal(64, signature.Length);
        var input = Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]);
        Assert.True(verifier.VerifyData(input, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
        var changedPayload = Encoding.UTF8.GetString(Decode(parts[1])).Replace(installation.Id.ToString(), Guid.NewGuid().ToString());
        var changedPart = Convert.ToBase64String(Encoding.UTF8.GetBytes(changedPayload)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        Assert.False(verifier.VerifyData(Encoding.ASCII.GetBytes(parts[0] + "." + changedPart), signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
    }

    [Theory]
    [InlineData(6, 3, 72)]
    [InlineData(48, 3, 72)]
    [InlineData(240, 1, 24)]
    public void Offline_lease_is_bounded_by_configured_grace_and_hard_three_day_cap(int validHours, int graceDays, int expectedLeaseHours)
    {
        var clock = new TestClock();
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var license = ValidLicense(clock);
        license.ValidUntil = clock.Now.UtcDateTime.AddHours(validHours);
        license.OfflineGraceDays = graceDays;
        var payload = Payload(signer.Sign(new Installation(), license));
        Assert.Equal(clock.Now.AddHours(expectedLeaseHours).ToUnixTimeSeconds(), payload.GetProperty("exp").GetInt64());
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(0)]
    [InlineData(4)]
    [InlineData(99)]
    public void Invalid_grace_policy_is_rejected_without_issuing_or_reserving_a_lease(int graceDays)
    {
        var clock = new TestClock();
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var license = ValidLicense(clock);
        license.OfflineGraceDays = graceDays;
        var installation = new Installation();
        Assert.Throws<DomainException>(() => signer.Sign(installation, license));
        Assert.Null(installation.LastLeaseExpiresAt);
    }

    [Fact]
    public void Shorter_reissued_lease_does_not_release_existing_offline_seat_reservation()
    {
        var clock = new TestClock();
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var installation = new Installation();
        var license = ValidLicense(clock);
        var first = Payload(signer.Sign(installation, license));
        var originalExpiry = DateTimeOffset.FromUnixTimeSeconds(first.GetProperty("exp").GetInt64()).UtcDateTime;
        Assert.Equal(originalExpiry, installation.LastLeaseExpiresAt);
        license.OfflineGraceDays = 1;
        clock.Now = clock.Now.AddHours(1);
        var replacement = Payload(signer.Sign(installation, license));
        Assert.Equal(clock.Now.AddDays(1).ToUnixTimeSeconds(), replacement.GetProperty("exp").GetInt64());
        Assert.Equal(originalExpiry, installation.LastLeaseExpiresAt);
    }

    [Theory]
    [InlineData("negative-start")]
    [InlineData("same-unix-second")]
    public void Unrepresentable_license_window_cannot_issue_a_lease(string scenario)
    {
        var clock = new TestClock();
        clock.Now = clock.Now.AddMilliseconds(500);
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var license = ValidLicense(clock);
        if (scenario == "negative-start")
            license.ValidFrom = DateTime.UnixEpoch.AddMilliseconds(-1);
        else
        {
            license.ValidFrom = clock.Now.UtcDateTime.AddMilliseconds(-100);
            license.ValidUntil = clock.Now.UtcDateTime.AddMilliseconds(100);
        }
        var installation = new Installation();
        var error = Assert.Throws<DomainException>(() => signer.Sign(installation, license));
        Assert.Equal(400, error.StatusCode);
        Assert.Null(installation.LastLeaseExpiresAt);
    }

    [Theory]
    [InlineData(LicenseStatus.Revoked, -1, 24)]
    [InlineData(LicenseStatus.Suspended, -1, 24)]
    [InlineData(LicenseStatus.Active, -48, 0)]
    [InlineData(LicenseStatus.Active, 1, 24)]
    public void Unusable_license_cannot_issue_signed_lease(LicenseStatus status, int startsInHours, int endsInHours)
    {
        var clock = new TestClock();
        using var signer = new LeaseSigner(Configuration(), new TestEnvironment(), clock);
        var license = ValidLicense(clock);
        license.Status = status;
        license.ValidFrom = clock.Now.UtcDateTime.AddHours(startsInHours);
        license.ValidUntil = clock.Now.UtcDateTime.AddHours(endsInHours);
        Assert.Throws<DomainException>(() => signer.Sign(new Installation(), license));
    }
}
