using MegaStream.Server.RemoteProviders.Core;

namespace MegaStream.Server.RemoteProviders;

public static class RemoteProviderRegistration
{
    public static IServiceCollection AddRemoteProviders(this IServiceCollection services, IConfiguration configuration)
    {
        // Eager validation deliberately applies in every environment, not just on first request.
        var cipher = new ProviderPayloadCipher(configuration);
        services.AddSingleton<ProviderPayloadCipher>(_ => cipher);
        services.AddScoped<IRemoteProviderService, RemoteProviderService>();
        return services;
    }
}
