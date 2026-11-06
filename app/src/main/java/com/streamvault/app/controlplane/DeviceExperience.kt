package com.MegaStream.app.controlplane

import android.content.Context
import android.content.SharedPreferences
import com.MegaStream.app.ui.model.AppUiStyle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.MegaStream.domain.model.isSupportedAdminPlaybackQuality
import com.MegaStream.player.tracks.ManagedPlaybackQualityPreferences

@Serializable
data class DeviceNotice(val id: String, val title: String, val message: String, val createdAt: String) {
    init {
        require(runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false))
        require(title.isNotBlank() && title.length <= 128 && message.isNotBlank() && message.length <= 2000)
        require(runCatching { java.time.Instant.parse(createdAt) }.isSuccess)
    }
}

@Serializable
data class DeviceExperience(
    val allowSubscriptionDetails: Boolean,
    val notifications: List<DeviceNotice> = emptyList(),
    val macAddress: String? = null,
    val uiStyle: String? = null,
    val playbackQuality: String? = null,
) {
    init {
        require(notifications.size <= 50 && notifications.map { it.id }.distinct().size == notifications.size)
        require(macAddress == null || com.MegaStream.app.ui.screens.settings.normalizeDeviceMacAddress(macAddress) == macAddress)
        require(uiStyle == null || AppUiStyle.entries.any { it.storageValue == uiStyle })
        require(playbackQuality == null || isSupportedAdminPlaybackQuality(playbackQuality))
    }

    fun effectiveAppUiStyle(localStyle: String?): AppUiStyle = AppUiStyle.fromStorage(uiStyle ?: localStyle)
}

@Serializable
data class DeviceMacReport(val macAddress: String?)

@Serializable
data class DeviceAppearanceRequest(val uiStyle: String, val expectedUiStyle: String?) {
    init {
        require(AppUiStyle.entries.any { it.storageValue == uiStyle })
        require(expectedUiStyle == null || AppUiStyle.entries.any { it.storageValue == expectedUiStyle })
    }
}

@Singleton
class DeviceExperienceRepository internal constructor(
    private val preferences: SharedPreferences,
    private val credentials: Provider<InstallationCredentials>,
    private val client: ControlPlaneClient,
    private val deviceMac: () -> String?,
) {
    @Inject constructor(@ApplicationContext context: Context, credentials: Provider<InstallationCredentials>) : this(
        context.getSharedPreferences(ManagedPlaybackQualityPreferences.FILE_NAME, Context.MODE_PRIVATE),
        credentials, ControlPlaneClient(),
        { com.MegaStream.app.ui.screens.settings.readDeviceMacAddressForReport(context)?.address },
    )

    private var pendingAppearance = preferences.getString("pending-appearance", null)?.let {
        runCatching { Json.decodeFromString<DeviceAppearanceRequest>(it) }.getOrNull()
    }
    private val lane = Mutex()
    private val mutableState = MutableStateFlow(DeviceExperience(
        allowSubscriptionDetails = preferences.getBoolean("details", false),
        uiStyle = pendingAppearance?.uiStyle ?: preferences.getString("ui-style", null)?.takeIf { stored ->
            AppUiStyle.entries.any { it.storageValue == stored }
        },
        playbackQuality = ManagedPlaybackQualityPreferences.readQuality(preferences)
    ))
    val state = mutableState.asStateFlow()
    private val mutableReadIds = MutableStateFlow(preferences.getStringSet("read-notices", emptySet()).orEmpty().toSet())
    val readIds = mutableReadIds.asStateFlow()
    private val mutableInboxOpen = MutableStateFlow(false)
    val inboxOpen = mutableInboxOpen.asStateFlow()
    fun openInbox() { mutableInboxOpen.value = true }
    fun closeInbox() { mutableInboxOpen.value = false }

    suspend fun queueAppUiStyle(style: AppUiStyle): Boolean = withContext(Dispatchers.IO) {
        lane.withLock {
            val previous = pendingAppearance
            val pending = DeviceAppearanceRequest(style.storageValue,
                if (previous != null) previous.expectedUiStyle else state.value.uiStyle)
            if (!preferences.edit().putString("pending-appearance", Json.encodeToString(pending)).commit())
                return@withLock false
            pendingAppearance = pending
            mutableState.value = state.value.copy(uiStyle = pending.uiStyle)
            true
        }
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lane.withLock {
            val credential = try { credentials.get().credential } catch (_: IllegalStateException) { return@withLock false }
            pendingAppearance?.let { pending ->
                val result = client.setAppearance(credential, pending)
                if (result is ControlPlaneResult.Success ||
                    result is ControlPlaneResult.Failure && result.error.status == 409) {
                    val edit = preferences.edit().remove("pending-appearance")
                    if (result is ControlPlaneResult.Success) edit.putString("ui-style", pending.uiStyle)
                    if (!edit.commit()) return@withLock false
                    pendingAppearance = null
                    mutableState.value = state.value.copy(uiStyle = preferences.getString("ui-style", null))
                }
            }
            when (val result = client.deviceExperience(credential)) {
                is ControlPlaneResult.Failure -> false
                is ControlPlaneResult.Success -> {
                    if (!preferences.edit()
                        .putBoolean("details", result.value.allowSubscriptionDetails)
                        .putString("ui-style", result.value.uiStyle)
                        .putString(ManagedPlaybackQualityPreferences.QUALITY_KEY, result.value.playbackQuality)
                        .commit()) return@withLock false
                    mutableState.value = result.value.copy(uiStyle = pendingAppearance?.uiStyle ?: result.value.uiStyle)
                    deviceMac()?.let { mac ->
                        if (mac != result.value.macAddress) client.reportDeviceMac(credential, DeviceMacReport(mac))
                    }
                    true
                }
            }
        }
    }

    fun markRead(ids: Set<String>) {
        val bounded = (mutableReadIds.value + ids).toList().takeLast(100).toSet()
        if (preferences.edit().putStringSet("read-notices", bounded).commit()) mutableReadIds.value = bounded
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class DeviceExperienceViewModel @Inject constructor(val repository: DeviceExperienceRepository) : androidx.lifecycle.ViewModel() {
    fun refresh() { viewModelScope.launch { repository.refresh() } }
}
