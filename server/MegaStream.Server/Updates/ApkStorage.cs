using System.IO.Compression;
using System.Security.Cryptography;
using MegaStream.Server.Services;
using Microsoft.Extensions.Options;

namespace MegaStream.Server.Updates;

public sealed class UpdateOptions
{
    public string PublicOrigin { get; set; } = "";
    public string StoragePath { get; set; } = "";
    public long MaxUploadBytes { get; set; } = 209715200;

    public static void Validate(UpdateOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        var origin = options.PublicOrigin;
        if (string.IsNullOrWhiteSpace(origin) || origin.Any(char.IsWhiteSpace) ||
            origin.Any(char.IsControl) || origin.IndexOfAny(['\\', '%', '?', '#', '@']) >= 0 ||
            !origin.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ||
            !Uri.TryCreate(origin, UriKind.Absolute, out var uri) || uri.Scheme != "https" ||
            string.IsNullOrEmpty(uri.Host) || uri.UserInfo.Length != 0 ||
            (origin.IndexOf('/', 8) is var slash && slash >= 0 && slash != origin.Length - 1))
            throw new ArgumentException("PublicOrigin must be an HTTPS origin without credentials, path, query or fragment.", nameof(options));
        if (string.IsNullOrWhiteSpace(options.StoragePath) || !Path.IsPathFullyQualified(options.StoragePath) ||
            Path.GetFullPath(options.StoragePath).Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                .Any(part => part.Equals("wwwroot", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("StoragePath must be absolute and outside wwwroot.", nameof(options));
        if (options.MaxUploadBytes <= 0 || options.MaxUploadBytes > 209715200)
            throw new ArgumentException("MaxUploadBytes must be between 1 and 209715200 bytes.", nameof(options));
    }
}

public sealed record StoredApk(string StorageKey, long SizeBytes, string Sha256);

/// <summary>Private artifact storage. Its directory must not be mapped by any static-file server.</summary>
public sealed class ApkStorage
{
    private const int MaxManifestBytes = 1024 * 1024;
    private readonly string root;
    private readonly string origin;
    private readonly long maxUploadBytes;

    public ApkStorage(IOptions<UpdateOptions> options)
    {
        ArgumentNullException.ThrowIfNull(options);
        var snapshot = new UpdateOptions
        {
            PublicOrigin = options.Value.PublicOrigin,
            StoragePath = options.Value.StoragePath,
            MaxUploadBytes = options.Value.MaxUploadBytes
        };
        UpdateOptions.Validate(snapshot);
        root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(snapshot.StoragePath));
        origin = new Uri(snapshot.PublicOrigin).GetLeftPart(UriPartial.Authority);
        maxUploadBytes = snapshot.MaxUploadBytes;
        CheckDirectoryChain();
        Directory.CreateDirectory(root);
        CheckDirectoryChain();
    }

    public string GetDownloadUrl(Guid releaseId)
    {
        ValidateReleaseId(releaseId);
        return $"{origin}/updates/files/{releaseId:D}/release.apk";
    }

    public async Task<StoredApk> StoreAsync(Guid releaseId, string fileName, Stream input, CancellationToken ct = default)
    {
        ValidateReleaseId(releaseId);
        if (string.IsNullOrWhiteSpace(fileName) || fileName.Length <= 4 || fileName.Length > 255 || fileName.Any(char.IsControl) ||
            fileName.IndexOfAny(['/', '\\', ':', '%', '*', '?', '"', '<', '>', '|']) >= 0 ||
            !fileName.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
            throw new DomainException(400, "invalid_filename", "A safe .apk filename is required.");
        ArgumentNullException.ThrowIfNull(input);
        ct.ThrowIfCancellationRequested();
        if (input.CanSeek && input.Length - input.Position > maxUploadBytes)
            throw TooLarge();
        CheckDirectoryChain();
        var key = $"{releaseId:D}.apk";
        var destination = ResolveKey(key);
        var temporary = Path.Combine(root, $"{Guid.NewGuid():D}.upload");
        var created = false;
        try
        {
            long size = 0;
            string digest;
            await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.ReadWrite,
                FileShare.None, 81920, FileOptions.Asynchronous))
            {
                created = true;
                (size, digest) = await CopyAndHashAsync(input, output, ct);
                await output.FlushAsync(ct);
                output.Position = 0;
                await ValidateArchiveAsync(output, ct);
            }
            ct.ThrowIfCancellationRequested();
            CheckDirectoryChain();
            RejectLink(destination);
            try { File.Move(temporary, destination, overwrite: false); }
            catch (IOException) when (File.Exists(destination))
            {
                throw new DomainException(409, "apk_exists", "An APK already exists for this release.");
            }
            created = false;
            return new StoredApk(key, size, digest);
        }
        finally
        {
            if (created) File.Delete(temporary);
        }
    }

    public Stream OpenRead(string storageKey)
    {
        var path = ResolveKey(storageKey);
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
    }

    // Only for compensating a failed database insertion; never expose as a device operation.
    public void Delete(string storageKey) => File.Delete(ResolveKey(storageKey));

    private async Task<(long Size, string Digest)> CopyAndHashAsync(Stream input, Stream output, CancellationToken ct)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[81920];
        long size = 0;
        int count;
        while ((count = await input.ReadAsync(buffer.AsMemory(), ct)) != 0)
        {
            if (count > maxUploadBytes - size) throw TooLarge();
            size += count;
            hash.AppendData(buffer, 0, count);
            await output.WriteAsync(buffer.AsMemory(0, count), ct);
        }
        return (size, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant());
    }

