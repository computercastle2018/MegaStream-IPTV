package com.MegaStream.app.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionInformationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun information_is_hidden_until_requested_and_can_be_hidden_again() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.settings_subscription_information)
        val connections = context.getString(R.string.settings_subscription_connections, 2)
        composeRule.setContent {
            MegaStreamTheme {
                ProviderSettingsCard(
                    provider = Provider(name = "Sports", type = ProviderType.XTREAM_CODES,
                        serverUrl = "https://private-provider.invalid", password = "private-password", maxConnections = 2),
                    isActive = true, isSyncing = false,
                    xtreamLiveOnboardingPhase = null, xtreamLiveOnboarding = null,
                    xtreamIndexSectionStatuses = emptyMap(), diagnostics = null,
                    databaseMaintenance = null, syncWarnings = emptyList(),
                    onRetryWarningAction = {}, onConnect = {}, onRefresh = {}, onDelete = {},
                    onEdit = {}, onParentalControl = {}, onToggleM3uVodClassification = {},
                    onRefreshM3uClassification = {}
                )
            }
        }
        composeRule.onNodeWithText(connections).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label).performClick()
        composeRule.onNodeWithText(connections).assertExists()
        composeRule.onNodeWithText("private-password", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("private-provider.invalid", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label).performClick()
        composeRule.onNodeWithText(connections).assertDoesNotExist()
        composeRule.onNodeWithText("Sports").assertExists()
    }
}
