package com.MegaStream.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.app.R
import com.MegaStream.app.navigation.LicenseActivationRoute
import com.MegaStream.app.navigation.LicenseNavigationViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyleManaged

@Composable
internal fun SettingsContentPane(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    screenLabels: SettingsScreenLabels,
    dialogState: SettingsScreenDialogState,
    licenseNavigation: LicenseNavigationViewModel,
    providerState: SettingsProviderSectionState,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onNavigateToParentalControl: (Long) -> Unit,
    onChooseRecordingFolder: () -> Unit,
    onCreateBackup: () -> Unit,
    onShareBackup: () -> Unit,
    onViewCrashReport: () -> Unit,
    onShareCrashReport: () -> Unit,
    onDeleteCrashReport: () -> Unit,
    onRestoreBackup: () -> Unit,
    onDriveSignIn: () -> Unit,
    onDriveSignOut: () -> Unit,
    onDrivePush: () -> Unit,
    onDrivePull: () -> Unit,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveStyle = LocalAppUiStyle.current
    val studio = effectiveStyle == AppUiStyle.STUDIO
    val managed = LocalAppUiStyleManaged.current
    if (dialogState.selectedCategory == 8) {
        LicenseActivationRoute(
            navigation = licenseNavigation,
            onContinue = {},
            onCancel = {},
            settingsMode = true,
            modifier = modifier.padding(top = if (studio) 8.dp else 76.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .imePadding(),
        contentPadding = PaddingValues(start = if (studio) 0.dp else 20.dp,
            top = if (studio) 8.dp else 76.dp, end = if (studio) 0.dp else 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = !uiState.isSyncing
    ) {
        if (dialogState.selectedCategory == 0) {
            providerSection(
                uiState = uiState,
                onAddProvider = onAddProvider,
                onEditProvider = onEditProvider,
                onNavigateToParentalControl = onNavigateToParentalControl,
                viewModel = viewModel,
                providerState = providerState
            )
        } else if (dialogState.selectedCategory == 1) {
            settingsPlaybackSection(
                uiState = uiState,
                viewModel = viewModel,
                timeshiftDepthLabel = screenLabels.timeshiftDepthLabel,
                decoderModeLabel = screenLabels.decoderModeLabel,
                audioOutputPreferenceLabel = screenLabels.audioOutputPreferenceLabel,
                surfaceModeLabel = screenLabels.surfaceModeLabel,
                playbackSpeedLabel = screenLabels.playbackSpeedLabel,
                defaultStopTimerLabel = screenLabels.defaultStopTimerLabel,
                defaultIdleTimerLabel = screenLabels.defaultIdleTimerLabel,
                audioVideoOffsetLabel = screenLabels.audioVideoOffsetLabel,
                controlsTimeoutLabel = screenLabels.controlsTimeoutLabel,
                liveOverlayTimeoutLabel = screenLabels.liveOverlayTimeoutLabel,
                noticeTimeoutLabel = screenLabels.noticeTimeoutLabel,
                diagnosticsTimeoutLabel = screenLabels.diagnosticsTimeoutLabel,
                preferredAudioLanguageLabel = screenLabels.preferredAudioLanguageLabel,
                subtitleSizeLabel = screenLabels.subtitleSizeLabel,
                subtitleTextColorLabel = screenLabels.subtitleTextColorLabel,
                subtitleBackgroundLabel = screenLabels.subtitleBackgroundLabel,
                wifiQualityLabel = screenLabels.wifiQualityLabel,
                ethernetQualityLabel = screenLabels.ethernetQualityLabel,
                lastSpeedTestLabel = screenLabels.lastSpeedTestLabel,
                lastSpeedTestSummary = screenLabels.lastSpeedTestSummary,
                speedTestRecommendationLabel = screenLabels.speedTestRecommendationLabel,
                onShowTimeshiftDepthDialogChange = { dialogState.showTimeshiftDepthDialog = it },
                onShowDecoderModeDialogChange = { dialogState.showDecoderModeDialog = it },
                onShowAudioOutputPreferenceDialogChange = { dialogState.showAudioOutputPreferenceDialog = it },
                onShowSurfaceModeDialogChange = { dialogState.showSurfaceModeDialog = it },
                onShowPlaybackSpeedDialogChange = { dialogState.showPlaybackSpeedDialog = it },
                onShowDefaultStopTimerDialogChange = { dialogState.showDefaultStopTimerDialog = it },
                onShowDefaultIdleTimerDialogChange = { dialogState.showDefaultIdleTimerDialog = it },
                onShowAudioVideoOffsetDialogChange = { dialogState.showAudioVideoOffsetDialog = it },
                onShowControlsTimeoutDialogChange = { dialogState.showControlsTimeoutDialog = it },
                onShowLiveOverlayTimeoutDialogChange = { dialogState.showLiveOverlayTimeoutDialog = it },
                onShowNoticeTimeoutDialogChange = { dialogState.showNoticeTimeoutDialog = it },
                onShowDiagnosticsTimeoutDialogChange = { dialogState.showDiagnosticsTimeoutDialog = it },
                onShowAudioLanguageDialogChange = { dialogState.showAudioLanguageDialog = it },
                onShowSubtitleSizeDialogChange = { dialogState.showSubtitleSizeDialog = it },
                onShowSubtitleTextColorDialogChange = { dialogState.showSubtitleTextColorDialog = it },
                onShowSubtitleBackgroundDialogChange = { dialogState.showSubtitleBackgroundDialog = it },
                onShowWifiQualityDialogChange = { dialogState.showWifiQualityDialog = it },
                onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it }
            )
        } else if (dialogState.selectedCategory == 2) {
            settingsBrowsingSection(
                uiState = uiState,
                viewModel = viewModel,
                context = context,
                guideDefaultCategoryLabel = screenLabels.guideDefaultCategoryLabel,
                timeFormatLabel = screenLabels.timeFormatLabel,
                appLanguageLabel = screenLabels.appLanguageLabel,
                onShowAppUiStyleDialogChange = { dialogState.showAppUiStyleDialog = it },
                onRefreshCurrentLists = {
                    val provider = uiState.providers.firstOrNull { it.id == uiState.activeProviderId }
                    if (provider != null) {
                        providerState.pendingSyncProviderId = provider.id
                        providerState.customSyncSelections = buildSet {
                            add(ProviderSyncSelection.TV)
                            add(ProviderSyncSelection.MOVIES)
                            add(ProviderSyncSelection.EPG)
                            if (provider.type == ProviderType.XTREAM_CODES) {
                                add(ProviderSyncSelection.SERIES)
                            }
                        }
                        providerState.showProviderSyncDialog = true
                    }
                },
                onShowLiveTvModeDialogChange = { dialogState.showLiveTvModeDialog = it },
                onShowLiveTvFiltersDialogChange = { dialogState.showLiveTvFiltersDialog = it },
                onShowLiveTvQuickFilterVisibilityDialogChange = { dialogState.showLiveTvQuickFilterVisibilityDialog = it },
                onShowLiveChannelNumberingDialogChange = { dialogState.showLiveChannelNumberingDialog = it },
                onShowLiveChannelGroupingDialogChange = { dialogState.showLiveChannelGroupingDialog = it },
                onShowGroupedChannelLabelDialogChange = { dialogState.showGroupedChannelLabelDialog = it },
                onShowLiveVariantPreferenceDialogChange = { dialogState.showLiveVariantPreferenceDialog = it },
                onShowGuideDefaultCategoryDialogChange = { dialogState.showGuideDefaultCategoryDialog = it },
                onShowTimeFormatDialogChange = { dialogState.showTimeFormatDialog = it },
                onShowVodViewModeDialogChange = { dialogState.showVodViewModeDialog = it },
                onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it },
                onShowLanguageDialogChange = { dialogState.showLanguageDialog = it }
            )
        } else if (dialogState.selectedCategory == 3) {
            settingsPrivacySection(
                uiState = uiState,
                viewModel = viewModel,
                onPendingProtectionLevelChange = { dialogState.pendingProtectionLevel = it },
                onPendingActionChange = { dialogState.pendingAction = it },
                onShowPinDialogChange = { dialogState.showPinDialog = it },
                onShowLevelDialogChange = { dialogState.showLevelDialog = it },
                onShowClearHistoryDialogChange = { dialogState.showClearHistoryDialog = it }
            )
        } else if (dialogState.selectedCategory == 4) {
            settingsRecordingSection(
                uiState = uiState,
                viewModel = viewModel,
                onChooseFolder = onChooseRecordingFolder,
                onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
                onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
                onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
                onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it },
                onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it }
            )
        } else if (dialogState.selectedCategory == 5) {
            settingsBackupSection(
                onCreateBackup = onCreateBackup,
                onShareBackup = onShareBackup,
                onRestoreBackup = onRestoreBackup
            )
            settingsDriveBackupSection(
                uiState = uiState,
                onSignIn = onDriveSignIn,
                onSignOut = onDriveSignOut,
                onPush = onDrivePush,
                onPull = onDrivePull
            )
        } else if (dialogState.selectedCategory == 6) {
            epgSourcesSection(
                uiState = uiState,
                viewModel = viewModel
            )
        } else if (dialogState.selectedCategory == 7) {
            item {
                if (studio) {
                    StudioSettingsTemplates(
                        selectedStyle = effectiveStyle,
                        managed = managed,
                        onStyleSelected = { if (!managed) viewModel.setAppUiStyle(it) },
                    )
                } else {
                    val displayedStyle = if (managed) effectiveStyle else uiState.appUiStyle
                    val palette = when (displayedStyle) {
                        AppUiStyle.CLASSIC -> listOf(Color(0xFF07111B), Color(0xFF69A8FF), Color(0xFF4FD39A))
                        AppUiStyle.MODERN -> listOf(Color(0xFF111822), Color(0xFF64D2FF), Color(0xFFFFCF6E))
                        AppUiStyle.STUDIO -> listOf(Color(0xFF191F1E), Color(0xFF69DCB1), Color(0xFFECCB7B))
                    }
                    TvClickableSurface(
                        onClick = { if (!managed) dialogState.showAppUiStyleDialog = true },
                        enabled = !managed,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(stringResource(R.string.settings_app_ui_style),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                palette.forEach { color ->
                                    Box(Modifier.size(28.dp).background(color, RoundedCornerShape(4.dp)))
                                }
                            }
                            Text(stringResource(displayedStyle.labelResId),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(if (managed) R.string.settings_template_managed else displayedStyle.descriptionResId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (dialogState.selectedCategory == 9) {
            settingsAboutSection(
                uiState = uiState,
                context = context,
                buildVerificationLabel = screenLabels.buildVerificationLabel,
                onOpenUri = onOpenUri,
                onCheckForUpdates = viewModel::checkForAppUpdates,
                onInstallDownloadedUpdate = viewModel::installDownloadedUpdate,
                onDownloadLatestUpdate = viewModel::downloadLatestUpdate,
                onSetAutoCheckAppUpdates = viewModel::setAutoCheckAppUpdates,
                onSetAutoDownloadAppUpdates = viewModel::setAutoDownloadAppUpdates,
                onRefreshDownloadState = viewModel::refreshDownloadState,
                onViewCrashReport = onViewCrashReport,
                onShareCrashReport = onShareCrashReport,
                onDeleteCrashReport = onDeleteCrashReport
            )
        }
    }
}
