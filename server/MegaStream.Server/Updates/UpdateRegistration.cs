using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace MegaStream.Server.Updates;

public static class UpdateRegistration
{
    public static IServiceCollection AddUpdateManagement(this IServiceCollection services, IConfiguration configuration)
    {
        services.AddOptions<UpdateOptions>()
            .Bind(configuration.GetSection("Updates"))
            .Validate(options =>
            {
                try
                {
                    UpdateOptions.Validate(options);
                    return options.MaxUploadBytes is > 0 and <= 209715200;
                }
                catch (ArgumentException)
                {
                    return false;
                }
            }, "Updates requires an HTTPS origin, an absolute private storage path, and MaxUploadBytes between 1 and 209715200.")
            .Validate<IWebHostEnvironment>((options, environment) =>
                string.Equals(environment.EnvironmentName, "Testing", StringComparison.OrdinalIgnoreCase)
                || options.PublicOrigin == "https://megastrem.megastation.uk",
                "Updates:PublicOrigin must be https://megastrem.megastation.uk outside Testing.")
            .Validate<IWebHostEnvironment>((options, environment) => IsOutsideWebRoot(options.StoragePath, environment),
                "Updates:StoragePath must be outside the application's web root.")
            .ValidateOnStart();
        services.AddSingleton<ApkStorage>();
        services.AddScoped<UpdateService>();
        return services;
    }

    private static bool IsOutsideWebRoot(string storagePath, IWebHostEnvironment environment)
    {
        if (string.IsNullOrWhiteSpace(storagePath) || !Path.IsPathFullyQualified(storagePath)) return false;
        try
        {
            var storage = Path.TrimEndingDirectorySeparator(Path.GetFullPath(storagePath));
            var webRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(
                string.IsNullOrWhiteSpace(environment.WebRootPath)
                    ? Path.Combine(environment.ContentRootPath, "wwwroot")
                    : environment.WebRootPath));
            // Conservative case-insensitive comparison also protects case-insensitive volumes.
            var prefix = Path.EndsInDirectorySeparator(webRoot) ? webRoot : webRoot + Path.DirectorySeparatorChar;
            return !storage.Equals(webRoot, StringComparison.OrdinalIgnoreCase)
                && !storage.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
        }
        catch (ArgumentException)
        {
            return false;
        }
    }
}
