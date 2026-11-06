# Owned Server Files

These are the complete worker-owned server paths for the webOS licensing,
redemption and device-report import work in this chat. Paths are relative to
the repository root. Copy current contents into the isolated source overlay.

## Production Source

```text
server/MegaStream.Server/Program.cs
server/MegaStream.Server/Services/LeaseSigner.cs
server/MegaStream.Server/V1/V1Service.cs
server/MegaStream.Server/V1/V1Controller.cs
server/MegaStream.Server/Controllers/LocalSubscriptionsController.cs
server/MegaStream.Server/RemoteProviders/Core/Contracts.cs
server/MegaStream.Server/RemoteProviders/Core/Entities.cs
server/MegaStream.Server/RemoteProviders/Core/LocalSubscriptionCredentialsStore.cs
server/MegaStream.Server/RemoteProviders/Core/RemoteProviderService.cs
server/MegaStream.Server/RemoteProviders/Http/DeviceProvidersController.cs
server/MegaStream.Server/RemoteProviders/Http/ProvidersAdminController.cs
server/MegaStream.Server/RemoteProviders/ModelConfiguration.cs
server/MegaStream.Server/RemoteProviders/Views/Assignments.cshtml
server/MegaStream.Server/RemoteProviders/Views/Editor.cshtml
server/MegaStream.Server/RemoteProviders/Views/Index.cshtml
server/MegaStream.Server/Migrations/AppDbContextModelSnapshot.cs
server/MegaStream.Server/Migrations/20261030055417_AddProviderRedemptionCodes.cs
server/MegaStream.Server/Migrations/20261030055417_AddProviderRedemptionCodes.Designer.cs
server/MegaStream.Server/Migrations/20261030060810_AddLocalSubscriptionImportSource.cs
server/MegaStream.Server/Migrations/20261030060810_AddLocalSubscriptionImportSource.Designer.cs
server/MegaStream.Server/Migrations/20261030062227_AddLocalSubscriptionSourceState.cs
server/MegaStream.Server/Migrations/20261030062227_AddLocalSubscriptionSourceState.Designer.cs
server/MegaStream.Server/RemoteProviders/README.md
```

## Tests

```text
server/MegaStream.Server.Tests/LeaseSignerTests.cs
server/MegaStream.Server.Tests/V1/DesktopDeviceTests.cs
server/MegaStream.Server.Tests/V1/WebosCorsTests.cs
server/MegaStream.Server.Tests/RemoteProviders/RemoteProviderDatabase.cs
server/MegaStream.Server.Tests/RemoteProviders/RemoteProviderHttpTests.cs
server/MegaStream.Server.Tests/RemoteProviders/RemoteProviderServiceTests.cs
server/MegaStream.Server.Tests/RemoteProviders/RemoteProviderTestData.cs
server/MegaStream.Server.Tests/RemoteProviders/ProviderCredentialsTests.cs
server/MegaStream.Server.Tests/RemoteProviders/ProviderRedemptionTests.cs
server/MegaStream.Server.Tests/RemoteProviders/ProviderRedemptionHttpTests.cs
server/MegaStream.Server.Tests/RemoteProviders/LocalSubscriptionImportTests.cs
```

This handoff file itself is also worker-owned. Do not include unrelated dirty
files merely because they are under server/. In particular, this worker did NOT
edit `Views/Shared/_Layout.cshtml`, `wwwroot/css/admin.css` or
`wwwroot/js/admin-theme.js`. Preserve them outside this deployment overlay.

## Deployment Boundary

The redemption migration may already be applied; the two import migrations
above remain required. Use the new server build and intended database settings:

```sh
dotnet MegaStream.Server.dll --migrate-only
```

Manual admin route:
`POST /admin/providers/installations/{installationId}/import-local-subscriptions`.
Automatic sync runs after `POST /api/v1/devices/subscriptions` stores credentials.
It requires recent presence, an active license, and a recent report. No device
command, license transfer, deployment, or actual-TV pairing is performed here.
