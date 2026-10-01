using System;
using System.Text.RegularExpressions;

namespace MegaStream.Server.Services;

/// <summary>Separates metadata validation from non-throwing diagnostic redaction.</summary>
public static class Sanitizer
{
    private const int MaxInputLength = 65_536;
    private const string Redacted = "[redacted]";
    private static readonly TimeSpan MatchTimeout = TimeSpan.FromMilliseconds(100);
    private const RegexOptions Options = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private static readonly Regex Playlist = Pattern(@"#EXT(?:M3U|INF|GRP|VLCOPT)|(?:get|player_api|panel_api)\.php|\b(?:playlist|m3u)\s*[\""']?\s*[:=]\s*[\""']?\s*(?:\[|\{|https?[:%])|\.(?:m3u8?)(?:\b|\?)");
    private static readonly Regex ProviderPath = Pattern(@"/(?:live|movie|series)/[^/\s?#<>\""']+/[^/\s?#<>\""']+");
    private static readonly Regex UserField = Pattern(@"\b(?:username|user_name|user)\s*[\""']?\s*[:=]");
    private static readonly Regex PasswordField = Pattern(@"\b(?:password|passwd|pwd|pass)\s*[\""']?\s*[:=]");
    private static readonly Regex Url = Pattern(@"(?:[a-z][a-z0-9+.-]*://|www\.)[^\s<>\""']+|//[^\s/<>\""']+\.[^\s<>\""']+");
    private static readonly Regex Credential = Pattern(@"\b(?:password|passwd|pwd|pass|username|user_name|user|key|access[_-]?token|refresh[_-]?token|token|api[_-]?key|secret|client[_-]?secret|authorization|cookie|set-cookie)\s*[\""']?\s*[:=]\s*(?:\""[^\""\r\n]*(?:\""|$)|'[^'\r\n]*(?:'|$)|[^\r\n,;&}]+)");
    private static readonly Regex Authorization = Pattern(@"\b(?:Bearer|Basic)\s+[a-z0-9+/_.=~-]+");
    private static readonly Regex Controls = Pattern(@"[\x00-\x1f\x7f]+");

    public static string Clean(string? value, int maxLength = 256)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        try
        {
            var decoded = Decode(value);
            if (decoded is null || IsProviderPayload(decoded))
                throw InvalidMetadata();
            return Redact(decoded, maxLength);
        }
        catch (RegexMatchTimeoutException)
        {
            throw InvalidMetadata();
        }
        catch (UriFormatException)
        {
            throw InvalidMetadata();
        }
    }

    public static void ValidateDiagnostic(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        try
        {
            var decoded = Decode(value);
            if (decoded is null || IsProviderPayload(decoded) || Url.IsMatch(decoded) ||
                Credential.IsMatch(decoded) || Authorization.IsMatch(decoded))
                throw InvalidMetadata();
        }
        catch (RegexMatchTimeoutException)
        {
            throw InvalidMetadata();
        }
        catch (UriFormatException)
        {
            throw InvalidMetadata();
        }
    }

    /// <summary>Redacts even disallowed payloads instead of failing a logging operation.</summary>
    public static string CleanDiagnostic(string? value, int maxLength = 256)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        try
        {
            var decoded = Decode(value);
            return decoded is null || IsProviderPayload(decoded)
                ? Truncate(Redacted, maxLength)
                : Redact(decoded, maxLength);
        }
        catch (RegexMatchTimeoutException)
        {
            return Truncate(Redacted, maxLength);
        }
        catch (UriFormatException)
        {
            return Truncate(Redacted, maxLength);
        }
    }

    private static bool IsProviderPayload(string value) =>
        Playlist.IsMatch(value) || ProviderPath.IsMatch(value) ||
        (UserField.IsMatch(value) && PasswordField.IsMatch(value));

    private static string? Decode(string value)
    {
        if (value.Length > MaxInputLength) return null;
        value = value.Replace(@"\/", "/", StringComparison.Ordinal);
        // Decode before inspection so percent-encoding cannot hide credentials or playlist markers.
        for (var i = 0; i < 4; i++)
        {
            var decoded = Uri.UnescapeDataString(value);
            if (decoded == value) return value;
            value = decoded;
        }
        // Reject excessive nesting rather than risk retaining an encoded secret.
        return Uri.UnescapeDataString(value) == value ? value : null;
    }

    private static string Redact(string value, int maxLength)
    {
        value = Url.Replace(value, Redacted);
        value = Credential.Replace(value, Redacted);
        value = Authorization.Replace(value, Redacted);
        value = Controls.Replace(value, " ").Trim();
        return Truncate(value, maxLength);
    }

    private static string Truncate(string value, int maxLength)
    {
        var length = Math.Clamp(maxLength, 0, value.Length);
        if (length > 0 && length < value.Length && char.IsHighSurrogate(value[length - 1])) length--;
        return value[..length];
    }

    private static DomainException InvalidMetadata() =>
        new(400, "invalid_metadata", "Metadata must not contain playlists or provider credentials.");

    private static Regex Pattern(string pattern) => new(pattern, Options, MatchTimeout);
}
