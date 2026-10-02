package com.MegaStream.app.ui.screens.license

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.licensing.LicenseAccessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LicenseSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun licensedPageRemainsPresentAndShowsActivationAfterVerifiedRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val port = object : LicenseActivationPort {
            override suspend fun activateDirect(rawKey: String): Nothing = error("Unexpected activation")
            override suspend fun requestCode(): Nothing = error("Unexpected code request")
            override suspend fun pollCode(code: String, pollToken: String): Nothing = error("Unexpected polling")
        }
        val model = LicenseActivationViewModel(
            port, scope, LicenseEntitlementSnapshot(LicenseAccessState.ALLOWED)
        )
        try {
            composeRule.setContent {
                MegaStreamTheme {
                    LicenseActivationScreen(model, embedded = true)
                }
            }
            composeRule.onNodeWithText(context.getString(R.string.settings_license)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.license_playback_allowed)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.license_direct_choice)).assertDoesNotExist()

            composeRule.runOnIdle {
                model.updateEntitlement(LicenseEntitlementSnapshot(LicenseAccessState.REVOKED))
            }

            composeRule.onNodeWithText(context.getString(R.string.settings_license)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.license_playback_blocked)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.license_direct_choice)).assertExists()
            composeRule.runOnIdle { assertFalse(model.state.value.disposed) }
        } finally {
            model.dispose()
            scope.cancel()
        }
    }
}
