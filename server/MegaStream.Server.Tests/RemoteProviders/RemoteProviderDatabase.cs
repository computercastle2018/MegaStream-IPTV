using System.Globalization;
using System.Text;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Tests.RemoteProviders;

// Each test owns a real SQLite file; each context opens its own connection.
internal sealed class RemoteProviderDatabase : IAsyncDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "megastream-remote-" + Guid.NewGuid().ToString("N"));
    public string ConnectionString { get; }

    private RemoteProviderDatabase()
    {
        Directory.CreateDirectory(directory);
        ConnectionString = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(directory, "providers.db"),
            Pooling = false,
            DefaultTimeout = 15
        }.ToString();
    }

    public static async Task<RemoteProviderDatabase> CreateAsync()
    {
        var store = new RemoteProviderDatabase();
        try
        {
            await using var db = store.NewContext();
            // Production migrations are MySQL-specific. Exercise the actual EF model on SQLite.
            await db.Database.EnsureCreatedAsync();
            await db.Database.ExecuteSqlRawAsync("PRAGMA journal_mode=WAL;");
            return store;
        }
        catch
        {
            await store.DisposeAsync();
            throw;
        }
    }

    public AppDbContext NewContext() => new(new DbContextOptionsBuilder<AppDbContext>()
        .UseSqlite(ConnectionString).Options);

    public async Task<Guid> AddInstallationAsync()
    {
        await using var db = NewContext();
        var installation = new Installation
        {
            Id = Guid.NewGuid(),
            TokenHash = Guid.NewGuid().ToString("N"),
            Platform = "android",
            AppVersion = "1.0",
            Status = InstallationStatus.Active
        };
        db.Installations.Add(installation);
        await db.SaveChangesAsync();
        return installation.Id;
    }

    public async Task<string> DumpAllPersistedValuesAsync()
    {
        await using var connection = new SqliteConnection(ConnectionString);
        await connection.OpenAsync();
        var tables = new List<string>();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name";
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync()) tables.Add(reader.GetString(0));
        }
        var dump = new StringBuilder();
        foreach (var table in tables)
        {
            await using var command = connection.CreateCommand();
            command.CommandText = "SELECT * FROM \"" + table.Replace("\"", "\"\"") + "\"";
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
                for (var index = 0; index < reader.FieldCount; index++)
                {
                    var value = reader.GetValue(index);
                    dump.AppendLine(value is byte[] bytes
                        ? Encoding.UTF8.GetString(bytes)
                        : Convert.ToString(value, CultureInfo.InvariantCulture));
                }
        }
        // Include free pages and WAL bytes, not just currently live rows.
        foreach (var file in Directory.GetFiles(directory))
            dump.AppendLine(Encoding.UTF8.GetString(await File.ReadAllBytesAsync(file)));
        return dump.ToString();
    }

    public ValueTask DisposeAsync()
    {
        Directory.Delete(directory, recursive: true);
        return ValueTask.CompletedTask;
    }
}
