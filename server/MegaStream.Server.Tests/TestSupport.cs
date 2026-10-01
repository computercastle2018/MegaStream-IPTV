using MegaStream.Server.Data;
using MegaStream.Server.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Hosting;

namespace MegaStream.Server.Tests;

internal sealed class TestDatabase : IAsyncDisposable
{
    private readonly SqliteConnection connection;
    public AppDbContext Db { get; }
    public SecretHasher Hasher { get; } = new(LeaseSignerTests.Configuration(), new TestEnvironment());

    private TestDatabase(SqliteConnection connection, AppDbContext db)
    {
        this.connection = connection;
        Db = db;
    }

    public static async Task<TestDatabase> CreateAsync()
    {
        var connection = new SqliteConnection($"Data Source=test-{Guid.NewGuid():N};Mode=Memory;Cache=Shared;Pooling=False;Default Timeout=5");
        await connection.OpenAsync();
        var db = new AppDbContext(new DbContextOptionsBuilder<AppDbContext>().UseSqlite(connection).Options);
        // Production migrations target MySQL; use the real EF model on SQLite for service transactions.
        await db.Database.EnsureCreatedAsync();
        return new TestDatabase(connection, db);
    }

    public AppDbContext NewContext() => new(new DbContextOptionsBuilder<AppDbContext>().UseSqlite(connection.ConnectionString).Options);

    public async ValueTask DisposeAsync()
    {
        await Db.DisposeAsync();
        await connection.DisposeAsync();
    }
}

internal sealed class TestClock : TimeProvider
{
    public DateTimeOffset Now { get; set; } = new(2030, 1, 2, 12, 0, 0, TimeSpan.Zero);
    public override DateTimeOffset GetUtcNow() => Now;
}

internal sealed class TestEnvironment : IHostEnvironment
{
    public string EnvironmentName { get; set; } = Environments.Development;
    public string ApplicationName { get; set; } = "MegaStream.Server.Tests";
    public string ContentRootPath { get; set; } = AppContext.BaseDirectory;
    public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
}
