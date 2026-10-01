package com.MegaStream.app.kiosk

enum class KioskMode { OFF, PLAYBACK, ALWAYS }

data class DevicePolicySnapshot(
    val kioskMode: KioskMode,
    val allowLocalExit: Boolean,
)

fun shouldLock(
    policy: DevicePolicySnapshot,
    isManagedDeviceOwner: Boolean,
    appForeground: Boolean,
    playbackActive: Boolean,
    explicitManualExitInProgress: Boolean,
): Boolean = isManagedDeviceOwner && appForeground && !explicitManualExitInProgress &&
    when (policy.kioskMode) {
        KioskMode.OFF -> false
        KioskMode.PLAYBACK -> playbackActive
        KioskMode.ALWAYS -> true
    }

interface KioskPlatform {
    fun isDeviceOwnerApp(): Boolean
    fun isLockTaskPermitted(): Boolean
    fun startLockTask()
    fun stopLockTask()
    fun finishAndRemoveTask()
}

enum class KioskDenialReason { NOT_ALLOWLISTED, LOCAL_EXIT_DISABLED }

enum class KioskOperation {
    CHECK_DEVICE_OWNER,
    CHECK_LOCK_TASK_PERMISSION,
    START_LOCK_TASK,
    STOP_LOCK_TASK,
    FINISH_TASK,
}

enum class KioskFailureReason { SECURITY, ILLEGAL_STATE }

sealed class KioskResult {
    object Applied : KioskResult()
    object Released : KioskResult()
    object Unsupported : KioskResult()
    data class Denied(val reason: KioskDenialReason) : KioskResult()
    data class Failed(val operation: KioskOperation, val reason: KioskFailureReason) : KioskResult()
}
