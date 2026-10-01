package com.MegaStream.app.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * UI-thread adapter for managed-device kiosk transitions; never provisions or allowlists a device.
 * Ownership is limited to successful lock requests made through this controller instance.
 */
class ManagedKioskController(
    activity: Activity,
    applicationPackage: String = activity.applicationContext.packageName,
) {
    init {
        require(applicationPackage == activity.applicationContext.packageName) {
            "applicationPackage must match the activity application package"
        }
    }

    private val engine = KioskControllerEngine(AndroidKioskPlatform(activity, applicationPackage))

    val lastResult: KioskResult
        get() = engine.lastResult

    val lockRequested: Boolean
        get() = engine.lockRequested

    val manualExitCompleted: Boolean
        get() = engine.manualExitCompleted

    fun update(
        policy: DevicePolicySnapshot,
        appForeground: Boolean,
        playbackActive: Boolean,
        explicitManualExitInProgress: Boolean = false,
    ): KioskResult = engine.update(
        policy,
        appForeground,
        playbackActive,
        explicitManualExitInProgress,
    )

    fun requestManualExit(policy: DevicePolicySnapshot): KioskResult = engine.requestManualExit(policy)

    private class AndroidKioskPlatform(
        private val activity: Activity,
        private val applicationPackage: String,
    ) : KioskPlatform {
        // Resolve within engine-guarded operations, including service lookup failures.
        private fun devicePolicyManager(): DevicePolicyManager? =
            activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

        override fun isDeviceOwnerApp(): Boolean =
            devicePolicyManager()?.isDeviceOwnerApp(applicationPackage) == true

        override fun isLockTaskPermitted(): Boolean =
            devicePolicyManager()?.isLockTaskPermitted(applicationPackage) == true

        override fun startLockTask() = activity.startLockTask()

        override fun stopLockTask() = activity.stopLockTask()

        override fun finishAndRemoveTask() = activity.finishAndRemoveTask()
    }
}
