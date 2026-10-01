using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MegaStream.Server.Models;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;

namespace MegaStream.Server.Services;

/// <summary>Issues compact ES256 leases without exposing private key material.</summary>
public sealed class LeaseSigner : IDisposable
{
    private readonly ECDsa _key;
    private readonly TimeProvider _clock;
    private readonly object _gate = new();
    private readonly string _encodedHeader;
    private bool _disposed;

    public object PublicJwk { get; }

    public LeaseSigner(IConfiguration configuration, IHostEnvironment environment)
        : this(configuration, environment, TimeProvider.System) { }

    public LeaseSigner(IConfiguration configuration, IHostEnvironment environment, TimeProvider clock)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        ArgumentNullException.ThrowIfNull(environment);
        ArgumentNullException.ThrowIfNull(clock);
        _clock = clock;
        _key = LoadKey(configuration, environment);
        var parameters = _key.ExportParameters(false);
        var x = Base64Url(parameters.Q.X!);
        var y = Base64Url(parameters.Q.Y!);
        // RFC 7638 canonical EC JWK thumbprint: stable across PEM encodings and restarts.
        var thumbprint = Encoding.UTF8.GetBytes($"{{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"{x}\",\"y\":\"{y}\"}}");
        var kid = Base64Url(SHA256.HashData(thumbprint));
        PublicJwk = new { kty = "EC", crv = "P-256", x, y, alg = "ES256", use = "sig", kid };
        _encodedHeader = EncodeJson(new { alg = "ES256", typ = "JWT", kid });
    }

    public string Sign(Installation installation, License license)
    {
        ArgumentNullException.ThrowIfNull(installation);
        ArgumentNullException.ThrowIfNull(license);
        var now = _clock.GetUtcNow();
        var validFrom = AsUtc(license.ValidFrom);
        var validUntil = AsUtc(license.ValidUntil);
        if (now < DateTimeOffset.UnixEpoch || validFrom < DateTimeOffset.UnixEpoch ||
            validUntil.ToUnixTimeSeconds() <= validFrom.ToUnixTimeSeconds())
            throw new DomainException(400, "invalid_license_window", "License dates must define a valid nonnegative whole-second window.");
        if (license.Status != LicenseStatus.Active)
            throw new DomainException(403, "license_inactive", "The license is not active.");
        if (validUntil <= now || validUntil <= validFrom)
            throw new DomainException(403, "license_expired", "The license has expired.");
        if (validFrom > now)
            throw new DomainException(403, "license_not_started", "The license is not yet valid.");

        var graceDays = license.OfflineGraceDays;
        if (graceDays is < 1 or > 3)
            throw new DomainException(400, "invalid_offline_grace", "Offline grace must be between 1 and 3 days.");
        // Issuance above requires now < validUntil, so now + grace is always the earlier horizon.
        var expiresAt = DateTimeOffset.MaxValue - now <= TimeSpan.FromDays(graceDays)
            ? DateTimeOffset.MaxValue
            : now.AddDays(graceDays);
        var payload = EncodeJson(new
        {
            iss = "https://megastrem.megastation.uk",
            aud = "megastream-android",
            sub = installation.Id,
            lid = license.Id,
            lrv = Math.Max(1, license.Revision),
            credentialBinding = installation.CredentialBinding,
            iat = now.ToUnixTimeSeconds(),
            nbf = now.ToUnixTimeSeconds(),
            exp = expiresAt.ToUnixTimeSeconds(),
            licenseStartsAt = validFrom.ToUnixTimeSeconds(),
            licenseEndsAt = validUntil.ToUnixTimeSeconds(),
            jti = Guid.NewGuid(),
            policyVersion = 1,
            decision = "allowed"
        });
        var signingInput = _encodedHeader + "." + payload;
        byte[] signature;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            signature = _key.SignData(Encoding.ASCII.GetBytes(signingInput), HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            // Reserve the seat through every outstanding lease, including older, longer leases.
            var emittedExpiry = DateTimeOffset.FromUnixTimeSeconds(expiresAt.ToUnixTimeSeconds());
            if (installation.LastLeaseExpiresAt is not { } reservedUntil || AsUtc(reservedUntil) < emittedExpiry)
                installation.LastLeaseExpiresAt = emittedExpiry.UtcDateTime;
        }
        return signingInput + "." + Base64Url(signature);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _key.Dispose();
            _disposed = true;
        }
    }

    private static ECDsa LoadKey(IConfiguration configuration, IHostEnvironment environment)
    {
        var pem = configuration["SIGNING_KEY_PEM"] ?? Environment.GetEnvironmentVariable("SIGNING_KEY_PEM");
        var file = configuration["SIGNING_KEY_FILE"] ?? Environment.GetEnvironmentVariable("SIGNING_KEY_FILE");
        if (string.IsNullOrWhiteSpace(pem) && string.IsNullOrWhiteSpace(file))
        {
            if (environment.IsDevelopment()) return ECDsa.Create(ECCurve.NamedCurves.nistP256);
            throw new InvalidOperationException("A P-256 private signing key must be configured outside Development.");
        }

        ECDsa? key = null;
        try
        {
            if (string.IsNullOrWhiteSpace(pem)) pem = File.ReadAllText(file!);
            key = ECDsa.Create();
            key.ImportFromPem(pem);
            var parameters = key.ExportParameters(false);
            if (parameters.Curve.Oid.Value != "1.2.840.10045.3.1.7" ||
                parameters.Q.X?.Length != 32 || parameters.Q.Y?.Length != 32)
                throw new CryptographicException();
            // Signing proves possession of the private key without exporting it.
            _ = key.SignData(Array.Empty<byte>(), HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            return key;
        }
        catch (Exception exception) when (exception is CryptographicException or ArgumentException or
            IOException or UnauthorizedAccessException or NotSupportedException or System.Security.SecurityException)
        {
            key?.Dispose();
            // Do not attach provider exceptions: they may include PEM or secret file paths.
            throw new InvalidOperationException("The configured signing key must be a valid unencrypted P-256 private PEM key.");
        }
    }

    private static DateTimeOffset AsUtc(DateTime value) => new(value.Kind switch
    {
        DateTimeKind.Local => value.ToUniversalTime(),
        DateTimeKind.Unspecified => DateTime.SpecifyKind(value, DateTimeKind.Utc),
        _ => value
    });

    private static string EncodeJson(object value) => Base64Url(JsonSerializer.SerializeToUtf8Bytes(value));
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
