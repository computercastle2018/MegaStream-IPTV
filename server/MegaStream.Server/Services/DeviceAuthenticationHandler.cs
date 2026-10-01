using System.Security.Claims;
using System.Text.Encodings.Web;
using MegaStream.Server.Data;
using MegaStream.Server.Models;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace MegaStream.Server.Services;

public sealed class DeviceAuthenticationHandler(IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger,
    UrlEncoder encoder, AppDbContext db, SecretHasher hasher) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "Device";
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        // Never accept credentials from cookies, route values or query strings.
        if (!Request.Path.StartsWithSegments("/api/v1")) return AuthenticateResult.NoResult();
        var authorization = Request.Headers.Authorization;
        if (authorization.Count != 1 || !authorization[0]!.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return AuthenticateResult.NoResult();
        var token = authorization[0]![7..];
        if (token.Length != 43 || token.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-' && c != '_')) return AuthenticateResult.Fail("Invalid device credentials.");
        var hash = hasher.Hash("device", token);
        var device = await db.Installations.AsNoTracking().Where(x => x.TokenHash == hash).Select(x => new { x.Id, x.TokenHash }).SingleOrDefaultAsync(Context.RequestAborted);
        if (device is null || !hasher.Matches("device", token, device.TokenHash)) return AuthenticateResult.Fail("Invalid device credentials.");
        var identity = new ClaimsIdentity(new[] { new Claim(ClaimTypes.NameIdentifier, device.Id.ToString()) }, SchemeName);
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName));
    }
    protected override Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.Headers.WWWAuthenticate = "Bearer";
        return Results.Problem(statusCode: 401, title: "Device authentication required", extensions: new Dictionary<string, object?> { { "code", "unauthorized" } }).ExecuteAsync(Context);
    }
    protected override Task HandleForbiddenAsync(AuthenticationProperties properties) => Results.Problem(statusCode: 403, title: "Forbidden").ExecuteAsync(Context);
}
