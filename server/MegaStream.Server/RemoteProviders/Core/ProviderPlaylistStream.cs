using System.Buffers;
using System.IO.Pipelines;
using System.Text;

namespace MegaStream.Server.RemoteProviders.Core;

// Owns the upstream request until MVC finishes copying; never buffers a complete playlist on the server.
internal sealed class ProviderPlaylistStream(Stream input, Uri source, HttpClient client,
    HttpResponseMessage response, CancellationTokenSource deadline) : Stream
{
    private readonly PipeReader reader = PipeReader.Create(input);
    private readonly UTF8Encoding encoding = new(false, true);
    private byte[] pending = [];
    private int offset;
    private long received, emitted;
    private bool header, description, entry, ended;

    public async Task InitializeAsync()
    {
        using var prefix = new MemoryStream();
        while (!entry)
        {
            var line = await NextLineAsync();
            if (line is null || prefix.Length + line.Length > 65536) throw ProviderPlaylistExporter.Unavailable();
            prefix.Write(line);
        }
        pending = prefix.ToArray();
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        if (buffer.Length == 0) return 0;
        ct.ThrowIfCancellationRequested();
        try
        {
            if (offset == pending.Length)
            {
                pending = await NextLineAsync() ?? [];
                offset = 0;
            }
            var count = Math.Min(buffer.Length, pending.Length - offset);
            pending.AsMemory(offset, count).CopyTo(buffer);
            offset += count;
            return count;
        }
        catch (Exception error) when (error is IOException or OperationCanceledException or DecoderFallbackException)
        { throw ProviderPlaylistExporter.Unavailable(); }
    }

    private async Task<byte[]?> NextLineAsync()
    {
        if (ended) return null;
        while (true)
        {
            var result = await reader.ReadAsync(deadline.Token);
            var bytes = result.Buffer;
            var newline = bytes.PositionOf((byte)'\n');
            if (newline is null && !result.IsCompleted)
            {
                if (bytes.Length > 32768) throw ProviderPlaylistExporter.Unavailable();
                reader.AdvanceTo(bytes.Start, bytes.End);
                continue;
            }
            var line = newline is { } position ? bytes.Slice(0, position) : bytes;
            if (line.Length > 32768 || (received += line.Length + (newline.HasValue ? 1 : 0)) > ProviderPlaylistExporter.MaximumBytes)
                throw ProviderPlaylistExporter.Unavailable();
            var text = encoding.GetString(line.ToArray()).TrimEnd('\r');
            reader.AdvanceTo(newline is { } end ? bytes.GetPosition(1, end) : bytes.End);
            if (result.IsCompleted && newline is null && line.Length == 0)
            {
                ended = true;
                if (!entry || description) throw ProviderPlaylistExporter.Unavailable();
                return null;
            }
            return ValidateLine(text);
        }
    }

    private byte[] ValidateLine(string text)
    {
        if (text.Any(c => char.IsControl(c) && c != '\t')) throw ProviderPlaylistExporter.Unavailable();
        var line = text.Trim();
        if (!header)
        {
            if (line.TrimStart('\uFEFF') != "#EXTM3U") throw ProviderPlaylistExporter.Unavailable();
            header = true; text = "#EXTM3U";
        }
        else if (line.StartsWith("#EXTINF:", StringComparison.Ordinal)) description = true;
        else if (line.Length != 0 && !line.StartsWith('#'))
        {
            if (!description || !Uri.TryCreate(source, line, out var stream) || stream.Scheme is not ("http" or "https") ||
                stream.UserInfo.Length != 0 || stream.Fragment.Length != 0) throw ProviderPlaylistExporter.Unavailable();
            text = stream.AbsoluteUri; description = false; entry = true;
        }
        var bytes = encoding.GetBytes(text + "\n");
        if ((emitted += bytes.Length) > ProviderPlaylistExporter.MaximumBytes) throw ProviderPlaylistExporter.Unavailable();
        return bytes;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { reader.Complete(); response.Dispose(); client.Dispose(); deadline.Dispose(); }
        base.Dispose(disposing);
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) => ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();
    public override void Flush() => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
