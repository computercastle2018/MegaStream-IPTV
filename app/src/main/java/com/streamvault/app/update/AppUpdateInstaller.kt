package com.MegaStream.app.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.MegaStream.app.BuildConfig
import com.MegaStream.data.preferences.PreferencesRepository
import com.MegaStream.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateDownloadState(
    val status: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val versionName: String? = null,
    val downloadId: Long? = null,
    val release: GitHubReleaseInfo? = null
)

enum class AppUpdateDownloadStatus { Idle, Downloading, Downloaded, Failed }

@Singleton
class AppUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val active = context.getSharedPreferences("active_app_update", Context.MODE_PRIVATE)
    private val metadataStore = UpdateMetadataStore(context)
    private val json = Json { encodeDefaults = true }
    private val backupClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES).build()
    private val _downloadState = MutableStateFlow(AppUpdateDownloadState())
    val downloadState: StateFlow<AppUpdateDownloadState> = _downloadState.asStateFlow()
    private var backupJob: Job? = null
    private val managedCallbacks = mutableMapOf<BroadcastReceiver, PendingIntent>()
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == _downloadState.value.downloadId
            ) scope.launch { refreshState() }
        }
    }

    init {
        registerReceiver(downloadCompleteReceiver, DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        scope.launch {
            refreshState()
            while (isActive) {
                delay(1500)
                if (_downloadState.value.status == AppUpdateDownloadStatus.Downloading &&
                    _downloadState.value.downloadId != null
                ) refreshState()
            }
        }
    }

    suspend fun refreshState(): AppUpdateDownloadState = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { refreshLocked() } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                publish(AppUpdateDownloadStatus.Failed, readRelease())
            }
        }
    }

    /** Private preferences, not legacy version-name preferences, are the authority for restoration. */
    private suspend fun refreshLocked(): AppUpdateDownloadState {
        ensureDurableState()
        val release = readRelease() ?: return publish(AppUpdateDownloadStatus.Idle)
        val persisted = metadataStore.find(release)
        if (persisted == null || !hasRequiredMetadata(persisted) ||
            (BackupUpdateManifest.isTrustedUrl(persisted.downloadUrl.orEmpty()) && active.contains("download_id"))
        ) {
            stopDownloadLocked()
            persistPhase("failed")
            return publish(AppUpdateDownloadStatus.Failed, release)
        }
        if (!isNewer(persisted)) {
            stopDownloadLocked()
            commitActive(active.edit().clear())
            preferencesRepository.setAppUpdateDownloadId(null)
            preferencesRepository.setAppUpdateDownloadVersionName(null)
            preferencesRepository.setDownloadedAppUpdateVersionName(null)
            return publish(AppUpdateDownloadStatus.Idle)
        }
        val target = apkFileForVersion(release.versionName)
        return when (active.getString("phase", null)) {
            "complete" -> if (target.isFile && target.length() > 0) {
                publish(AppUpdateDownloadStatus.Downloaded, persisted)
            } else {
                persistPhase("failed")
                publish(AppUpdateDownloadStatus.Failed, persisted)
            }
            "downloading" -> {
                val id = active.getLong("download_id", -1).takeIf { it >= 0 }
                if (id == null) {
                    // An interrupted process never promotes a partial backup download to completion.
                    if (backupJob?.isActive == true) publish(AppUpdateDownloadStatus.Downloading, persisted)
                    else {
                        partialFile(release, active.getString("token", "").orEmpty()).delete()
                        persistPhase("failed")
                        publish(AppUpdateDownloadStatus.Failed, persisted)
                    }
                } else {
                    val status = downloadManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        } else DownloadManager.STATUS_FAILED
                    }
                    when (status) {
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING ->
                            publish(AppUpdateDownloadStatus.Downloading, persisted, id)
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val partial = partialFile(release, active.getString("token", "").orEmpty())
                            completeLocked(persisted, partial)
                        }
                        else -> {
                            persistPhase("failed")
                            publish(AppUpdateDownloadStatus.Failed, persisted)
                        }
                    }
                }
            }
            else -> publish(AppUpdateDownloadStatus.Failed, persisted)
        }
    }

    suspend fun startDownload(releaseInfo: GitHubReleaseInfo): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            var started = false
            try {
                ensureDurableState()
                // Merge exact-identity verification metadata before checking it; never look up by name.
                try { metadataStore.save(releaseInfo) } catch (error: IOException) {
                    persistencePoisoned = true
                    throw error
                }
                val release = metadataStore.find(releaseInfo) ?: throw IOException("Missing update metadata")
                require(hasRequiredMetadata(release)) { "Release verification metadata is missing or invalid" }
                require(isNewer(release)) { "You already have this MegaStream version or a newer one" }
                val url = release.downloadUrl ?: throw IllegalArgumentException("Update download is unavailable")
                require(isHttpsUrl(url)) { "Update download URL must use HTTPS" }
                val controlled = BackupUpdateManifest.isTrustedUrl(url)
                require(release.source != AppUpdateSource.Backup || controlled) { "Untrusted backup download URL" }
                val existing = refreshLocked()
                if (existing.release == release && existing.status in setOf(
                        AppUpdateDownloadStatus.Downloading, AppUpdateDownloadStatus.Downloaded
                    )) return@withLock Result.success(Unit)
                stopDownloadLocked()
                started = true
                val token = UUID.randomUUID().toString()
                val target = apkFileForVersion(release.versionName)
                check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                    "External Downloads directory is unavailable"
                }
                val partial = partialFile(release, token)
                // Full identity and a distinct incomplete phase are durable BEFORE any network download.
                commitActive(active.edit().clear().putString("release", json.encodeToString(release))
                    .putString("token", token).putString("phase", "downloading"))
                preferencesRepository.setDownloadedAppUpdateVersionName(null)
                preferencesRepository.setAppUpdateDownloadVersionName(release.versionName)
                preferencesRepository.setAppUpdateDownloadId(null)
                if (controlled) {
                    publish(AppUpdateDownloadStatus.Downloading, release)
                    backupJob = scope.launch { downloadBackup(release, token, partial) }
                } else {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setTitle("MegaStream ${release.versionName}")
                        .setDescription("Downloading the latest MegaStream update")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setMimeType(APK_MIME).setAllowedOverMetered(true).setAllowedOverRoaming(true)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, partial.name)
                    val id = downloadManager.enqueue(request)
                    try { commitActive(active.edit().putLong("download_id", id)) }
                    catch (error: IOException) {
                        downloadManager.remove(id)
                        throw error
                    }
                    preferencesRepository.setAppUpdateDownloadId(id)
                    publish(AppUpdateDownloadStatus.Downloading, release, id)
                }
                Result.success(Unit)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (started) {
                    runCatching { stopDownloadLocked() }
                    runCatching { persistPhase("failed") }
                    publish(AppUpdateDownloadStatus.Failed, readRelease())
                }
                // Do not attach a network exception to the public result (URLs may contain secrets).
                Result.error(error.message?.takeIf { error is IllegalArgumentException }
                    ?: "Failed to start update download")
            }
        }
    }

    private suspend fun downloadBackup(release: GitHubReleaseInfo, token: String, partial: File) {
        try {
            val url = release.downloadUrl ?: throw IOException("Missing download URL")
            require(BackupUpdateManifest.isTrustedUrl(url))
            backupClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                // Reject every redirect, including same-host redirects, rather than delegate trust to OkHttp.
                if (!response.isSuccessful) throw IOException("Backup download was rejected")
                val body = response.body ?: throw IOException("Empty backup response")
                val advertised = body.contentLength()
                if (advertised > MAX_APK_BYTES ||
                    (advertised >= 0 && release.sizeBytes != null && advertised != release.sizeBytes)
                ) throw IOException("Unexpected update size")
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES || (release.sizeBytes != null && total > release.sizeBytes)) {
                                throw IOException("Update exceeds expected size")
                            }
                            output.write(buffer, 0, count)
                        }
                        if (total == 0L || (release.sizeBytes != null && total != release.sizeBytes)) {
                            throw IOException("Incomplete update download")
                        }
                        output.fd.sync()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                if (active.getString("token", null) == token && matchesIdentity(readRelease(), release)) {
                    completeLocked(release, partial)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            mutex.withLock {
                if (active.getString("token", null) == token) {
                    runCatching { persistPhase("failed") }
                    publish(AppUpdateDownloadStatus.Failed, release)
                }
            }
        } finally { partial.delete() }
    }

    private suspend fun completeLocked(release: GitHubReleaseInfo, partial: File): AppUpdateDownloadState {
        if (!partial.isFile || partial.length() <= 0 || partial.length() > MAX_APK_BYTES ||
            (release.sizeBytes != null && partial.length() != release.sizeBytes) ||
            !computeSha256Hex(partial).equals(release.sha256, ignoreCase = true)
        ) {
            partial.delete()
            persistPhase("failed")
            return publish(AppUpdateDownloadStatus.Failed, release)
        }
        val target = apkFileForVersion(release.versionName)
        if (target.exists() && !target.delete()) throw IOException("Could not replace update file")
        if (!partial.renameTo(target)) throw IOException("Could not finalize update file")
        // A file's mere existence is never evidence that a download completed.
        persistPhase("complete")
        preferencesRepository.setAppUpdateDownloadId(null)
        preferencesRepository.setAppUpdateDownloadVersionName(null)
        preferencesRepository.setDownloadedAppUpdateVersionName(release.versionName)
        return publish(AppUpdateDownloadStatus.Downloaded, release)
    }

    suspend fun installDownloadedUpdate(
        expectedSha256: String? = null,
        preferManaged: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val state = refreshLocked()
                val release = state.release
                if (state.status != AppUpdateDownloadStatus.Downloaded || release == null) {
                    return@withLock Result.error("No downloaded update is ready to install")
                }
                val file = apkFileForVersion(release.versionName)
                validationFailure(release, file, expectedSha256)?.let { return@withLock Result.error(it) }
                if (preferManaged && tryManagedInstall(release, file)) Result.success(Unit)
                else {
                    // A denied/failed managed submission may have taken time; never reuse stale verification.
                    validationFailure(release, file, expectedSha256)?.let { return@withLock Result.error(it) }
                    promptInstall(file)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { Result.error("The downloaded update could not be verified or installed") }
        }
    }

    @Suppress("DEPRECATION")
    private fun validationFailure(release: GitHubReleaseInfo, file: File, argumentHash: String?): String? {
        ensureDurableState()
        val persisted = metadataStore.find(release) ?: return "Persisted update metadata is missing"
        if (!file.isFile) return "Downloaded update file is missing"
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
        return ApkInstallValidation.failure(ApkInstallValidation.Evidence(
            expectedPackage = BuildConfig.APPLICATION_ID,
            metadataPackage = persisted.packageName,
            candidatePackage = candidate?.packageName,
            expectedVersionCode = persisted.versionCode,
            candidateVersionCode = candidate?.let(::versionCode),
            installedVersionCode = versionCode(installed),
            persistedSha256 = persisted.sha256,
            actualSha256 = computeSha256Hex(file),
            argumentSha256 = argumentHash,
            installedCertificates = signatureSha256Set(installed),
            candidateCertificates = signatureSha256Set(candidate),
            expectedCertificate = persisted.signingCertificateSha256
        ))
    }

    /** Success means the system prompt was opened, never that installation completed. */
    private fun promptInstall(file: File): Result<Unit> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.error("Allow installs from this app, then try Install update again")
        } else {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Result.success(Unit)
        }
    } catch (_: Exception) { Result.error("The package installer could not be launched") }

    /** Only device owners may request managed installation; all other callers get the prompt. */
    private fun tryManagedInstall(release: GitHubReleaseInfo, file: File): Boolean {
        val owner = runCatching {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                ?.isDeviceOwnerApp(BuildConfig.APPLICATION_ID) == true
        }.getOrDefault(false)
        if (!owner) return false
        val installer = runCatching { context.packageManager.packageInstaller }.getOrNull() ?: return false
        var sessionId: Int? = null
        var callback: BroadcastReceiver? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(BuildConfig.APPLICATION_ID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setSize(file.length())
            }
            val id = installer.createSession(params)
            sessionId = id
            val token = active.getString("token", null)
            val action = "${BuildConfig.APPLICATION_ID}.UPDATE_INSTALL.${UUID.randomUUID()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != action || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    cleanupManagedCallback(this)
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        runCatching { installer.abandonSession(id) }
                        // Ignore supplied pending-user-action intents; revalidate this exact release and use our prompt.
                        scope.launch {
                            mutex.withLock {
                                if (active.getString("token", null) == token && matchesIdentity(readRelease(), release)) {
                                    val fallback = runCatching {
                                        if (validationFailure(release, file, null) == null) promptInstall(file)
                                        else Result.error("Update verification failed")
                                    }.getOrElse { Result.error("The package installer could not be launched") }
                                    if (fallback is Result.Error) {
                                        runCatching { persistPhase("failed") }
                                        publish(AppUpdateDownloadStatus.Failed, release)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            callback = receiver
            registerReceiver(receiver, action)
            val pending = PendingIntent.getBroadcast(context, id,
                Intent(action).setPackage(BuildConfig.APPLICATION_ID),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0))
            synchronized(managedCallbacks) { managedCallbacks[receiver] = pending }
            installer.openSession(id).use { session ->
                val copiedDigest = MessageDigest.getInstance("SHA-256")
                var copiedBytes = 0L
                session.openWrite("update.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copiedBytes += count
                            if (copiedBytes > MAX_APK_BYTES) throw IOException("Update exceeds size limit")
                            copiedDigest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    session.fsync(output)
                }
                // Verify exactly the bytes submitted to PackageInstaller, not an earlier external-file snapshot.
                val persisted = metadataStore.find(release) ?: throw IOException("Missing verification metadata")
                ensureDurableState()
                if (!copiedDigest.digest().toHex().equals(persisted.sha256, ignoreCase = true) ||
                    copiedBytes == 0L || (persisted.sizeBytes != null && copiedBytes != persisted.sizeBytes)
                ) throw IOException("Managed update integrity verification failed")
                session.commit(pending.intentSender)
            }
            // Submission only. No Installed state is emitted here (or by the callback).
            return true
        } catch (_: Exception) {
            callback?.let(::cleanupManagedCallback)
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            return false
        }
    }

    private fun cleanupManagedCallback(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
        synchronized(managedCallbacks) { managedCallbacks.remove(receiver) }?.cancel()
    }

    private fun hasRequiredMetadata(release: GitHubReleaseInfo): Boolean =
        isHttpsUrl(release.downloadUrl.orEmpty()) &&
            (release.source != AppUpdateSource.Backup || BackupUpdateManifest.isTrustedUrl(release.downloadUrl.orEmpty())) &&
            release.packageName == BuildConfig.APPLICATION_ID && (release.versionCode ?: 0) > 0 &&
            ApkInstallValidation.isValidDigest(release.sha256) &&
            (release.signingCertificateSha256 == null || ApkInstallValidation.isValidDigest(release.signingCertificateSha256)) &&
            (release.minSdk == null || release.minSdk in 1..Build.VERSION.SDK_INT) &&
            (release.sizeBytes == null || release.sizeBytes in 1..MAX_APK_BYTES)

    @Suppress("DEPRECATION")
    private fun isNewer(release: GitHubReleaseInfo): Boolean = release.versionCode?.toLong()?.let {
        it > versionCode(context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0))
    } ?: false

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatureSha256Set(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.toSet()
    }

    private fun matchesIdentity(first: GitHubReleaseInfo?, second: GitHubReleaseInfo): Boolean =
        first != null && first.versionName == second.versionName && first.versionCode == second.versionCode &&
            first.downloadUrl == second.downloadUrl

    private fun readRelease(): GitHubReleaseInfo? = active.getString("release", null)?.let {
        runCatching { json.decodeFromString<GitHubReleaseInfo>(it) }.getOrNull()
    }

    private fun persistPhase(phase: String) {
        commitActive(active.edit().putString("phase", phase).remove("download_id"))
    }

    private fun ensureDurableState() {
        if (persistencePoisoned) throw IOException("Update persistence failed; restart the app before retrying")
    }

    private fun commitActive(editor: SharedPreferences.Editor) {
        ensureDurableState()
        if (!editor.commit()) {
            // SharedPreferences updates memory even when its disk commit fails.
            persistencePoisoned = true
            throw IOException("Could not persist active update state")
        }
    }

    private fun stopDownloadLocked() {
        backupJob?.cancel()
        backupJob = null
        active.getLong("download_id", -1).takeIf { it >= 0 }?.let { downloadManager.remove(it) }
    }

    private fun publish(status: AppUpdateDownloadStatus, release: GitHubReleaseInfo? = null, id: Long? = null): AppUpdateDownloadState =
        AppUpdateDownloadState(status, release?.versionName, id, release).also { _downloadState.value = it }

    private fun isHttpsUrl(url: String): Boolean = runCatching {
        val parsed = URI(url)
        parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null
    }.getOrDefault(false)

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun apkFileForVersion(versionName: String): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("External Downloads directory is unavailable")
        val safeVersion = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(downloads, "MegaStream-$safeVersion.apk")
    }

    private fun partialFile(release: GitHubReleaseInfo, token: String): File =
        File(apkFileForVersion(release.versionName).parentFile, "update-$token.part")

    fun downloadedApkPath(): String? {
        val state = _downloadState.value
        if (state.status != AppUpdateDownloadStatus.Downloaded) return null
        return state.release?.let { release ->
            runCatching { apkFileForVersion(release.versionName).takeIf { it.isFile }?.absolutePath }.getOrNull()
        }
    }

    private fun registerReceiver(receiver: BroadcastReceiver, action: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
    }

    fun unregister() {
        scope.cancel()
        runCatching { context.unregisterReceiver(downloadCompleteReceiver) }
        val receivers = synchronized(managedCallbacks) { managedCallbacks.keys.toList() }
        receivers.forEach(::cleanupManagedCallback)
    }

    private companion object {
        @Volatile var persistencePoisoned = false
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
