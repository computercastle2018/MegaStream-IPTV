package com.MegaStream.app.update

import android.content.Context
import android.os.Build
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class RemoteUpdateCommandInstallerAdapter(
    private val installer: AppUpdateInstaller,
    private val allowManaged: Boolean = true,
) : RemoteUpdateCommandDownloader {
    override val downloadState: StateFlow<AppUpdateDownloadState> get() = installer.downloadState
    override suspend fun startDownload(release: GitHubReleaseInfo): Result<Unit> = installer.startDownload(release)
    override suspend fun refreshState(): AppUpdateDownloadState = installer.refreshState()
    override suspend fun installDownloadedUpdate(expectedSha256: String?, preferManaged: Boolean): Result<Unit> =
        installer.installDownloadedUpdate(expectedSha256, preferManaged && allowManaged)
}

class RemoteUpdateCommandAndroidVersionObserver(context: Context) : RemoteUpdateCommandInstalledVersionObserver {
    private val context = context.applicationContext
    override suspend fun installedVersionCode(): Long = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
