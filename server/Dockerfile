# Build from server/: docker build .
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY MegaStream.Server/MegaStream.Server.csproj MegaStream.Server/
RUN dotnet restore MegaStream.Server/MegaStream.Server.csproj
COPY MegaStream.Server/ MegaStream.Server/
RUN dotnet publish MegaStream.Server/MegaStream.Server.csproj \
    --configuration Release --no-restore --output /app/publish /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/megastream/keys /var/lib/megastream/apks \
    && chown "$APP_UID:$APP_UID" /var/lib/megastream/keys /var/lib/megastream/apks
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
USER $APP_UID
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --location --max-redirs 0 --header 'Host: localhost' http://127.0.0.1:8080/health/ready || exit 1
ENTRYPOINT ["dotnet", "MegaStream.Server.dll"]
