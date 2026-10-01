using System.Text.Json;
using MegaStream.Server.RemoteProviders;
using MegaStream.Server.RemoteProviders.Core;
using Microsoft.Extensions.DependencyInjection;
using MegaStream.Server.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class ProviderEncryptionTests
{
    [Fact]
    public async Task Identical_plaintext_and_AAD_use_fresh_nonce_and_ciphertext_on_each_encryption()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        using var cipher = Cipher();
        var input = RemoteProviderTestData.Profile();
        var id = Guid.NewGuid();
        var first = cipher.Encrypt(id, input.Type, 1, input.Configuration);
        var second = cipher.Encrypt(id, input.Type, 1, input.Configuration);
        Assert.False(first.AsSpan(0, 12).SequenceEqual(second.AsSpan(0, 12)));
        Assert.False(first.AsSpan(28).SequenceEqual(second.AsSpan(28)));
        await using (var db = store.NewContext())
        {
            db.Set<RemoteProviderProfile>().Add(new()
            {
                Id = id, Type = input.Type, DisplayName = input.DisplayName,
                Revision = 1, EncryptedPayload = first, KeyVersion = cipher.KeyVersion
            });
            await db.SaveChangesAsync();
        }
        foreach (var encrypted in new[] { first, second })
        {
            await using (var writer = store.NewContext())
            {
                var row = await writer.Set<RemoteProviderProfile>().SingleAsync();
                row.EncryptedPayload = encrypted;
                await writer.SaveChangesAsync();
            }
            await using var reader = store.NewContext();
            var persisted = await reader.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync();
            Assert.Equal(JsonSerializer.Serialize(input.Configuration), JsonSerializer.Serialize(cipher.Decrypt(persisted)));
        }
    }

    [Theory]
    [InlineData("wrong-key")]
    [InlineData("profile-id")]
    [InlineData("provider-type")]
    [InlineData("profile-revision")]
    [InlineData("key-version")]
    [InlineData("ciphertext")]
    public async Task Persisted_payload_rejects_wrong_key_or_tampered_authenticated_context_without_exposing_secrets(string tampering)
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        using var originalCipher = Cipher();
        var input = RemoteProviderTestData.Profile();
        var id = Guid.NewGuid();
        var payload = originalCipher.Encrypt(id, input.Type, 5, input.Configuration);
        var row = new RemoteProviderProfile
        {
            Id = tampering == "profile-id" ? Guid.NewGuid() : id,
            Type = tampering == "provider-type" ? RemoteProviderValues.M3u : input.Type,
            Revision = tampering == "profile-revision" ? 6 : 5,
            KeyVersion = tampering == "key-version" ? "2" : originalCipher.KeyVersion,
            DisplayName = input.DisplayName,
            EncryptedPayload = payload
        };
        if (tampering == "ciphertext") row.EncryptedPayload[^1] ^= 1;
        await using (var writer = store.NewContext())
        {
            writer.Set<RemoteProviderProfile>().Add(row);
            await writer.SaveChangesAsync();
        }
        await using var reader = store.NewContext();
        using var decryptor = Cipher(tampering == "wrong-key" ? (byte)99 : (byte)7);
        var persisted = await reader.Set<RemoteProviderProfile>().AsNoTracking().SingleAsync();
        var error = Assert.Throws<DomainException>(() => decryptor.Decrypt(persisted));
        Assert.Equal(503, error.StatusCode);
        Assert.Equal("provider_configuration_unavailable", error.Code);
        foreach (var secret in RemoteProviderTestData.SecretValues(input.Configuration))
            Assert.DoesNotContain(secret, error.ToString(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("missing-key")]
    [InlineData("malformed-key")]
    [InlineData("short-key")]
    [InlineData("zero-version")]
    [InlineData("negative-version")]
    public void Invalid_encryption_configuration_fails_module_registration_before_serving_requests(string scenario)
    {
        var encoded = scenario switch
        {
            "missing-key" => null,
            "malformed-key" => "private-invalid-base64-key!",
            "short-key" => Convert.ToBase64String(new byte[31]),
            _ => Convert.ToBase64String(Enumerable.Repeat((byte)13, 32).ToArray())
        };
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["PROVIDER_SECRET_KEY"] = encoded,
            ["PROVIDER_SECRET_KEY_VERSION"] = scenario == "zero-version" ? "0" : scenario == "negative-version" ? "-1" : "1"
        }).Build();
        var error = Assert.Throws<InvalidOperationException>(() => new ServiceCollection().AddRemoteProviders(config));
        if (encoded is not null) Assert.DoesNotContain(encoded, error.ToString(), StringComparison.Ordinal);
    }

    private static ProviderPayloadCipher Cipher(byte keyByte = 7) => new(new ConfigurationBuilder()
        .AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["PROVIDER_SECRET_KEY"] = Convert.ToBase64String(Enumerable.Repeat(keyByte, 32).ToArray()),
            ["PROVIDER_SECRET_KEY_VERSION"] = "1"
        }).Build());
}