    private string ResolveKey(string storageKey)
    {
        if (storageKey is null || storageKey.Length != 40 ||
            !storageKey.EndsWith(".apk", StringComparison.Ordinal) ||
            !Guid.TryParseExact(storageKey[..36], "D", out var id) || id == Guid.Empty ||
            storageKey != $"{id:D}.apk")
            throw new DomainException(400, "invalid_storage_key", "Invalid APK storage key.");
        CheckDirectoryChain();
        var path = Path.Combine(root, storageKey);
        RejectLink(path);
        return path;
    }

    private void CheckDirectoryChain()
    {
        for (DirectoryInfo? directory = new(root); directory is not null; directory = directory.Parent)
            RejectLink(directory.FullName);
    }

    private static void RejectLink(string path)
    {
        var file = new FileInfo(path);
        if (file.LinkTarget is not null || (file.Exists && (file.Attributes & FileAttributes.ReparsePoint) != 0))
            throw new DomainException(400, "unsafe_storage_path", "Symbolic links are not allowed in APK storage.");
        var directory = new DirectoryInfo(path);
        if (directory.LinkTarget is not null || (directory.Exists && (directory.Attributes & FileAttributes.ReparsePoint) != 0))
            throw new DomainException(400, "unsafe_storage_path", "Symbolic links are not allowed in APK storage.");
    }

    private static async Task ValidateArchiveAsync(Stream stream, CancellationToken ct)
    {
        try
        {
            using var archive = new ZipArchive(stream, ZipArchiveMode.Read, leaveOpen: true);
            ZipArchiveEntry? manifest = null;
            foreach (var entry in archive.Entries)
            {
                ct.ThrowIfCancellationRequested();
                if (entry.FullName != "AndroidManifest.xml") continue;
                if (manifest is not null) throw InvalidApk();
                manifest = entry;
            }
            if (manifest is null || manifest.Length <= 0 || manifest.Length > MaxManifestBytes)
                throw InvalidApk();
            using var content = manifest.Open();
            var buffer = new byte[8192];
            long actual = 0;
            int count;
            while ((count = await content.ReadAsync(buffer.AsMemory(), ct)) != 0)
            {
                actual += count;
                if (actual > MaxManifestBytes) throw InvalidApk();
            }
            if (actual == 0 || actual != manifest.Length) throw InvalidApk();
        }
        catch (InvalidDataException) { throw InvalidApk(); }
        catch (NotSupportedException) { throw InvalidApk(); }
    }

    private static void ValidateReleaseId(Guid id)
    {
        if (id == Guid.Empty) throw new DomainException(400, "invalid_release_id", "A nonempty release ID is required.");
    }

    private static DomainException InvalidApk() => new(400, "invalid_apk", "A valid APK ZIP with one nonempty root AndroidManifest.xml (at most 1 MiB) is required.");
    private static DomainException TooLarge() => new(413, "apk_too_large", "The APK exceeds the configured upload size limit.");
}
