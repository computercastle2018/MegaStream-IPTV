using System.Security.Cryptography;
using System.Text;

namespace MegaStream.Server.Services;

public sealed class SecretHasher
{
    private readonly byte[] pepper;
    public SecretHasher(IConfiguration configuration, IHostEnvironment environment)
    {
        var encoded = configuration["DEVICE_SECRET_PEPPER"];
        if (string.IsNullOrWhiteSpace(encoded))
        {
            if (!environment.IsDevelopment() && !environment.IsEnvironment("Testing"))
                throw new InvalidOperationException("DEVICE_SECRET_PEPPER must contain a persistent base64 secret of at least 32 bytes.");
            pepper = RandomNumberGenerator.GetBytes(32);
            return;
        }
        try { pepper = Convert.FromBase64String(encoded); }
        catch (FormatException) { throw new InvalidOperationException("DEVICE_SECRET_PEPPER must be base64 encoded."); }
        if (pepper.Length < 32) throw new InvalidOperationException("DEVICE_SECRET_PEPPER must contain at least 32 random bytes.");
    }
    public string Hash(string purpose, string secret) => Convert.ToHexString(HMACSHA256.HashData(pepper, Encoding.UTF8.GetBytes(purpose + "\0" + secret)));
    public bool Matches(string purpose, string secret, string expectedHash)
    {
        if (expectedHash.Length != 64) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Hash(purpose, secret)), Encoding.ASCII.GetBytes(expectedHash));
    }
    public static string CredentialBinding(string bearerToken)
    {
        var raw = Convert.FromBase64String(bearerToken.Replace('-', '+').Replace('_', '/') + "=");
        return Convert.ToBase64String(SHA256.HashData(raw)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
}
