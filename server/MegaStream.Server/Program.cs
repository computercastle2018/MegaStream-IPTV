using System.Net;
using System.Security.Claims;
using System.Threading.RateLimiting;
using MegaStream.Server.Data;
using MegaStream.Server.Services;
using MegaStream.Server.Updates;
using MegaStream.Server.V1;
using MegaStream.Server.RemoteProviders;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

var migrateOnly = args.Contains("--migrate-only", StringComparer.Ordinal);
var builder=WebApplication.CreateBuilder(args.Where(argument => argument != "--migrate-only").ToArray());
builder.WebHost.ConfigureKestrel(options=>options.Limits.MaxRequestBodySize=512*1024);
// Request paths and query strings can contain client-supplied secrets even when rejected.
builder.Logging.AddFilter("Microsoft.AspNetCore.Hosting.Diagnostics", LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.AspNetCore.HttpLogging", LogLevel.None);
var connection=builder.Configuration["MYSQL_CONNECTION_STRING"]??builder.Configuration.GetConnectionString("DefaultConnection");
if(string.IsNullOrWhiteSpace(connection)&&!builder.Environment.IsEnvironment("Testing"))
    throw new InvalidOperationException("MYSQL_CONNECTION_STRING or ConnectionStrings:DefaultConnection must be configured.");
builder.Services.AddDbContext<AppDbContext>(options=>options.UseMySql(connection??"Server=localhost;Database=megastream;User=test;Password=test",new MySqlServerVersion(new Version(8,0,36))));
builder.Services.AddIdentity<IdentityUser,IdentityRole>(options=>{
    options.User.RequireUniqueEmail=true;
    options.Password.RequiredLength=14;options.Password.RequireDigit=true;options.Password.RequireNonAlphanumeric=true;
    options.Password.RequireUppercase=true;options.Password.RequireLowercase=true;
    options.Lockout.DefaultLockoutTimeSpan=TimeSpan.FromMinutes(15);options.Lockout.MaxFailedAccessAttempts=5;
}).AddEntityFrameworkStores<AppDbContext>().AddDefaultTokenProviders();
builder.Services.ConfigureApplicationCookie(options=>{
    options.LoginPath="/Account/Login";options.AccessDeniedPath="/Account/Login";
    options.Cookie.Name=builder.Environment.IsDevelopment()?"MegaStream.Admin":"__Host-MegaStream.Admin";
    options.Cookie.HttpOnly=true;options.Cookie.SameSite=SameSiteMode.Strict;
    options.Cookie.SecurePolicy=builder.Environment.IsDevelopment()?CookieSecurePolicy.SameAsRequest:CookieSecurePolicy.Always;
    options.ExpireTimeSpan=TimeSpan.FromHours(4);options.SlidingExpiration=false;
    options.Events.OnRedirectToLogin=context=>{
        if(context.Request.Path.StartsWithSegments("/api"))return Results.Problem(statusCode:401,title:"Unauthorized").ExecuteAsync(context.HttpContext);
        context.Response.Redirect(context.RedirectUri);return Task.CompletedTask;
    };
    options.Events.OnRedirectToAccessDenied=context=>{
        if(context.Request.Path.StartsWithSegments("/api"))return Results.Problem(statusCode:403,title:"Forbidden").ExecuteAsync(context.HttpContext);
        context.Response.Redirect(context.RedirectUri);return Task.CompletedTask;
    };
});
builder.Services.AddAuthentication().AddScheme<AuthenticationSchemeOptions,DeviceAuthenticationHandler>(DeviceAuthenticationHandler.SchemeName,_=>{});
builder.Services.AddAuthorization(options=>options.AddPolicy("Admin",policy=>policy.AddAuthenticationSchemes(IdentityConstants.ApplicationScheme).RequireAuthenticatedUser()));
builder.Services.AddAntiforgery(options=>{
    options.Cookie.HttpOnly=true;options.Cookie.SameSite=SameSiteMode.Strict;
    options.Cookie.SecurePolicy=builder.Environment.IsDevelopment()?CookieSecurePolicy.SameAsRequest:CookieSecurePolicy.Always;
});
var protection=builder.Services.AddDataProtection().SetApplicationName("MegaStream.Server");
var keyDirectory=builder.Configuration["DATA_PROTECTION_KEYS_PATH"];
if(!string.IsNullOrWhiteSpace(keyDirectory))protection.PersistKeysToFileSystem(new DirectoryInfo(keyDirectory));
else if(!builder.Environment.IsDevelopment()&&!builder.Environment.IsEnvironment("Testing"))throw new InvalidOperationException("DATA_PROTECTION_KEYS_PATH must persist production cookie keys.");
// Entrypoint ownership retains the private certificate through app.Run and disposes it on shutdown or startup failure.
using var dataProtectionCertificate = DataProtectionCertificate.Load(builder.Configuration, builder.Environment);
if (dataProtectionCertificate is not null) protection.ProtectKeysWithCertificate(dataProtectionCertificate);
var trustedProxyValues=(builder.Configuration["TRUSTED_PROXIES"]??"").Split(',',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries);
if(!string.IsNullOrWhiteSpace(builder.Configuration["TRUSTED_PROXIES"])&&trustedProxyValues.Length==0)
    throw new InvalidOperationException("TRUSTED_PROXIES must contain explicit IP addresses.");
builder.Services.Configure<ForwardedHeadersOptions>(options=>{
    options.ForwardedHeaders=ForwardedHeaders.XForwardedFor|ForwardedHeaders.XForwardedProto;
    options.ForwardLimit=1;options.KnownNetworks.Clear();options.KnownProxies.Clear();
    foreach(var value in trustedProxyValues)
    {
        if(!IPAddress.TryParse(value,out var address))throw new InvalidOperationException("TRUSTED_PROXIES must contain explicit IP addresses.");
        options.KnownProxies.Add(address);
    }
});
builder.Services.AddControllersWithViews().ConfigureApiBehaviorOptions(options=>options.InvalidModelStateResponseFactory=context=>
{
    var problem = new ProblemDetails { Status = 400, Title = "Invalid request", Detail = "Request fields are missing or invalid." };
    problem.Extensions["code"] = "invalid_request";
    problem.Extensions["traceId"] = context.HttpContext.TraceIdentifier;
    var response = new BadRequestObjectResult(problem);
    response.ContentTypes.Add("application/problem+json");
    return response;
});
builder.Services.AddProblemDetails(options => options.CustomizeProblemDetails = context =>
{
    context.ProblemDetails.Extensions.TryAdd("code", "http_" + context.ProblemDetails.Status);
    context.ProblemDetails.Extensions["traceId"] = context.HttpContext.TraceIdentifier;
});
builder.Services.AddUpdateManagement(builder.Configuration);
builder.Services.AddV1Protocol();
builder.Services.AddRemoteProviders(builder.Configuration);
builder.Services.AddHttpsRedirection(options=>options.HttpsPort=443);
builder.Services.AddScoped<IAdminService,AdminService>();builder.Services.AddScoped<AdminService>();builder.Services.AddScoped<DeviceService>();
builder.Services.AddSingleton<LeaseSigner>();builder.Services.AddSingleton<SecretHasher>();builder.Services.AddHostedService<RetentionService>();
builder.Services.AddRateLimiter(options=>{
    options.RejectionStatusCode=429;
    options.GlobalLimiter=PartitionedRateLimiter.Create<HttpContext,string>(context=>context.Request.Path.StartsWithSegments("/health")
        ? RateLimitPartition.GetNoLimiter("health")
        : RateLimitPartition.GetFixedWindowLimiter(RateLimitIdentity(context),
            _=>new FixedWindowRateLimiterOptions {PermitLimit=120,Window=TimeSpan.FromMinutes(1),QueueLimit=0}));
    options.OnRejected=(context,ct)=>new ValueTask(Results.Problem(statusCode:429,title:"Too many requests").ExecuteAsync(context.HttpContext));
});
var app=builder.Build();
// Eagerly validate signing configuration; never silently fall back to an ephemeral production key.
_=app.Services.GetRequiredService<LeaseSigner>();
_=app.Services.GetRequiredService<SecretHasher>();
if(trustedProxyValues.Length>0)app.UseForwardedHeaders();
app.UseExceptionHandler(error=>error.Run(async context=>{
    var exception=context.Features.Get<IExceptionHandlerFeature>()?.Error;
    if(exception is DomainException domain)await Results.Problem(statusCode:domain.StatusCode,title:domain.Message,extensions:new Dictionary<string,object?>{{"code",domain.Code}}).ExecuteAsync(context);
    else await Results.Problem(statusCode:500,title:"An unexpected server error occurred.").ExecuteAsync(context);
}));
app.Use(async(context,next)=>{
    context.Response.OnStarting(()=>{
        var immutableApk = (HttpMethods.IsGet(context.Request.Method) || HttpMethods.IsHead(context.Request.Method))
            && context.Request.Path.StartsWithSegments("/updates/files")
            && context.Response.StatusCode is 200 or 206
            && context.Response.Headers.CacheControl.ToString().Contains("immutable", StringComparison.Ordinal);
        if (!immutableApk)
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Pragma = "no-cache";
        }
        context.Response.Headers.XContentTypeOptions="nosniff";context.Response.Headers["Referrer-Policy"]="no-referrer";
        context.Response.Headers["Content-Security-Policy"]="default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
        return Task.CompletedTask;
    });
    await next();
});
if(!app.Environment.IsDevelopment()&&!app.Environment.IsEnvironment("Testing"))
{
    app.UseHsts();
    app.UseWhen(context=>!context.Request.Path.StartsWithSegments("/health"),branch=>branch.UseHttpsRedirection());
}
app.UseStatusCodePages(async context=>{
    if(context.HttpContext.Request.Path.StartsWithSegments("/api"))
        await Results.Problem(statusCode:context.HttpContext.Response.StatusCode,title:"Request could not be completed").ExecuteAsync(context.HttpContext);
});
app.UseStaticFiles();
app.UseRouting();
app.UseAuthentication();
app.Use(async (context, next) =>
{
    if (IsProtectedDeviceEndpoint(context))
    {
        // Named-scheme authorization happens later; authenticate now to partition only verified devices.
        var deviceAuthentication = await context.AuthenticateAsync(DeviceAuthenticationHandler.SchemeName);
        context.User = deviceAuthentication.Succeeded && deviceAuthentication.Principal is not null
            ? deviceAuthentication.Principal
            : new ClaimsPrincipal(new ClaimsIdentity());
    }
    await next();
});
app.UseRateLimiter();
app.UseAuthorization();
app.MapGet("/health/live",()=>Results.Ok(new {status="ok"})).AllowAnonymous();
app.MapGet("/health/ready",async(AppDbContext db,CancellationToken ct)=>{
    try {await db.Database.ExecuteSqlRawAsync("SELECT 1",ct);return Results.Ok(new {status="ready"}) as IResult;}
    catch{return Results.Problem(statusCode:503,title:"Database is unavailable");}
}).AllowAnonymous();
app.MapControllers();app.MapControllerRoute(name:"default",pattern:"{controller=Admin}/{action=Index}/{id?}");
if (migrateOnly && !EF.IsDesignTime)
{
    await using var migrationScope = app.Services.CreateAsyncScope();
    await migrationScope.ServiceProvider.GetRequiredService<AppDbContext>().Database.MigrateAsync();
    return;
}
if(!EF.IsDesignTime && !app.Environment.IsEnvironment("Testing"))
{
    await using var scope=app.Services.CreateAsyncScope();
    var db=scope.ServiceProvider.GetRequiredService<AppDbContext>();
    if(builder.Configuration.GetValue("DB_MIGRATE",false))await db.Database.MigrateAsync();
    await AdminSeeder.SeedAsync(scope.ServiceProvider,builder.Configuration,builder.Environment);
}
app.Run();

