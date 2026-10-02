package com.MegaStream.app.ui.screens.license

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.LocalContentColor
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.EntitlementState
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.domain.licensing.LicenseAccessState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Standalone UI: the caller owns wiring and independently supplies verified entitlement to the VM.
 * No activation transport result grants playback. Protected playback still needs the domain gate.
 * Password text is remember-only, never saveable. JVM/Compose immutable Strings and IME buffers
 * cannot be zeroed; clearing drops UI references, not a promise of secure heap erasure.
 */
@Composable
fun LicenseActivationScreen(
    viewModel: LicenseActivationViewModel,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val secret = remember(viewModel) { mutableStateOf("") }
    val requesters = remember(viewModel) { LicenseFocusTarget.entries.associateWith { FocusRequester() } }
    val order = LicenseFocusPolicy.order(state)
    val lastFocused = remember(viewModel) { mutableStateOf<LicenseFocusTarget?>(null) }
    val focusManager = LocalFocusManager.current
    fun control(target: LicenseFocusTarget, sideBySide: Boolean = false): Modifier {
        val index = order.indexOf(target)
        return Modifier.fillMaxWidth()
            .focusRequester(requesters.getValue(target))
            .onFocusChanged { if (it.isFocused) lastFocused.value = target }
            .focusProperties {
                val before = order.getOrNull(index - 1)?.let { requesters.getValue(it) } ?: FocusRequester.Default
                val after = order.getOrNull(index + 1)?.let { requesters.getValue(it) } ?: FocusRequester.Default
                up = before
                previous = before
                down = after
                next = after
                if (sideBySide && target in listOf(LicenseFocusTarget.DIRECT_CHOICE, LicenseFocusTarget.CODE_CHOICE)) {
                    up = FocusRequester.Default
                    down = order.getOrNull(2)?.let { requesters.getValue(it) } ?: FocusRequester.Default
                    if (target == LicenseFocusTarget.DIRECT_CHOICE) end = requesters.getValue(LicenseFocusTarget.CODE_CHOICE)
                    else start = requesters.getValue(LicenseFocusTarget.DIRECT_CHOICE)
                }
            }
            .semantics { traversalIndex = target.ordinal.toFloat() }
    }
    fun select(mode: LicenseActivationMode) {
        secret.value = ""
        viewModel.selectMode(mode)
    }
    fun submit() {
        val chars = secret.value.toCharArray()
        secret.value = "" // Clear BEFORE handing the mutable credential to the coordinator.
        try { viewModel.submitKey(chars) } finally { chars.fill('\u0000') }
    }
    DisposableEffect(viewModel) {
        onDispose {
            secret.value = ""
            viewModel.dispose()
        }
    }
    LaunchedEffect(state.mode, state.disposed, state.playbackUnblocked) { secret.value = "" }
    LaunchedEffect(viewModel, order) {
        val previous = lastFocused.value
        if (previous == null && !embedded) {
            LicenseFocusPolicy.initialFocus(state)?.let { requesters.getValue(it).requestFocus() }
        } else if (previous != null && previous !in order) {
            val target = LicenseFocusPolicy.recoveryTarget(state)
            if (target != null) requesters.getValue(target).requestFocus()
            else {
                focusManager.clearFocus()
                lastFocused.value = null
            }
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    BoxWithConstraints(modifier.fillMaxSize().then(
        if (embedded) Modifier else Modifier.background(MaterialTheme.colorScheme.background)
    )) {
        val sideBySide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                horizontal = if (embedded) 20.dp else if (sideBySide) 48.dp else 24.dp,
                vertical = if (embedded) 16.dp else 32.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .semantics { isTraversalGroup = true },
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(stringResource(if (embedded) R.string.settings_license else R.string.license_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                if (embedded) {
                    Text(stringResource(R.string.license_settings_verified_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(if (state.playbackUnblocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.license_entitlement_status, stringResource(entitlementLabel(state.entitlement.state))),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.license_entitlement_expiry,
                            state.entitlement.expiresAtEpochSeconds?.let { formatInstant(Instant.ofEpochSecond(it)) }
                                ?: stringResource(R.string.license_expiry_unknown)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(if (state.playbackUnblocked)
                            R.string.license_playback_allowed else R.string.license_playback_blocked))
                    }
                }
                if (!state.playbackUnblocked) {
                    val choices: @Composable (Modifier) -> Unit = { choiceModifier ->
                        TvButton(
                            onClick = { select(LicenseActivationMode.DIRECT_KEY) }, enabled = !state.disposed,
                            colors = ButtonDefaults.colors(containerColor = if (state.mode == LicenseActivationMode.DIRECT_KEY)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (state.mode == LicenseActivationMode.DIRECT_KEY)
                                    MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = choiceModifier.then(control(LicenseFocusTarget.DIRECT_CHOICE, sideBySide))
                                .semantics { selected = state.mode == LicenseActivationMode.DIRECT_KEY },
                        ) { Text(stringResource(R.string.license_direct_choice)) }
                        TvButton(
                            onClick = { select(LicenseActivationMode.ACTIVATION_CODE) }, enabled = !state.disposed,
                            colors = ButtonDefaults.colors(containerColor = if (state.mode == LicenseActivationMode.ACTIVATION_CODE)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (state.mode == LicenseActivationMode.ACTIVATION_CODE)
                                    MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = choiceModifier.then(control(LicenseFocusTarget.CODE_CHOICE, sideBySide))
                                .semantics { selected = state.mode == LicenseActivationMode.ACTIVATION_CODE },
                        ) { Text(stringResource(R.string.license_code_choice)) }
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            choices(Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { choices(Modifier) }
                    }

                    if (state.mode == LicenseActivationMode.DIRECT_KEY) {
                        CompositionLocalProvider(LocalTextToolbar provides SecretTextToolbar) {
                            OutlinedTextField(
                                value = secret.value,
                                onValueChange = { if (!state.busy && !state.disposed && it.length <= 512) secret.value = it },
                                label = { Text(stringResource(R.string.license_key_label)) },
                                enabled = !state.busy && !state.disposed,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = control(LicenseFocusTarget.KEY).onPreviewKeyEvent {
                                    val event = it.nativeKeyEvent
                                    // Block clipboard chords (including dedicated keys and legacy Insert/Delete).
                                    event.keyCode in setOf(KeyEvent.KEYCODE_COPY, KeyEvent.KEYCODE_CUT, KeyEvent.KEYCODE_PASTE) ||
                                        ((event.isCtrlPressed || event.isMetaPressed) && event.keyCode in setOf(
                                            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_INSERT)) ||
                                        (event.isShiftPressed && event.keyCode in setOf(KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL))
                                },
                            )
                        }
                        TvButton(onClick = { submit() }, enabled = !state.busy && !state.disposed,
                            modifier = control(LicenseFocusTarget.SUBMIT)) { Text(stringResource(R.string.license_submit)) }
                    } else {
                        Text(stringResource(R.string.license_code_help))
                        state.activationCode?.let { code ->
                            val codeDescription = stringResource(R.string.license_code_value, code)
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.license_code_choice), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(code, style = (if (sideBySide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge)
                                    .copy(textDirection = TextDirection.Ltr),
                                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                                        contentDescription = codeDescription
                                    })
                                Text(stringResource(R.string.license_code_expiry,
                                    state.codeExpiresAtEpochMillis?.let { formatInstant(Instant.ofEpochMilli(it)) }
                                        ?: stringResource(R.string.license_expiry_unknown)))
                            }
                        }
                        TvButton(onClick = viewModel::requestCode, enabled = !state.busy && !state.disposed,
                            modifier = control(LicenseFocusTarget.REQUEST)) { Text(stringResource(R.string.license_request_code)) }
                        if (state.canRetryPolling) {
                            TvButton(onClick = viewModel::retryPolling, modifier = control(LicenseFocusTarget.RETRY)) {
                                Text(stringResource(R.string.license_retry_polling))
                            }
                        }
                    }
                    if (!state.disposed && (state.busy || state.canRetryPolling)) {
                        TvButton(onClick = viewModel::stopPolling, modifier = control(LicenseFocusTarget.STOP)) {
                            Text(stringResource(R.string.license_stop))
                        }
                    }
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(pollLabel(state.pollStatus)))
                        if (state.mode == LicenseActivationMode.ACTIVATION_CODE) {
                            Text(stringResource(R.string.license_poll_attempts, state.pollAttempts))
                        }
                        state.error?.let { Text(stringResource(errorLabel(it)), color = MaterialTheme.colorScheme.error) }
                        state.transportState?.let {
                            Text(if (it == EntitlementState.ALLOWED) stringResource(R.string.license_transport_accepted)
                                else stringResource(R.string.license_transport_rejected, stringResource(transportLabel(it))))
                        }
                    }
                }
            }
        }
    }
    }
}

private fun formatInstant(instant: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(instant)

private fun entitlementLabel(state: LicenseAccessState): Int = when (state) {
    LicenseAccessState.ALLOWED -> R.string.license_status_allowed
    LicenseAccessState.UNLICENSED -> R.string.license_status_unlicensed
    LicenseAccessState.NOT_STARTED -> R.string.license_status_not_started
    LicenseAccessState.EXPIRED -> R.string.license_status_expired
    LicenseAccessState.SUSPENDED -> R.string.license_status_suspended
    LicenseAccessState.REVOKED -> R.string.license_status_revoked
    LicenseAccessState.INSTALLATION_DISABLED -> R.string.license_status_installation_disabled
    LicenseAccessState.VERIFICATION_REQUIRED -> R.string.license_status_verification_required
}

private fun transportLabel(state: EntitlementState): Int = when (state) {
    EntitlementState.ALLOWED -> R.string.license_status_allowed
    EntitlementState.UNLICENSED -> R.string.license_status_unlicensed
    EntitlementState.NOT_STARTED -> R.string.license_status_not_started
    EntitlementState.EXPIRED -> R.string.license_status_expired
    EntitlementState.SUSPENDED -> R.string.license_status_suspended
    EntitlementState.REVOKED -> R.string.license_status_revoked
    EntitlementState.INSTALLATION_DISABLED -> R.string.license_status_installation_disabled
    EntitlementState.VERIFICATION_REQUIRED -> R.string.license_status_verification_required
}

private fun pollLabel(status: LicenseActivationPollStatus): Int = when (status) {
    LicenseActivationPollStatus.IDLE -> R.string.license_poll_idle
    LicenseActivationPollStatus.REQUESTING -> R.string.license_poll_requesting
    LicenseActivationPollStatus.POLLING -> R.string.license_poll_polling
    LicenseActivationPollStatus.WAITING -> R.string.license_poll_waiting
    LicenseActivationPollStatus.RETRYABLE_FAILURE -> R.string.license_poll_retryable
    LicenseActivationPollStatus.ACTIVATED -> R.string.license_poll_activated
    LicenseActivationPollStatus.EXPIRED -> R.string.license_poll_expired
    LicenseActivationPollStatus.EXHAUSTED -> R.string.license_poll_exhausted
    LicenseActivationPollStatus.STOPPED -> R.string.license_poll_stopped
}

private fun errorLabel(error: LicenseActivationError): Int = when (error) {
    LicenseActivationError.INVALID_KEY -> R.string.license_error_invalid_key
    LicenseActivationError.NETWORK -> R.string.license_error_network
    LicenseActivationError.TIMEOUT -> R.string.license_error_timeout
    LicenseActivationError.RATE_LIMITED -> R.string.license_error_rate_limited
    LicenseActivationError.REJECTED -> R.string.license_error_rejected
    LicenseActivationError.INVALID_RESPONSE -> R.string.license_error_invalid_response
    LicenseActivationError.UNKNOWN -> R.string.license_error_unknown
}

private object SecretTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}
