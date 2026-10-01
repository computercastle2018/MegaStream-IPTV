package com.MegaStream.app.diagnostics.runtime

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.MegaStream.domain.diagnostics.DiagnosticsRecorder
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier
import java.io.File

/**
 * Production construction only; no application wiring. Call on Dispatchers.IO once per MAIN process.
 * Usage: create(context, durableRecorder), startSession(), nextMetadata(), then finishSession() only
 * for an intentional terminal exit. Retry recoverPending after false/throw, not by resetting storage.
 * Context.noBackupFilesDir prevents backup/restore from transplanting process/session identities.
 */
object AndroidSessionRecoveryFactory {
    fun create(context: Context, recorder: DiagnosticsRecorder): AndroidExitRecoveryCollector {
        require(Build.VERSION.SDK_INT >= 27)
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = Process.myPid()
        require(localProcessName(activityManager, pid) == app.packageName) { "Main process required" }
        val startedAt = processStartEpochMillis(System.currentTimeMillis(), SystemClock.elapsedRealtime(), Process.getStartElapsedRealtime())
        val store = FileSessionRecoveryStore(File(app.noBackupFilesDir, "diagnostics-session-recovery-v1.bin"), ::syncDirectory)
        val environment = RecoveryEnvironment(RecoveryProcess(pid, startedAt, app.packageName, Build.VERSION.SDK_INT), System::currentTimeMillis)
        return AndroidExitRecoveryCollector(store, recorder, environment, AndroidHistory(activityManager))
    }

    private fun localProcessName(activityManager: ActivityManager, pid: Int): String? =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else activityManager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}

/** Pure checked conversion; a regressed/invalid boot clock must never create a fabricated start. */
internal fun processStartEpochMillis(wallMillis: Long, elapsedMillis: Long, startElapsedMillis: Long): Long {
    require(validTimestamp(wallMillis) && startElapsedMillis >= 0 && elapsedMillis >= startElapsedMillis)
    val age = Math.subtractExact(elapsedMillis, startElapsedMillis)
    return Math.subtractExact(wallMillis, age).also { require(validTimestamp(it)) }
}

private class AndroidHistory(private val activityManager: ActivityManager) : ExitHistory {
    override fun query(packageName: String, priorPid: Int, maxRecords: Int): List<LocalProcessExit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        require(maxRecords in 1..AndroidExitRecoveryCollector.MAX_HISTORY)
        return try {
            queryApi30(packageName, priorPid, maxRecords)
        } catch (_: SecurityException) {
            // OEMs may deny history. Marker evidence remains usable; no exception text is retained.
            emptyList()
        }
    }

    @TargetApi(30)
    private fun queryApi30(packageName: String, priorPid: Int, maxRecords: Int): List<LocalProcessExit> =
        activityManager.getHistoricalProcessExitReasons(packageName, priorPid, maxRecords)
            .take(maxRecords)
            .filter { it.processName == packageName }
            .map { LocalProcessExit(packageName, SessionTerminationClassifier.ExitReasonSnapshot(
                it.pid, it.timestamp, it.reason, it.status,
            )) }
}
