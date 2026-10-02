package com.MegaStream.app.ui.notifications

import kotlinx.coroutines.delay

// Process-owned, like the repository: host recreation in picture-in-picture must not replay tones.
internal object DeviceNoticeToneHistory {
    val soundedIds = mutableSetOf<String>()
}

internal fun claimDeviceNoticeTone(
    noticeId: String?, inboxOpen: Boolean, resumed: Boolean, soundedIds: MutableSet<String>,
): Boolean {
    if (noticeId == null || inboxOpen || !resumed || !soundedIds.add(noticeId)) return false
    while (soundedIds.size > 100) soundedIds.remove(soundedIds.first())
    return true
}

/** The host cancels and restarts this timer when the notice, inbox, or resumed state changes. */
internal suspend fun awaitDeviceNoticeTimeout(
    noticeId: String?,
    inboxOpen: Boolean,
    resumed: Boolean,
    stillEligible: () -> Boolean,
    onTimeout: (String) -> Unit,
) {
    if (noticeId == null || inboxOpen || !resumed) return
    delay(60_000)
    if (stillEligible()) onTimeout(noticeId)
}
