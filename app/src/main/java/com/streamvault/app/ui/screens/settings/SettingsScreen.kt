package com.MegaStream.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.MegaStream.app.backup.BackupFileBridge
import com.MegaStream.app.diagnostics.CrashReportStore
import com.MegaStream.app.util.OfficialBuildVerifier
import com.MegaStream.app.ui.components.shell.AppTopBarCloseAction
import com.MegaStream.app.ui.components.shell.AppNavigationChrome
import com.MegaStream.app.ui.components.shell.AppScreenScaffold
import com.MegaStream.app.ui.theme.*
import com.MegaStream.domain.model.Provider
import androidx.compose.ui.res.stringResource
import com.MegaStream.app.R
import com.MegaStream.app.navigation.LicenseNavigationViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.MegaStream.app.ui.design.requestFocusSafely
import kotlinx.coroutines.delay
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.interaction.TvClickableSurface
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag


@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    currentRoute: String,
    initialBackupImportUri: String? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val studio = LocalAppUiStyle.current == AppUiStyle.STUDIO
    val settingsNavFocusRequester = remember { FocusRequester() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainActivity = context.findMainActivity()
    val licenseNavigation: LicenseNavigationViewModel = hiltViewModel(
        viewModelStoreOwner = mainActivity ?: checkNotNull(LocalViewModelStoreOwner.current)
    )
    val officialBuildVerification = remember(context.packageName) { OfficialBuildVerifier.verify(context) }
    val screenLabels = rememberSettingsScreenLabels(
        uiState = uiState,
        context = context,
        officialBuildStatus = officialBuildVerification.status
    )
    val dialogState = rememberSettingsScreenDialogState()
    val providerState = rememberSettingsProviderSectionState(dialogState)
    var handledInitialBackupImportUri by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportConfig(it.toString()) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectBackup(it.toString()) }
    }

    fun shareBackup() {
        val file = runCatching { BackupFileBridge.createExportFile(context) }.getOrNull()
        if (file == null) {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_share_prepare_failed))
            return
        }
        val uri = BackupFileBridge.providerUriForFile(context, file)
        viewModel.exportConfig(uri.toString()) {
            runCatching { context.startActivity(BackupFileBridge.buildShareIntent(uri)) }
                .onFailure { viewModel.showUserMessage(context.getString(R.string.settings_backup_share_failed)) }
        }
    }

    fun shareCrashReport() {
        val file = CrashReportStore.latestReportFile(context)
        if (!file.isFile || file.length() <= 0L) {
            viewModel.showUserMessage(context.getString(R.string.settings_crash_report_missing))
            viewModel.refreshCrashReport()
            return
        }
        val uri = CrashReportStore.providerUriForFile(context, file)
        runCatching { context.startActivity(CrashReportStore.buildShareIntent(uri)) }
            .onFailure { viewModel.showUserMessage(context.getString(R.string.settings_crash_report_share_failed)) }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.completeDriveSignIn(result.data)
    }

    val recordingFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = DocumentFile.fromTreeUri(context, it)?.name
            viewModel.updateRecordingFolder(it.toString(), displayName)
        }
    }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    LaunchedEffect(uiState.recordingItems) {
        dialogState.selectedRecordingId = when {
            uiState.recordingItems.isEmpty() -> null
            dialogState.selectedRecordingId == null -> uiState.recordingItems.first().id
            uiState.recordingItems.any { item -> item.id == dialogState.selectedRecordingId } -> dialogState.selectedRecordingId
            else -> uiState.recordingItems.first().id
        }
    }

    LaunchedEffect(initialBackupImportUri) {
        val uri = initialBackupImportUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handledInitialBackupImportUri == uri) return@LaunchedEffect
        handledInitialBackupImportUri = uri
        dialogState.selectedCategory = 5
        viewModel.inspectBackup(uri)
    }

    LaunchedEffect(currentRoute, dialogState.selectedCategory, studio) {
        delay(80)
        settingsNavFocusRequester.requestFocusSafely(tag = "SettingsScreen", target = "Selected settings section")
    }

    val contentPane: @Composable (Modifier) -> Unit = { paneModifier ->
        SettingsContentPane(
            uiState = uiState,
            viewModel = viewModel,
            context = context,
            screenLabels = screenLabels,
            dialogState = dialogState,
            licenseNavigation = licenseNavigation,
            providerState = providerState,
            onAddProvider = onAddProvider,
            onEditProvider = onEditProvider,
            onNavigateToParentalControl = onNavigateToParentalControl,
            onChooseRecordingFolder = { recordingFolderLauncher.launch(null) },
            onCreateBackup = { createDocumentLauncher.launch("MegaStream_backup.json") },
            onShareBackup = ::shareBackup,
            onViewCrashReport = viewModel::viewCrashReport,
            onShareCrashReport = ::shareCrashReport,
            onDeleteCrashReport = viewModel::deleteCrashReport,
            onRestoreBackup = {
                openDocumentLauncher.launch(
                    arrayOf("application/json", "text/json", "application/x-json", "application/octet-stream", "*/*")
                )
            },
            onDriveSignIn = { viewModel.beginDriveSignIn(driveSignInLauncher) },
            onDriveSignOut = viewModel::signOutDrive,
            onDrivePush = viewModel::pushToDrive,
            onDrivePull = viewModel::pullFromDrive,
            onOpenUri = uriHandler::openUri,
            modifier = paneModifier
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = { if (!uiState.isSyncing) onNavigate(it) },
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_providers_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false,
            topBarActions = {
                AppTopBarCloseAction(
                    onClick = { mainActivity?.finishAffinity() }
                )
            }
        ) {
            if (studio) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("studio_settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                    StudioSettingsSections(dialogState.selectedCategory, settingsNavFocusRequester,
                        onCategorySelected = { if (!uiState.isSyncing) dialogState.selectedCategory = it })
                    contentPane(Modifier.weight(1f))
                }
            } else Row(modifier = Modifier.fillMaxSize()) {
                SettingsNavigationRail(
                    selectedCategory = dialogState.selectedCategory,
                    focusRequester = settingsNavFocusRequester,
                    onCategorySelected = { dialogState.selectedCategory = it }
                )

                // Thin vertical separator
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.07f))
                )

                contentPane(Modifier.weight(1f))
            }
        }

    SettingsScreenOverlays(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        dialogState = dialogState,
        mainActivity = mainActivity,
        currentRoute = currentRoute,
        modifier = Modifier
    )
}
}

@Composable
private fun StudioSettingsSections(
    selectedCategory: Int,
    focusRequester: FocusRequester,
    onCategorySelected: (Int) -> Unit,
) {
    val labels = listOf(R.string.settings_providers, R.string.settings_playback,
        R.string.settings_browsing, R.string.settings_privacy, R.string.settings_recording_title,
        R.string.settings_backup_restore, R.string.settings_epg_sources_section,
        R.string.settings_templates, R.string.settings_license, R.string.settings_about)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedCategory)
    LaunchedEffect(selectedCategory) { state.scrollToItem(selectedCategory) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(labels) { index, label ->
            TvClickableSurface(
                onClick = { onCategorySelected(index) },
                modifier = (if (selectedCategory == index) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { selected = selectedCategory == index },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selectedCategory == index) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                ),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}
