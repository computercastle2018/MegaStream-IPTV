package com.MegaStream.app.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteUpdateCommandInstallerAdapter(private val installer: AppUpdateInstaller) : RemoteUpdateCommandDownloader {
    override val downloadState: StateFlow<AppUpdateDownloadState> get() = installer.downloadState
    override suspend fun startDownload(release: GitHubReleaseInfo): Result<Unit> = installer.startDownload(release)
    override suspend fun refreshState(): AppUpdateDownloadState = installer.refreshState()
    override suspend fun installDownloadedUpdate(expectedSha256: String?, preferManaged: Boolean): Result<Unit> =
        installer.installDownloadedUpdate(expectedSha256, preferManaged)
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

/** Single-process local ledger. Use the same dedicated preferences file for all instances.
 * Records are intentionally retained: pruning IDs would permit replay. commit(), not apply(),
 * establishes the durability barrier. A failed commit poisons writes until process restart,
 * because SharedPreferences can mutate its memory cache even when disk persistence fails.
 * SharedPreferences is not a cross-process transactional store.
 */
class RemoteUpdateCommandSharedPreferencesStore private constructor(
    private val preferences: SharedPreferences
) : RemoteUpdateCommandStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    override suspend fun claim(record: RemoteUpdateCommandRecord): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            checkHealthy()
            if (read(record.commandId) != null) return@synchronized false
            val occupied = preferences.all.keys.filter { it.startsWith(PREFIX) }.any {
                val existing = decode(requireNotNull(preferences.getString(it, null)))
                existing.status != RemoteUpdateCommandStatus.Installed && existing.status != RemoteUpdateCommandStatus.Failed
            }
            check(!occupied) { "Another update command is active" }
            persist(record)
            true
        }
    }

    override suspend fun get(commandId: String): RemoteUpdateCommandRecord? = withContext(Dispatchers.IO) {
        synchronized(lock) { checkHealthy(); read(commandId) }
    }

    override suspend fun compareAndSet(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                checkHealthy()
                require(previous.commandId == next.commandId)
                if (read(previous.commandId) != previous) return@synchronized false
                persist(next)
                true
            }
        }

    private fun checkHealthy() = check(!poisoned) { "Command persistence failed; refusing further work" }
    private fun read(id: String): RemoteUpdateCommandRecord? = preferences.getString(PREFIX + id, null)?.let(::decode)
    private fun persist(record: RemoteUpdateCommandRecord) {
        try {
            check(preferences.edit().putString(PREFIX + record.commandId, encode(record)).commit()) { "Could not persist command" }
        } catch (error: Exception) {
            poisoned = true
            throw error
        }
    }

    private fun encode(record: RemoteUpdateCommandRecord): String = JSONObject().apply {
        put("id", record.commandId)
        put("versionCode", record.expectedVersionCode ?: JSONObject.NULL)
        put("versionName", record.versionName)
        put("sha256", record.expectedSha256 ?: JSONObject.NULL)
        put("downloadUrl", record.downloadUrl ?: JSONObject.NULL)
        put("downloadIdentified", record.downloadIdentified)
        put("managed", record.preferManaged)
        put("status", record.status.name)
        put("downloadId", record.downloadId ?: JSONObject.NULL)
        put("installAttempted", record.installAttempted)
        put("failure", record.failure?.code ?: JSONObject.NULL)
    }.toString()

    private fun decode(value: String): RemoteUpdateCommandRecord = JSONObject(value).let {
        RemoteUpdateCommandRecord(
            commandId = it.getString("id"),
            expectedVersionCode = if (it.isNull("versionCode")) null else it.getLong("versionCode"),
            versionName = it.getString("versionName"),
            expectedSha256 = if (it.isNull("sha256")) null else it.getString("sha256"),
            downloadUrl = if (it.isNull("downloadUrl")) null else it.getString("downloadUrl"),
            downloadIdentified = it.getBoolean("downloadIdentified"),
            preferManaged = it.getBoolean("managed"),
            status = RemoteUpdateCommandStatus.valueOf(it.getString("status")),
            downloadId = if (it.isNull("downloadId")) null else it.getLong("downloadId"),
            installAttempted = it.getBoolean("installAttempted"),
            failure = if (it.isNull("failure")) null else RemoteUpdateFailureCode.fromCode(it.getString("failure"))
        )
    }

    private companion object {
        const val FILE_NAME = "remote_update_command_ledger"
        const val PREFIX = "command:"
        val lock = Any()
        var poisoned = false
    }
}
