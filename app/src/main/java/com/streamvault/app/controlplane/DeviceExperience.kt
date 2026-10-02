package com.MegaStream.app.controlplane

import android.content.Context
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
) {
    init {
        require(notifications.size <= 50 && notifications.map { it.id }.distinct().size == notifications.size)
        require(macAddress == null || com.MegaStream.app.ui.screens.settings.normalizeDeviceMacAddress(macAddress) == macAddress)
    }
}

@Serializable
data class DeviceMacReport(val macAddress: String?)

@Singleton
class DeviceExperienceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: Provider<InstallationCredentials>,
) {
    private val preferences = context.getSharedPreferences("device-experience", Context.MODE_PRIVATE)
    private val client = ControlPlaneClient()
    private val lane = Mutex()
    private val mutableState = MutableStateFlow(DeviceExperience(preferences.getBoolean("details", false)))
    val state = mutableState.asStateFlow()
    private val mutableReadIds = MutableStateFlow(preferences.getStringSet("read-notices", emptySet()).orEmpty().toSet())
    val readIds = mutableReadIds.asStateFlow()
    private val mutableInboxOpen = MutableStateFlow(false)
    val inboxOpen = mutableInboxOpen.asStateFlow()
    fun openInbox() { mutableInboxOpen.value = true }
    fun closeInbox() { mutableInboxOpen.value = false }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lane.withLock {
            val credential = try { credentials.get().credential } catch (_: IllegalStateException) { return@withLock false }
            when (val result = client.deviceExperience(credential)) {
                is ControlPlaneResult.Failure -> false
                is ControlPlaneResult.Success -> {
                    if (!preferences.edit().putBoolean("details", result.value.allowSubscriptionDetails).commit()) return@withLock false
                    mutableState.value = result.value
                    com.MegaStream.app.ui.screens.settings.readDeviceMacAddressForReport(context)?.address?.let { mac ->
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
