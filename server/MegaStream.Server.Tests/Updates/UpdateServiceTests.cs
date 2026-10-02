using System.IO.Compression;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Xunit;

namespace MegaStream.Server.Tests.Updates;

public sealed class UpdateServiceTests : IDisposable
{
    private readonly SqliteConnection connection = new("Data Source=:memory:");
    private readonly string root = Path.Combine(AppContext.BaseDirectory, "update-service-" + Guid.NewGuid().ToString("N"));
    private readonly UpdateTestDb db;
    private readonly UpdateService service;

    public UpdateServiceTests()
    {
        connection.Open();
        db = new UpdateTestDb(new DbContextOptionsBuilder<AppDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();
        var storage = new ApkStorage(Options.Create(new UpdateOptions { PublicOrigin = "https://updates.example.test", StoragePath = root }));
        service = new UpdateService(db, storage);
    }

    [Theory]
    [InlineData(2147483648L, true)]
    [InlineData(3000000000L, false)]
    [InlineData(3000000001L, false)]
    public async Task Check_compares_integer_versions_without_truncation(long current, bool expected)
    {
        var device = await Device();
        var release = await Release(3000000000L);
        var response = await service.CheckAsync(device, Request(current));
        Assert.Equal(expected, response.Latest is not null);
        if (expected) Assert.Equal(release.Id, response.Latest!.ReleaseId);
    }

    [Theory]
    [InlineData("stable", 34, "arm64_v8a", true)]
    [InlineData("beta", 34, "arm64_v8a", false)]
    [InlineData("stable", 20, "arm64_v8a", false)]
    [InlineData("stable", 34, "x86", false)]
    [InlineData("stable", 34, "other", false)]
    public async Task Check_filters_channel_sdk_and_abi(string channel, int sdk, string abi, bool expected)
    {
        var device = await Device();
        await Release(10, "arm64_v8a");
        var response = await service.CheckAsync(device, new UpdateCheckRequest(1,
            channel == "stable" ? "com.megastream.app" : "com.megastream.app.beta", channel, sdk, Abi: abi));
        Assert.Equal(expected, response.Latest is not null);
    }

    [Fact]
    public async Task Public_latest_prefers_newer_universal_over_older_exact_abi_and_filters_incompatible_releases()
    {
        await Release(10, "arm64_v8a");
        var universal = await Release(11);
        await Release(12, "x86");
        Assert.Equal(universal.Id, (await service.GetLatestAsync("stable", "arm64_v8a"))!.ReleaseId);
        Assert.Equal(universal.Id, (await service.GetLatestAsync("stable", "other"))!.ReleaseId);
        Assert.Equal(universal.Id, (await service.GetLatestAsync("stable", "x86_64"))!.ReleaseId);
        Assert.Null(await service.GetLatestAsync("beta", "other"));
    }

    [Fact]
    public async Task Draft_artifact_is_hidden_and_publish_retry_preserves_timestamp()
    {
        using var apk = Apk();
        var release = await service.UploadAsync(Metadata(10), "app.apk", apk);
        Assert.Equal(404, (await Assert.ThrowsAsync<DomainException>(() => service.GetPublishedAsync(release.Id))).StatusCode);
        Assert.Null(await service.GetLatestAsync("stable", "other"));
        await service.PublishAsync(release.Id);
        var published = release.PublishedAt;
        await service.PublishAsync(release.Id);
        Assert.Equal(published, release.PublishedAt);
        var manifest = await service.GetLatestAsync("stable", "other");
        Assert.Equal(manifest!.ReleaseUrl, manifest.DownloadUrl);
        Assert.Equal($"https://updates.example.test/updates/files/{release.Id:D}/release.apk", manifest.DownloadUrl);
        Assert.Equal(DateTimeKind.Utc, manifest.PublishedAt.Kind);
    }

    [Fact]
    public async Task Prompt_progress_is_sequential_and_stale_reports_never_regress()
    {
        var device = await Device();
        var release = await Release(10);
        await service.CheckAsync(device, Request(1));
        var command = Assert.Single(await service.SendAsync(release.Id, [device], "prompt"));
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => service.ReportAsync(device, command.Id, "downloaded", null))).StatusCode);
        foreach (var status in new[] { "ack", "downloaded", "installPrompted", "installed" })
        {
            var progress = await service.ReportAsync(device, command.Id, status, null);
            var revision = progress.Revision;
            Assert.Equal(status, (await service.ReportAsync(device, command.Id, status, null)).Status);
            Assert.Equal(revision, progress.Revision);
            Assert.Equal(status, (await service.ReportAsync(device, command.Id, "ack", null)).Status);
        }
        Assert.NotNull(command.AckAt); Assert.NotNull(command.DownloadedAt);
        Assert.NotNull(command.InstallPromptedAt); Assert.NotNull(command.InstalledAt);
        Assert.Null(await service.GetPendingAsync(device));
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => service.ReportAsync(device, command.Id, "failed", "late"))).StatusCode);
        await service.CheckAsync(device, Request(10));
        Assert.Equal(command.Id, Assert.Single(await service.SendAsync(release.Id, [device, device], "prompt")).Id);
        Assert.Single(await db.Set<DeviceUpdateCommand>().ToListAsync());
    }

    [Fact]
    public async Task Managed_installation_skips_prompt_only_after_download()
    {
        var device = await Device();
        var release = await Release(10);
        await service.CheckAsync(device, Request(1) with { Mode = "managed" });
        var command = Assert.Single(await service.SendAsync(release.Id, [device], "managed"));
        await service.ReportAsync(device, command.Id, "ack", null);
        await service.ReportAsync(device, command.Id, "downloaded", null);
        Assert.Equal("installed", (await service.ReportAsync(device, command.Id, "installed", null)).Status);
        Assert.Null(command.InstallPromptedAt);
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => service.SendAsync(release.Id, [device], "prompt"))).StatusCode);
    }

    [Fact]
    public async Task Foreign_command_is_not_found_and_failure_is_terminal()
    {
        var owner = await Device();
        var stranger = await Device();
        var release = await Release(10);
        await service.CheckAsync(owner, Request(1));
        var command = Assert.Single(await service.SendAsync(release.Id, [owner], "prompt"));
        Assert.Equal(404, (await Assert.ThrowsAsync<DomainException>(() => service.ReportAsync(stranger, command.Id, "ack", null))).StatusCode);
        Assert.Null(await service.GetPendingAsync(stranger));
        Assert.Equal(command.Id, (await service.GetPendingAsync(owner))!.CommandId);
        var failed = await service.ReportAsync(owner, command.Id, "failed", new string('x', 128));
        Assert.Equal(128, failed.ErrorCode!.Length);
        Assert.NotNull(failed.FailedAt);
        Assert.Equal(failed.Revision, (await service.ReportAsync(owner, command.Id, "failed", "different")).Revision);
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => service.ReportAsync(owner, command.Id, "ack", null))).StatusCode);
        Assert.Null(await service.GetPendingAsync(owner));
    }

    [Theory]
    [InlineData("unknown")]
    [InlineData("revoked")]
    [InlineData("incompatible")]
    [InlineData("managed")]
    public async Task Invalid_bulk_target_leaves_no_partial_commands(string invalidKind)
    {
        var valid = await Device();
        var invalid = await Device();
        var release = await Release(10);
        await service.CheckAsync(valid, Request(1) with { Mode = "managed" });
        await service.CheckAsync(invalid, Request(1));
        if (invalidKind == "unknown") invalid = Guid.NewGuid();
        if (invalidKind == "revoked")
        {
            (await db.Installations.SingleAsync(x => x.Id == invalid)).Status = InstallationStatus.Revoked;
            await db.SaveChangesAsync();
        }
        if (invalidKind == "incompatible") await service.CheckAsync(invalid, Request(10));
        await Assert.ThrowsAsync<DomainException>(() => service.SendAsync(release.Id, [valid, invalid], invalidKind == "managed" ? "managed" : "prompt"));
        Assert.Empty(await db.Set<DeviceUpdateCommand>().ToListAsync());
        Assert.Empty(db.ChangeTracker.Entries<DeviceUpdateCommand>());
    }

    [Fact]
    public async Task Scalar_observation_preserves_compatibility_and_unknown_state_is_ineligible()
    {
        var known = await Device();
        var unknown = await Device();
        var release = await Release(10);
        await service.CheckAsync(known, Request(1) with { Abi = "arm64_v8a" });
        await using (var tx = await db.Database.BeginTransactionAsync())
        {
            await service.ObserveAsync(known, 2, true);
            await service.ObserveAsync(unknown, 2, false);
            await tx.CommitAsync();
        }
        var state = await db.Set<DeviceUpdateState>().SingleAsync(x => x.InstallationId == known);
        Assert.Equal("com.megastream.app", state.PackageName); Assert.Equal("stable", state.Channel);
        Assert.Equal(34, state.Sdk); Assert.Equal("arm64_v8a", state.Abi); Assert.Equal("managed", state.Mode);
        Assert.Equal(2, state.VersionCode);
        Assert.Single(await service.SendAsync(release.Id, [known], "managed"));
        await Assert.ThrowsAsync<DomainException>(() => service.SendAsync(release.Id, [unknown], "prompt"));
        Assert.Single(await service.OutdatedAsync(release.Id));
    }

    [Fact]
    public async Task Structured_observation_uses_callers_transaction_and_rolls_back_with_it()
    {
        var device = await Device();
        await using (var tx = await db.Database.BeginTransactionAsync())
        {
            await service.ObserveAsync(device, Request(5));
            await tx.RollbackAsync();
        }
        db.ChangeTracker.Clear();
        Assert.Empty(await db.Set<DeviceUpdateState>().ToListAsync());
    }

    [Fact]
    public async Task Version_uniqueness_is_global_and_duplicate_does_not_delete_original_artifact()
    {
        var release = await Release(10);
        var original = File.ReadAllBytes(Path.Combine(root, release.StorageKey));
        using var apk = Apk();
        var beta = Metadata(10) with { Channel = "beta", PackageName = "com.megastream.app.beta" };
        Assert.Equal(409, (await Assert.ThrowsAsync<DomainException>(() => service.UploadAsync(beta, "app.apk", apk))).StatusCode);
        Assert.Single(Directory.GetFiles(root));
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(root, release.StorageKey)));
        db.Set<UpdateRelease>().Add(new UpdateRelease { VersionCode = 10 });
        await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
    }

    [Theory]
    [InlineData("wrong-package")]
    [InlineData("wrong-channel")]
    [InlineData("certificate-colons")]
    [InlineData("certificate-nonhex")]
    [InlineData("zero-version")]
    [InlineData("zero-sdk")]
    [InlineData("other-abi")]
    public async Task Unsafe_release_metadata_is_rejected_before_storage(string scenario)
    {
        var metadata = Metadata(1);
        metadata = scenario switch
        {
            "wrong-package" => metadata with { PackageName = "com.attacker.app" },
            "wrong-channel" => metadata with { Channel = "beta" },
            "certificate-colons" => metadata with { SigningCertificateSha256 = string.Join(":", Enumerable.Repeat("ab", 32)) },
            "certificate-nonhex" => metadata with { SigningCertificateSha256 = new string('z', 64) },
            "zero-version" => metadata with { VersionCode = 0 },
            "zero-sdk" => metadata with { MinSdk = 0 },
            "other-abi" => metadata with { Abi = "other" },
            _ => throw new ArgumentOutOfRangeException(nameof(scenario))
        };
        using var apk = Apk();
        Assert.Equal(400, (await Assert.ThrowsAsync<DomainException>(() => service.UploadAsync(metadata, "app.apk", apk))).StatusCode);
        Assert.Empty(Directory.GetFiles(root));
    }

    [Theory]
    [InlineData("")]
    [InlineData("download failed")]
    [InlineData("https://example.test/token")]
    [InlineData("token=secret")]
    [InlineData("unsafe\ntext")]
    public async Task Failure_rejects_diagnostic_text_without_mutating_command(string errorCode)
    {
        var device = await Device();
        var release = await Release(10);
        await service.CheckAsync(device, Request(1));
        var command = Assert.Single(await service.SendAsync(release.Id, [device], "prompt"));
        Assert.Equal(400, (await Assert.ThrowsAsync<DomainException>(() => service.ReportAsync(device, command.Id, "failed", errorCode))).StatusCode);
        Assert.Equal("pending", command.Status);
        Assert.Null(command.FailedAt);
        Assert.Equal("unknown", (await service.ReportAsync(device, command.Id, "failed", null)).ErrorCode);
    }

    [Fact]
    public async Task Revoked_device_is_forbidden_and_nonpositive_observations_are_rejected()
    {
        var device = await Device();
        Assert.Equal(400, (await Assert.ThrowsAsync<DomainException>(() => service.CheckAsync(device, Request(0)))).StatusCode);
        Assert.Equal(400, (await Assert.ThrowsAsync<DomainException>(() => service.ObserveAsync(device, 0, false))).StatusCode);
        (await db.Installations.SingleAsync(x => x.Id == device)).Status = InstallationStatus.Revoked;
        await db.SaveChangesAsync();
        var error = await Assert.ThrowsAsync<DomainException>(() => service.GetPendingAsync(device));
        Assert.Equal(403, error.StatusCode);
        Assert.Equal("installation_disabled", error.Code);
    }

    [Fact]
    public async Task Device_check_prefers_newer_universal_over_older_exact_abi()
    {
        var device = await Device();
        await Release(10, "arm64_v8a");
        var universal = await Release(11);
        var response = await service.CheckAsync(device, Request(1) with { Abi = "arm64_v8a" });
        Assert.Equal(universal.Id, response.Latest!.ReleaseId);
    }

    private async Task<Guid> Device()
    {
        var installation = new Installation { TokenHash = Guid.NewGuid().ToString("N"), AppVersion = "999.999.999" };
        db.Installations.Add(installation); await db.SaveChangesAsync();
        return installation.Id;
    }

    private async Task<UpdateRelease> Release(long version, string abi = "universal")
    {
        using var apk = Apk();
        var release = await service.UploadAsync(Metadata(version) with { Abi = abi }, "app.apk", apk);
        return await service.PublishAsync(release.Id);
    }

    private static ReleaseUpload Metadata(long version) => new(version, "v" + version, "stable", "com.megastream.app", 21, false, null, "Release notes");
    private static UpdateCheckRequest Request(long version) => new(version, "com.megastream.app", "stable", 34);
    private static MemoryStream Apk()
    {
        var stream = new MemoryStream();
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        using (var manifest = archive.CreateEntry("AndroidManifest.xml").Open()) manifest.WriteByte(1);
        stream.Position = 0;
        return stream;
    }

    public void Dispose()
    {
        db.Dispose(); connection.Dispose();
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }

    private sealed class UpdateTestDb(DbContextOptions<AppDbContext> options) : AppDbContext(options)
    {
        protected override void OnModelCreating(ModelBuilder builder)
        {
            base.OnModelCreating(builder);
            UpdateModelConfiguration.Configure(builder);
        }
    }
}
