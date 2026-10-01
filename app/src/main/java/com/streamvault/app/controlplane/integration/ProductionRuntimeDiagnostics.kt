package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.runtime.RuntimeDiagnosticsUploader
import com.MegaStream.app.diagnostics.runtime.AndroidExitRecoveryCollector
import com.MegaStream.domain.diagnostics.DiagnosticEvent
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder

/** Invoked only on the controller's serialized IO lane. Raw crash reports are never an input. */
internal class ProductionRuntimeDiagnostics(
    private val collector: AndroidExitRecoveryCollector,
    private val recorder: DiagnosticsRecorder,
    private val uploader: RuntimeDiagnosticsUploader,
    private val quarantine: DurableDiagnosticsQuarantine,
    private val appVersionCode: Long,
    private val appVersionName: String,
) : RuntimeControllerDiagnostics {
    private var startedSession: String? = null

    override suspend fun recoverPending() {
        check(collector.recoverPending()) { "Session recovery unavailable" }
    }

    override suspend fun startSession(): String {
        startedSession?.let { return it }
        val session = collector.startSession()
        check(session.recoveryComplete) { "Session recovery incomplete" }
        check(recorder.record(DiagnosticEvent.AppStarted(
            collector.nextMetadata(), appVersionCode, appVersionName,
        ))) { "Session start persistence unavailable" }
        return session.appSessionId.toString().also { startedSession = it }
    }

    override suspend fun upload() = quarantine.withUploadIntent { uploader.upload() }

    override suspend fun manualExit() {
        check(collector.finishSession()) { "Session end persistence unavailable" }
    }
}
