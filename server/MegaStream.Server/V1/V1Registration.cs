namespace MegaStream.Server.V1;

public static class V1Registration
{
    public static IServiceCollection AddV1Protocol(this IServiceCollection services)
    {
        services.AddScoped<V1Service>();
        services.AddScoped<V1Diagnostics>();
        return services;
    }
}
