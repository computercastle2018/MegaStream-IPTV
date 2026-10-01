package com.MegaStream.app.kiosk

/**
 * Serializes transitions and tracks only this instance's successful lock requests.
 * It cannot discover or release locks owned by another controller or recover system lock state.
 * Android callers must invoke transitions on the UI thread.
 */
class KioskControllerEngine(private val platform: KioskPlatform) {
    @Volatile
    var lastResult: KioskResult = KioskResult.Released
        private set

    @Volatile
    var lockRequested: Boolean = false
        private set

    @Volatile
    var manualExitCompleted: Boolean = false
        private set

    private var manualExitInProgress: Boolean = false

    @Synchronized
    fun update(
        policy: DevicePolicySnapshot,
        appForeground: Boolean,
        playbackActive: Boolean,
        explicitManualExitInProgress: Boolean = false,
    ): KioskResult {
        // Evaluate policy first: releasing must not depend on device-policy queries.
        if (!shouldLock(
                policy = policy,
                isManagedDeviceOwner = true,
                appForeground = appForeground,
                playbackActive = playbackActive,
                explicitManualExitInProgress = explicitManualExitInProgress ||
                    manualExitInProgress || manualExitCompleted,
            )
        ) {
            return record(stopOwnedLock() ?: KioskResult.Released)
        }

        var managed = false
        perform(KioskOperation.CHECK_DEVICE_OWNER) {
            managed = platform.isDeviceOwnerApp()
        }?.let { return record(it) }
        if (!managed) {
            return record(stopOwnedLock() ?: KioskResult.Unsupported)
        }

        var permitted = false
        perform(KioskOperation.CHECK_LOCK_TASK_PERMISSION) {
            permitted = platform.isLockTaskPermitted()
        }?.let { return record(it) }
        if (!permitted) {
            return record(
                stopOwnedLock() ?: KioskResult.Denied(KioskDenialReason.NOT_ALLOWLISTED),
            )
        }

        if (!lockRequested) {
            perform(KioskOperation.START_LOCK_TASK) {
                platform.startLockTask()
            }?.let { return record(it) }
            lockRequested = true
        }
        return record(KioskResult.Applied)
    }

    @Synchronized
    fun requestManualExit(policy: DevicePolicySnapshot): KioskResult {
        if (!policy.allowLocalExit) {
            return record(KioskResult.Denied(KioskDenialReason.LOCAL_EXIT_DISABLED))
        }
        if (manualExitCompleted) return record(KioskResult.Released)

        // Retained on failure: only another explicit exit request may retry finishing.
        manualExitInProgress = true
        stopOwnedLock()?.let { return record(it) }
        perform(KioskOperation.FINISH_TASK) {
            platform.finishAndRemoveTask()
        }?.let { return record(it) }
        manualExitCompleted = true
        return record(KioskResult.Released)
    }

    private fun stopOwnedLock(): KioskResult.Failed? {
        if (!lockRequested) return null
        perform(KioskOperation.STOP_LOCK_TASK) {
            platform.stopLockTask()
        }?.let { return it }
        lockRequested = false
        return null
    }

    private fun record(result: KioskResult): KioskResult {
        lastResult = result
        return result
    }

    private inline fun perform(
        operation: KioskOperation,
        action: () -> Unit,
    ): KioskResult.Failed? = try {
        action()
        null
    } catch (_: SecurityException) {
        KioskResult.Failed(operation, KioskFailureReason.SECURITY)
    } catch (_: IllegalStateException) {
        KioskResult.Failed(operation, KioskFailureReason.ILLEGAL_STATE)
    }
}
