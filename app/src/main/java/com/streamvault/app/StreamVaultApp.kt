package com.MegaStream.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.MegaStream.app.diagnostics.CrashReportStore
import com.MegaStream.app.diagnostics.RuntimeDiagnosticsManager
import com.MegaStream.app.update.GitHubReleaseChecker
import com.MegaStream.app.update.isRemoteAppVersionNewer
import com.MegaStream.app.ui.accessibility.isReducedMotionEnabled
import com.MegaStream.data.preferences.PreferencesRepository
import com.MegaStream.domain.model.Result
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.MegaStream.data.manager.recording.RecordingReconcileWorker
import com.MegaStream.data.sync.ProviderSyncWorker
import com.MegaStream.data.sync.XtreamIndexWorker
import com.MegaStream.player.timeshift.TimeshiftDiskManager
import javax.inject.Inject

@HiltAndroidApp
class MegaStreamApp : Application(), SingletonImageLoader.Factory {
    private val runtimeDiagnosticsManager by lazy { RuntimeDiagnosticsManager(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var gitHubReleaseChecker: GitHubReleaseChecker

    override fun onCreate() {
        super.onCreate()
        // Crash reporting must install before anything else can throw.
        CrashReportStore.install(this)
        runtimeDiagnosticsManager.start()

        // Everything below is non-essential for first-frame rendering and is
        // deferred off the main thread to keep cold-start TTI low. The earlier
        // implementation built WorkManager Constraints + enqueued five periodic
        // jobs synchronously inside onCreate, which delayed first frame by tens
        // of milliseconds on cold boots and disk-stalled launches.
        applicationScope.launch {
            // Clean up any timeshift temp directories left behind by crashes,
            // OOM kills, or force-stops from the previous run.
            TimeshiftDiskManager(applicationContext).cleanupStaleDirectories(activeSessionDir = null)
        }
        applicationScope.launch {
            refreshCachedAppUpdateIfNeeded()
        }
        applicationScope.launch {
            scheduleBackgroundWork()
        }
    }

    /** Enqueues periodic + one-shot workers off the main thread. Idempotent. */
    private fun scheduleBackgroundWork() {
        // Daily data maintenance: EPG pruning, stale-favorite cleanup, DB compaction.
        // BLD-H02: Require network + device idle so the worker doesn't drain battery.
        val gcConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val gcWorkRequest = PeriodicWorkRequestBuilder<com.MegaStream.data.sync.SyncWorker>(24, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(gcConstraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DataMaintenanceWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            gcWorkRequest
        )

        ProviderSyncWorker.enqueuePeriodic(this)
        ProviderSyncWorker.enqueueLaunchStaleCheck(this)
        XtreamIndexWorker.enqueuePeriodic(this)
        XtreamIndexWorker.enqueueLaunchStaleCheck(this)
        RecordingReconcileWorker.enqueuePeriodic(this)
        RecordingReconcileWorker.enqueueOneShot(this)
    }

    override fun onTerminate() {
        runtimeDiagnosticsManager.stop()
        super.onTerminate()
    }

    private suspend fun refreshCachedAppUpdateIfNeeded() {
        val autoCheckEnabled = preferencesRepository.autoCheckAppUpdates.first()
        if (!autoCheckEnabled) {
            return
        }

        val lastCheckedAt = preferencesRepository.lastAppUpdateCheckTimestamp.first()
        val now = System.currentTimeMillis()
        val checkIntervalMs = 24L * 60L * 60L * 1000L
        if (lastCheckedAt != null && now - lastCheckedAt < checkIntervalMs) {
            return
        }

        preferencesRepository.setLastAppUpdateCheckTimestamp(now)
        when (val result = gitHubReleaseChecker.fetchLatestRelease()) {
            is Result.Success -> {
                val release = result.data
                if (isRemoteAppVersionNewer(release.versionCode, release.versionName, release.publishedAt)) {
                    preferencesRepository.setCachedAppUpdateRelease(
                        versionName = release.versionName,
                        versionCode = release.versionCode,
                        releaseUrl = release.releaseUrl,
                        downloadUrl = release.downloadUrl,
                        releaseNotes = release.releaseNotes,
                        publishedAt = release.publishedAt
                    )
                } else {
                    preferencesRepository.setCachedAppUpdateRelease(
                        versionName = null,
                        versionCode = null,
                        releaseUrl = null,
                        downloadUrl = null,
                        releaseNotes = "",
                        publishedAt = null
                    )
                }
            }
            else -> Unit
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15) // Conservative TV memory cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(1024L * 1024L * 100L) // 100MB disk cache
                    .build()
            }
            // Limit concurrent decoding and fetching to 6 for TV hardware constraints
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(6))
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))
            .crossfade(!isReducedMotionEnabled(context))
            .build()
    }
}
