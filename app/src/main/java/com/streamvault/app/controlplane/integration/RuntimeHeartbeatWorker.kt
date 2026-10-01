package com.MegaStream.app.controlplane.integration

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** WorkManager reflection constructor; resolves the SAME application singleton, not a second graph. */
class RuntimeHeartbeatWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (!isMainRuntimeProcess(applicationContext)) Result.failure() else {
            val runtime = EntryPointAccessors.fromApplication(
                applicationContext, RuntimeWorkerEntryPoint::class.java,
            ).runtime()
            // Periodic work owns retries. Never revive a failed store/uploader within this process.
            if (runtime.backgroundTick()) Result.success() else Result.failure()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RuntimeWorkerEntryPoint {
    fun runtime(): ProductionRuntime
}
