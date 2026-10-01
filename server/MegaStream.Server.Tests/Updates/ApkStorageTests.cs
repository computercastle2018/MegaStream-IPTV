using System.IO.Compression;
using System.Security.Cryptography;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using Microsoft.Extensions.Options;
using Xunit;

namespace MegaStream.Server.Tests.Updates;

public sealed class ApkStorageTests : IDisposable
{
    // The output directory avoids platform temp aliases such as macOS /var -> /private/var.
    private readonly string root = Path.Combine(AppContext.BaseDirectory, "apk-tests-" + Guid.NewGuid().ToString("N"));

    private ApkStorage Store(long maxBytes = 209715200, string origin = "https://updates.example.test/") =>
        new(Options.Create(new UpdateOptions { PublicOrigin = origin, StoragePath = root, MaxUploadBytes = maxBytes }));

    [Fact]
    public async Task Valid_apk_returns_actual_size_hash_and_server_key_and_can_be_read_and_deleted()
    {
        var store = Store();
        var id = Guid.NewGuid();
        using var input = Apk();
        var expected = input.ToArray();
        var stored = await store.StoreAsync(id, "client-name.APK", input);
        Assert.Equal($"{id:D}.apk", stored.StorageKey);
        Assert.Equal(expected.LongLength, stored.SizeBytes);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(expected)).ToLowerInvariant(), stored.Sha256);
        using (var read = store.OpenRead(stored.StorageKey))
        using (var copy = new MemoryStream())
        {
            await read.CopyToAsync(copy);
            Assert.Equal(expected, copy.ToArray());
        }
        Assert.Single(Directory.GetFiles(root));
        store.Delete(stored.StorageKey);
        Assert.Empty(Directory.GetFiles(root));
    }

    [Fact]
    public async Task Existing_release_is_never_overwritten_or_removed_after_duplicate_upload()
    {
        var store = Store();
        var id = Guid.NewGuid();
        using var original = Apk(manifestSize: 5);
        var bytes = original.ToArray();
        var saved = await store.StoreAsync(id, "first.apk", original);
        using var replacement = Apk(manifestSize: 10);
        var error = await Assert.ThrowsAsync<DomainException>(() => store.StoreAsync(id, "second.apk", replacement));
        Assert.Equal(409, error.StatusCode);
        Assert.Equal(bytes, File.ReadAllBytes(Path.Combine(root, saved.StorageKey)));
        Assert.Single(Directory.GetFiles(root));
    }

    [Theory]
    [InlineData("../app.apk")]
    [InlineData("dir/app.apk")]
    [InlineData("dir\\app.apk")]
    [InlineData("C:app.apk")]
    [InlineData("app%2eapk.apk")]
    [InlineData("app\napk.apk")]
    [InlineData("app\0.apk")]
    [InlineData("app.zip")]
    [InlineData(".apk")]
    [InlineData("a*.apk")]
    [InlineData("a?.apk")]
    [InlineData("a\".apk")]
    [InlineData("a<.apk")]
    [InlineData("a>.apk")]
    [InlineData("a|.apk")]
    [InlineData("app.apk ")]
    [InlineData("")]
    public async Task Unsafe_filename_is_rejected_without_writing(string name)
    {
        var store = Store();
        using var input = Apk();
        var error = await Assert.ThrowsAsync<DomainException>(() => store.StoreAsync(Guid.NewGuid(), name, input));
        Assert.Equal("invalid_filename", error.Code);
        Assert.Empty(Directory.GetFiles(root));
    }

    [Theory]
    [InlineData("not-zip")]
    [InlineData("missing")]
    [InlineData("empty")]
    [InlineData("nested")]
    [InlineData("duplicate")]
    [InlineData("bomb")]
    public async Task Invalid_archive_or_unbounded_manifest_is_rejected_and_partial_file_removed(string scenario)
    {
        var store = Store();
        using var input = scenario switch
        {
            "not-zip" => new MemoryStream([1, 2, 3, 4]),
            "missing" => Apk("classes.dex"),
            "empty" => Apk(manifestSize: 0),
            "nested" => Apk("dir/AndroidManifest.xml"),
            "duplicate" => Apk(duplicate: true),
            "bomb" => Apk(manifestSize: 1024 * 1024 + 1),
            _ => throw new ArgumentOutOfRangeException(nameof(scenario))
        };
        var error = await Assert.ThrowsAsync<DomainException>(() => store.StoreAsync(Guid.NewGuid(), "app.apk", input));
        Assert.Equal("invalid_apk", error.Code);
        Assert.Empty(Directory.GetFiles(root));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Size_limit_applies_to_metadata_and_actual_nonseekable_bytes(bool seekable)
    {
        using var apk = Apk();
        var store = Store(apk.Length - 1);
        using Stream input = seekable ? new MemoryStream(apk.ToArray()) : new NonSeekableStream(apk.ToArray());
        var error = await Assert.ThrowsAsync<DomainException>(() => store.StoreAsync(Guid.NewGuid(), "app.apk", input));
        Assert.Equal(413, error.StatusCode);
        Assert.Empty(Directory.GetFiles(root));
    }

    [Fact]
    public async Task Upload_exactly_at_limit_succeeds()
    {
        using var input = Apk();
        var store = Store(input.Length);
        var saved = await store.StoreAsync(Guid.NewGuid(), "app.apk", input);
        Assert.Equal(input.Length, saved.SizeBytes);
    }

    [Fact]
    public async Task Read_failure_cleans_partial_upload()
    {
        var store = Store();
        using var input = new FailingStream();
        await Assert.ThrowsAsync<IOException>(() => store.StoreAsync(Guid.NewGuid(), "app.apk", input));
        Assert.Empty(Directory.GetFiles(root));
    }

    [Fact]
    public async Task Cancelled_upload_leaves_no_artifact()
    {
        var store = Store();
        using var input = Apk();
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => store.StoreAsync(Guid.NewGuid(), "app.apk", input, cancellation.Token));
        Assert.Empty(Directory.GetFiles(root));
    }

    [Theory]
    [InlineData("https://updates.example.test", "https://updates.example.test")]
    [InlineData("https://updates.example.test/", "https://updates.example.test")]
    [InlineData("https://updates.example.test:8443/", "https://updates.example.test:8443")]
    public void Download_url_uses_only_configured_origin_and_release_id(string origin, string expected)
    {
        var store = Store(origin: origin);
        var id = Guid.NewGuid();
        Assert.Equal($"{expected}/updates/files/{id:D}/release.apk", store.GetDownloadUrl(id));
    }

    [Theory]
    [InlineData("http://example.test")]
    [InlineData("//example.test")]
    [InlineData("https://user:pass@example.test")]
    [InlineData("https://@example.test")]
    [InlineData("https://example.test/path")]
    [InlineData("https://example.test/.")]
    [InlineData("https://example.test/a/..")]
    [InlineData("https://example.test/%2e")]
    [InlineData("https://example.test?x=1")]
    [InlineData("https://example.test?")]
    [InlineData("https://example.test#fragment")]
    [InlineData("https://example.test#")]
    [InlineData("https://example.test\\evil")]
    [InlineData(" https://example.test")]
    [InlineData("")]
    public void Invalid_origin_is_rejected_at_construction(string origin) =>
        Assert.Throws<ArgumentException>(() => Store(origin: origin));

    [Theory]
    [InlineData("relative")]
    [InlineData("webroot")]
    [InlineData("zero-limit")]
    [InlineData("excessive-limit")]
    public void Unsafe_storage_configuration_is_rejected(string scenario)
    {
        var options = new UpdateOptions
        {
            PublicOrigin = "https://example.test",
            StoragePath = scenario == "relative" ? "uploads" : scenario == "webroot" ? Path.Combine(root, "wwwroot", "uploads") : root,
            MaxUploadBytes = scenario == "zero-limit" ? 0 : scenario == "excessive-limit" ? 209715201 : 1024
        };
        Assert.Throws<ArgumentException>(() => new ApkStorage(Options.Create(options)));
    }

    [Theory]
    [InlineData("../outside.apk")]
    [InlineData("anything.apk")]
    [InlineData("00000000-0000-0000-0000-000000000000.apk")]
    [InlineData("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA.apk")]
    public void Unsafe_keys_cannot_be_read_or_deleted(string key)
    {
        var store = Store();
        Assert.Equal("invalid_storage_key", Assert.Throws<DomainException>(() => store.OpenRead(key)).Code);
        Assert.Equal("invalid_storage_key", Assert.Throws<DomainException>(() => store.Delete(key)).Code);
    }

    [Fact]
    public void Options_mutation_cannot_redirect_an_existing_store()
    {
        var options = new UpdateOptions { PublicOrigin = "https://example.test", StoragePath = root };
        var store = new ApkStorage(Options.Create(options));
        options.PublicOrigin = "http://attacker.test/path";
        options.StoragePath = "relative";
        var id = Guid.NewGuid();
        Assert.Equal($"https://example.test/updates/files/{id:D}/release.apk", store.GetDownloadUrl(id));
    }

    [Fact]
    public void Symbolic_link_artifact_is_not_read_or_deleted()
    {
        if (OperatingSystem.IsWindows()) return; // Symlink creation needs additional privileges on Windows.
        var store = Store();
        var outside = Path.Combine(root, "outside.txt");
        File.WriteAllText(outside, "untouched");
        var key = $"{Guid.NewGuid():D}.apk";
        File.CreateSymbolicLink(Path.Combine(root, key), outside);
        Assert.Equal("unsafe_storage_path", Assert.Throws<DomainException>(() => store.OpenRead(key)).Code);
        Assert.Equal("unsafe_storage_path", Assert.Throws<DomainException>(() => store.Delete(key)).Code);
        Assert.Equal("untouched", File.ReadAllText(outside));
    }

    private static MemoryStream Apk(string entryName = "AndroidManifest.xml", int manifestSize = 4, bool duplicate = false)
    {
        var result = new MemoryStream();
        using (var archive = new ZipArchive(result, ZipArchiveMode.Create, leaveOpen: true))
        {
            using (var entry = archive.CreateEntry(entryName).Open()) entry.Write(new byte[manifestSize]);
            if (duplicate)
                using (var entry = archive.CreateEntry(entryName).Open()) entry.WriteByte(1);
        }
        result.Position = 0;
        return result;
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }

    private class NonSeekableStream(byte[] bytes) : MemoryStream(bytes)
    {
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    }

    private sealed class FailingStream() : NonSeekableStream([1, 2, 3])
    {
        private bool read;
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (read) throw new IOException("Simulated interrupted upload.");
            read = true;
            return base.ReadAsync(buffer, cancellationToken);
        }
    }
}
