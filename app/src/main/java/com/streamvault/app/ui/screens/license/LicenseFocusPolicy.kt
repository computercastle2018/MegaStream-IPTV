package com.MegaStream.app.ui.screens.license

/** Logical vertical order, independent of layout direction. Disabled controls are omitted. */
internal enum class LicenseFocusTarget { DIRECT_CHOICE, CODE_CHOICE, KEY, SUBMIT, REQUEST, RETRY, STOP }

internal object LicenseFocusPolicy {
    fun initialFocus(state: LicenseActivationState): LicenseFocusTarget? = order(state).firstOrNull()

    fun recoveryTarget(state: LicenseActivationState): LicenseFocusTarget? = when {
        state.disposed || state.playbackUnblocked -> null
        state.busy -> LicenseFocusTarget.STOP
        state.canRetryPolling && state.mode == LicenseActivationMode.ACTIVATION_CODE -> LicenseFocusTarget.RETRY
        state.mode == LicenseActivationMode.DIRECT_KEY -> LicenseFocusTarget.KEY
        else -> LicenseFocusTarget.REQUEST
    }

    fun order(state: LicenseActivationState): List<LicenseFocusTarget> {
        if (state.disposed || state.playbackUnblocked) return emptyList()
        return buildList {
            add(LicenseFocusTarget.DIRECT_CHOICE)
            add(LicenseFocusTarget.CODE_CHOICE)
            if (state.mode == LicenseActivationMode.DIRECT_KEY && !state.busy) {
                add(LicenseFocusTarget.KEY)
                add(LicenseFocusTarget.SUBMIT)
            }
            if (state.mode == LicenseActivationMode.ACTIVATION_CODE && !state.busy) {
                add(LicenseFocusTarget.REQUEST)
                if (state.canRetryPolling) add(LicenseFocusTarget.RETRY)
            }
            if (state.busy || state.canRetryPolling) add(LicenseFocusTarget.STOP)
        }
    }
}
