using MegaStream.Server.Services;
using Xunit;

namespace MegaStream.Server.Tests;

public class SanitizerTests
{
    [Theory]
    [InlineData("#EXTM3U\n#EXTINF:-1,Channel\nhttps://provider.invalid/live/user/pass/1.ts")]
    [InlineData("{\"username\":\"alice\",\"password\":\"hidden\"}")]
    [InlineData("https%3A%2F%2Fprovider.invalid%2Fget.php%3Fusername%3Dalice%26password%3Dhidden")]
    [InlineData("%2523EXTM3U")]
    [InlineData("provider.example:8080/live/alice/secret/123.ts")]
    public void Provider_payload_is_rejected_as_metadata_but_redacted_in_diagnostics(string payload)
    {
        Assert.Throws<DomainException>(() => Sanitizer.Clean(payload));
        Assert.Equal("[redacted]", Sanitizer.CleanDiagnostic(payload));
    }

    [Theory]
    [InlineData("Failed https://alice:hidden@provider.invalid/live/secret", "provider.invalid")]
    [InlineData("Failed rtsp://provider.invalid/live/secret", "provider.invalid")]
    [InlineData("authorization: Bearer secret-value", "secret-value")]
    [InlineData("password=super-secret", "super-secret")]
    [InlineData("token%3Dprivate-token", "private-token")]
    [InlineData("api_key=private-key", "private-key")]
    public void Secrets_and_urls_are_not_retained(string input, string secret)
    {
        Assert.Throws<DomainException>(() => Sanitizer.ValidateDiagnostic(input));
        foreach (var result in new[] { Sanitizer.Clean(input), Sanitizer.CleanDiagnostic(input) })
        {
            Assert.DoesNotContain(secret, result, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("[redacted]", result);
        }
    }

    [Fact]
    public void Diagnostic_redaction_preserves_useful_text_and_enforces_bounds()
    {
        Assert.Equal("decoder failed [redacted]", Sanitizer.CleanDiagnostic("decoder\nfailed https://provider.invalid/a"));
        Assert.Equal(new string('a', 64), Sanitizer.CleanDiagnostic(new string('a', 100), 64));
        Assert.Equal("a", Sanitizer.CleanDiagnostic("a😀z", 2));
        Assert.Equal(string.Empty, Sanitizer.CleanDiagnostic(null));
        Assert.Equal(string.Empty, Sanitizer.CleanDiagnostic("content", 0));
    }

    [Fact]
    public void Oversized_payload_cannot_bypass_inspection_through_truncation()
    {
        var payload = new string('a', 65_537) + " password=secret";
        Assert.Throws<DomainException>(() => Sanitizer.Clean(payload, 64));
        Assert.Equal("[redacted]", Sanitizer.CleanDiagnostic(payload, 64));
    }
}
