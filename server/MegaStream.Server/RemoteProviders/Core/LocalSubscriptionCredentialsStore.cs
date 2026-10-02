using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MegaStream.Server.Contracts;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.RemoteProviders.Core;

public sealed class LocalSubscriptionCredentialsStore(AppDbContext db, ProviderPayloadCipher cipher)
{
    public string Encode(Guid installationId, IReadOnlyList<LocalSubscription> subscriptions)
    {
        var snapshot = new StoredSubscriptionSnapshot { Subscriptions = subscriptions.Cast<LocalSubscription?>().ToList() };
        foreach (var subscription in subscriptions)
        {
            if (subscription.Credentials is not { } configuration) continue;
            var type = ProviderType(subscription.Type);
            ConfigurationValidator.Validate(new() { DisplayName = "Subscription", Type = type, Configuration = configuration });
            snapshot.Credentials.Add(new StoredSubscriptionCredential
            {
                LocalId = subscription.LocalId, KeyVersion = cipher.KeyVersion,
                Payload = cipher.Encrypt(Binding(installationId, subscription.LocalId), type, 1, configuration)
            });
            subscription.Credentials = null;
        }
        return snapshot.Credentials.Count == 0 ? JsonSerializer.Serialize(subscriptions) : JsonSerializer.Serialize(snapshot);
    }

    public static IReadOnlyList<LocalSubscription?> Summaries(string json) => Decode(json).Subscriptions;
    public static IReadOnlySet<long> CredentialIds(string json) => Decode(json).Credentials.Select(x => x.LocalId).ToHashSet();

    public async Task<RemoteProviderConfiguration> RevealAsync(Guid installationId, long localId, CancellationToken ct)
    {
        var json = await db.Installations.AsNoTracking().Where(x => x.Id == installationId && x.Status == InstallationStatus.Active)
            .Select(x => x.LocalSubscriptionsJson).SingleOrDefaultAsync(ct);
        if (json is null) throw Unavailable();
        try
        {
            var snapshot = Decode(json);
            var summary = snapshot.Subscriptions.SingleOrDefault(x => x?.LocalId == localId);
            var encrypted = snapshot.Credentials.SingleOrDefault(x => x.LocalId == localId);
            if (summary is null || encrypted is null) throw Unavailable();
            return cipher.Decrypt(new RemoteProviderProfile
            {
                Id = Binding(installationId, localId), Type = ProviderType(summary.Type), Revision = 1,
                KeyVersion = encrypted.KeyVersion, EncryptedPayload = encrypted.Payload
            });
        }
        catch (JsonException) { throw Unavailable(); }
    }

    private static StoredSubscriptionSnapshot Decode(string json)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.ValueKind == JsonValueKind.Array
            ? new() { Subscriptions = JsonSerializer.Deserialize<List<LocalSubscription?>>(json) ?? throw new JsonException() }
            : JsonSerializer.Deserialize<StoredSubscriptionSnapshot>(json) ?? throw new JsonException();
    }

    private static Guid Binding(Guid installationId, long localId) =>
        new(SHA256.HashData(Encoding.UTF8.GetBytes($"local-subscription|{installationId:D}|{localId}"))[..16]);

    private static string ProviderType(string type) => type switch
    {
        "xtream_codes" => RemoteProviderValues.XtreamCodes, "m3u" => RemoteProviderValues.M3u,
        "stalker_portal" => RemoteProviderValues.StalkerPortal, _ => throw Unavailable()
    };

    private static DomainException Unavailable() => new(404, "subscription_credentials_unavailable", "Subscription credentials are unavailable.");
}

// Separate persistence envelope prevents incoming plaintext from being saved as report metadata.
public sealed class StoredSubscriptionSnapshot
{
    public List<LocalSubscription?> Subscriptions { get; set; } = [];
    public List<StoredSubscriptionCredential> Credentials { get; set; } = [];
}

public sealed class StoredSubscriptionCredential
{
    public long LocalId { get; set; }
    public string KeyVersion { get; set; } = "";
    public byte[] Payload { get; set; } = [];
}
