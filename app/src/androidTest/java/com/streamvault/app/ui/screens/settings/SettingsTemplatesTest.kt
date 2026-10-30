package com.MegaStream.app.ui.screens.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.MegaStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTemplatesTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun remoteTemplateRemainsEditableAndLaterAdminChoiceIsReflected() {
        val selected = mutableStateOf(AppUiStyle.STUDIO)
        var changes = 0
        composeRule.setContent {
            MegaStreamTheme {
                StudioSettingsTemplates(
                    selectedStyle = selected.value,
                    managed = true,
                    onStyleSelected = { selected.value = it; changes++ }
                )
            }
        }
        AppUiStyle.entries.forEach { style ->
            composeRule.onNodeWithTag("template_option_${style.storageValue}").assertIsEnabled()
        }
        composeRule.onNodeWithTag("template_option_${AppUiStyle.STUDIO.storageValue}").assertIsSelected()
        composeRule.onNodeWithTag("template_option_${AppUiStyle.MODERN.storageValue}").performClick()
        composeRule.runOnIdle {
            assertEquals(1, changes)
            assertEquals(AppUiStyle.MODERN, selected.value)
            selected.value = AppUiStyle.CLASSIC
        }
        composeRule.onNodeWithTag("template_option_${AppUiStyle.CLASSIC.storageValue}").assertIsSelected().assertIsEnabled()
    }
}
