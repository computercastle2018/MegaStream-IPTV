using MegaStream.Server.Data;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Services;

public static class PlaybackQualityPolicy
{
    public const string Default = "1080";
    public static IReadOnlyList<string> Values { get; } = ["auto", "480", "720", "1080", "2160"];
    public static bool IsValid(string? quality) => quality is not null && Values.Contains(quality);
    public static string Resolve(string? deviceOverride, string? globalDefault) =>
        IsValid(deviceOverride) ? deviceOverride! : IsValid(globalDefault) ? globalDefault! : Default;
    public static async Task<string> GlobalAsync(AppDbContext db, CancellationToken ct) =>
        Resolve(null, await db.PlaybackQualityDefaults.AsNoTracking()
            .Where(x => x.Id == 1).Select(x => x.Quality).SingleOrDefaultAsync(ct));
}
