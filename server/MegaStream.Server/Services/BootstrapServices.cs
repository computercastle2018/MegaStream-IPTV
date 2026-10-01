using MegaStream.Server.Data;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace MegaStream.Server.Services;

public static class AdminSeeder
{
    public static async Task SeedAsync(IServiceProvider services, IConfiguration configuration, IHostEnvironment environment)
    {
        var users = services.GetRequiredService<UserManager<IdentityUser>>();
        if (await users.Users.AnyAsync()) return;
        var email = configuration["ADMIN_EMAIL"];
        var password = configuration["ADMIN_PASSWORD"];
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrWhiteSpace(password))
        {
            if (!await users.Users.AnyAsync() && !environment.IsDevelopment() && !environment.IsEnvironment("Testing"))
                throw new InvalidOperationException("ADMIN_EMAIL and ADMIN_PASSWORD must seed the initial administrator.");
            return;
        }
        if (email.Length > 254 || !System.Net.Mail.MailAddress.TryCreate(email, out var address) || address.Address != email)
            throw new InvalidOperationException("ADMIN_EMAIL is invalid.");
        if (await users.FindByEmailAsync(email) is not null) return;
        var result = await users.CreateAsync(new IdentityUser { UserName = email, Email = email, EmailConfirmed = true }, password);
        if (!result.Succeeded) throw new InvalidOperationException("Administrator seed failed: " + string.Join(", ", result.Errors.Select(x => x.Code)));
    }
}
public sealed class RetentionService(IServiceScopeFactory scopes, IConfiguration configuration, ILogger<RetentionService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
                var retention = Math.Clamp(configuration.GetValue("DIAGNOSTIC_RETENTION_DAYS", 30), 1, 90);
                var cutoff = DateTime.UtcNow.AddDays(-retention);
                await db.DiagnosticEvents.Where(x => x.CreatedAt < cutoff).ExecuteDeleteAsync(stoppingToken);
                await db.DeviceSessions.Where(x => x.LastHeartbeatAt < cutoff).ExecuteDeleteAsync(stoppingToken);
                await db.Set<MegaStream.Server.V1.V1DiagnosticReceipt>().Where(x => x.ReceivedAt < cutoff).ExecuteDeleteAsync(stoppingToken);
                await db.Set<MegaStream.Server.V1.V1SessionState>().Where(x => x.LastAcceptedAt < cutoff).ExecuteDeleteAsync(stoppingToken);
                var now = DateTime.UtcNow;
                await db.ActivationCodes.Where(x => x.ExpiresAt < now && (x.Status == Models.ActivationCodeStatus.Pending || x.Status == Models.ActivationCodeStatus.Approved))
                    .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, Models.ActivationCodeStatus.Expired), stoppingToken);
                await db.ActivationCodes.Where(x => x.ExpiresAt < cutoff).ExecuteDeleteAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (System.Data.Common.DbException ex) { logger.LogError("Retention cleanup failed ({ErrorType}).", ex.GetType().Name); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