static bool IsProtectedDeviceEndpoint(HttpContext context)
{
    var endpoint = context.GetEndpoint();
    return context.Request.Path.StartsWithSegments("/api/v1")
        && endpoint?.Metadata.GetMetadata<IAuthorizeData>() is not null
        && endpoint.Metadata.GetMetadata<IAllowAnonymous>() is null;
}

static string RateLimitIdentity(HttpContext context)
{
    var ipPartition = "ip:" + (context.Connection.RemoteIpAddress?.ToString() ?? "unknown");
    var endpoint = context.GetEndpoint();
    if (endpoint?.Metadata.GetMetadata<IAllowAnonymous>() is not null)
        return ipPartition;
    var identity = context.User.Identity;
    var userId = context.User.FindFirstValue(ClaimTypes.NameIdentifier);
    if (identity?.IsAuthenticated != true || string.IsNullOrWhiteSpace(userId))
        return ipPartition;
    if (IsProtectedDeviceEndpoint(context) && identity.AuthenticationType == DeviceAuthenticationHandler.SchemeName)
        return "device:" + userId;
    if (!context.Request.Path.StartsWithSegments("/api")
        && endpoint?.Metadata.GetMetadata<IAuthorizeData>() is not null
        && identity.AuthenticationType == IdentityConstants.ApplicationScheme)
        return "admin:" + userId;
    return ipPartition;
}

public partial class Program { }
