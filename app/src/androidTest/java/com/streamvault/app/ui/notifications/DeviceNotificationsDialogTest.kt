package com.MegaStream.app.ui.notifications

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.DeviceNotice
import com.MegaStream.app.ui.theme.MegaStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceNotificationsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rtlSelectionSurvivesRefreshAndRemoteCanReachClose() {
        val first = DeviceNotice("00000000-0000-0000-0000-000000000001", "Selected message", "Full selected message", "2026-10-01T10:00:00Z")
        val incoming = DeviceNotice("00000000-0000-0000-0000-000000000002", "Incoming message", "New message", "2026-10-02T10:00:00Z")
        var notices by mutableStateOf(listOf(first))
        var refreshing by mutableStateOf(false)
        var showing by mutableStateOf(true)
        var dismissals = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MegaStreamTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    if (showing) DeviceNotificationsDialog(
                        notices = notices, readIds = emptySet(), inboxOpen = true,
                        refreshing = refreshing, refreshFailed = false, onRefresh = {},
                        onDismiss = { dismissals++; showing = false },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag("notification_preview_${first.id}").performClick()
        composeRule.onNodeWithTag("notification_message_detail").assertIsFocused()
        composeRule.runOnIdle { refreshing = true }
        composeRule.runOnIdle { notices = listOf(incoming, first); refreshing = false }
        composeRule.onNodeWithTag("notification_message_detail")
            .assert(hasAnyDescendant(hasText(first.message)))
        composeRule.onNodeWithTag("notification_message_detail").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.device_notifications_close)).assertIsFocused().performClick()
        composeRule.onNodeWithTag("notification_message_detail").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }
}
