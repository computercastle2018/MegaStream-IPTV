package com.MegaStream.app.controlplane.integration

import android.app.ActivityManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.MegaStream.app.BuildConfig
import com.MegaStream.app.controlplane.ControlPlaneClient
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.DeviceAbi
import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.controlplane.LocalSubscriptionsUploader
import com.MegaStream.app.controlplane.ReleaseChannel
import com.MegaStream.app.controlplane.runtime.ControlPlaneRuntimeClientAdapter
import com.MegaStream.app.controlplane.runtime.DiagnosticsTransport
import com.MegaStream.app.controlplane.runtime.InstallationScopedAppEntitlement
import com.MegaStream.app.controlplane.runtime.RuntimeCoordinator
import com.MegaStream.app.controlplane.runtime.RuntimeDiagnosticsUploader
import com.MegaStream.app.controlplane.runtime.RuntimeMetadata
import com.MegaStream.app.controlplane.runtime.RuntimeProviderAssignmentSink
import com.MegaStream.app.controlplane.runtime.RuntimeUpdateCommandSink
import com.MegaStream.app.diagnostics.runtime.AndroidSessionRecoveryFactory
import com.MegaStream.data.diagnostics.FileDiagnosticsOutbox
import com.MegaStream.data.licensing.LocalAppEntitlement
import com.MegaStream.domain.diagnostics.DiagnosticsEventSink
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Explicit capability states: absence of a sink can never masquerade as successful delivery. */
enum class RuntimeCapabilityStatus { NOT_CONFIGURED }
enum class RuntimeCapabilityBlocker {
    UPDATE_LEDGER_NOT_INSTALLATION_SCOPED_OR_CONFLICT_CHECKED,
    PROVIDER_ATOMIC_ASSIGNMENT_ADAPTER_MISSING,
}
data class RuntimeCapability(val status: RuntimeCapabilityStatus, val blocker: RuntimeCapabilityBlocker) {
    override fun toString(): String = "RuntimeCapability([REDACTED])"
}
internal class RuntimeCapabilityUnavailable(val blocker: RuntimeCapabilityBlocker) :
    IllegalStateException("Runtime capability not configured")

internal class AndroidRuntimeFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: Provider<InstallationCredentials>,
    private val entitlement: Provider<LocalAppEntitlement>,
    private val providers: Provider<com.MegaStream.domain.repository.ProviderRepository>,
) {
    /** Resolve all blocking providers and construct all durable stores on the IO dispatcher. */
    fun create(scope: CoroutineScope): RuntimeController {
        check(isMainRuntimeProcess(context)) { "Main process required" }
        val identity = credentials.get()
        val localEntitlement = entitlement.get()
        val directory = File(context.noBackupFilesDir, "control-plane-v1/${identity.installationId}")
        check(directory.isDirectory || directory.mkdirs()) { "Runtime storage unavailable" }
        syncRuntimeDirectory(directory.parentFile!!)
        syncRuntimeDirectory(context.noBackupFilesDir)
        val stores = DurableRuntimeStores(directory, directorySync = ::syncRuntimeDirectory)
        val outbox = FileDiagnosticsOutbox(File(directory, "diagnostics-outbox-v1.bin"))
        val quarantine = DurableDiagnosticsQuarantine(directory, outbox, directorySync = ::syncRuntimeDirectory)
        val recorder = DiagnosticsRecorder(DiagnosticsEventSink(outbox::append))
        val collector = AndroidSessionRecoveryFactory.create(context, recorder)
        val client = ControlPlaneClient()
        val coordinator = RuntimeCoordinator(
            identity, ControlPlaneRuntimeClientAdapter(client), stores, stores,
            InstallationScopedAppEntitlement(identity.installationId, localEntitlement),
            RuntimeUpdateCommandSink { _, _ ->
                // Existing RemoteUpdateCommandProcessor's ledger retains URLs, has no installation
                // binding or conflicting-payload check, and cannot meet the runtime port contract.
                throw RuntimeCapabilityUnavailable(RuntimeCapabilityBlocker.UPDATE_LEDGER_NOT_INSTALLATION_SCOPED_OR_CONFLICT_CHECKED)
            },
            RuntimeProviderAssignmentSink { _, _ ->
                throw RuntimeCapabilityUnavailable(RuntimeCapabilityBlocker.PROVIDER_ATOMIC_ASSIGNMENT_ADAPTER_MISSING)
            },
        )
        val uploader = RuntimeDiagnosticsUploader(outbox, quarantine, DiagnosticsTransport(client::diagnostics), identity)
        return RuntimeController(
            ProductionRuntimeOperations(identity.installationId, coordinator, stores, localEntitlement, ::metadata,
                uploadLocalSubscriptions = {
                    if (LocalSubscriptionsUploader(providers.get(), client, identity).upload() is ControlPlaneResult.Failure)
                        java.util.logging.Logger.getLogger("MegaStream").warning("Local subscription report rejected; retrying on next heartbeat")
                }),
            ProductionRuntimeDiagnostics(collector, recorder, uploader, quarantine,
                BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME),
            scope, Dispatchers.IO, SystemClock::elapsedRealtime,
        )
    }

    private fun metadata(): RuntimeMetadata = RuntimeMetadata(
        BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME, Build.MANUFACTURER, Build.MODEL,
        Build.VERSION.SDK_INT, Build.VERSION.RELEASE,
        when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> DeviceAbi.ARM64_V8A
            "armeabi-v7a" -> DeviceAbi.ARMEABI_V7A
            "x86_64" -> DeviceAbi.X86_64
            "x86" -> DeviceAbi.X86
            else -> DeviceAbi.OTHER
        },
        Locale.getDefault().toLanguageTag(),
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
            ?.isDeviceOwnerApp(context.packageName) == true,
        context.packageName,
        if (BuildConfig.APP_UPDATE_CHANNEL == "beta") ReleaseChannel.BETA else ReleaseChannel.STABLE,
    )
}

internal fun isMainRuntimeProcess(context: Context): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }
    return processName == context.packageName
}

private fun syncRuntimeDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
