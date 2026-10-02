package com.MegaStream.app.kiosk.integration

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import com.MegaStream.app.ui.design.requestFocusSafely
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.MegaStream.app.ui.interaction.TvButton
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.MegaStream.app.R
import kotlinx.coroutines.delay

val LocalKioskController = staticCompositionLocalOf<KioskController?> { null }
private val LocalPlayerFocus = staticCompositionLocalOf<MutableState<FocusRequester?>?> { null }

@Composable
fun KioskHost(
    controller: KioskController,
    inPictureInPicture: Boolean,
    onReturnHome: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state by controller.uiState.collectAsState()
    val exitFocus = remember { FocusRequester() }
    val playerFocus = remember { mutableStateOf<FocusRequester?>(null) }
    var returnFocused by remember { mutableStateOf(false) }
    val showExit = state.playerPresent && state.foreground && !inPictureInPicture
    var interaction by remember { mutableStateOf(0L) }
    var exitVisible by remember { mutableStateOf(false) }
    var focusExit by remember { mutableStateOf(false) }
    fun revealExit() {
        exitVisible = true
        interaction++
    }
    LaunchedEffect(showExit) { exitVisible = false }
    LaunchedEffect(interaction, state.confirmationPending) {
        if (!state.confirmationPending) {
            delay(4000)
            val restoreFocus = returnFocused
            exitVisible = false
            if (restoreFocus) {
                withFrameNanos { }
                playerFocus.value?.requestFocusSafely(tag = "KioskHost", target = "Player root")
            }
        }
    }
    LaunchedEffect(focusExit, exitVisible, showExit) {
        if (focusExit && exitVisible && showExit) {
            exitFocus.requestFocus()
            focusExit = false
        }
    }
    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) controller.cancelManualExit()
    }
    CompositionLocalProvider(LocalKioskController provides controller, LocalPlayerFocus provides playerFocus) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (showExit && native.action == KeyEvent.ACTION_DOWN) revealExit()
            if (showExit && !state.confirmationPending && native.keyCode == KeyEvent.KEYCODE_MENU &&
                native.action == KeyEvent.ACTION_DOWN && (native.isLongPress || native.repeatCount > 0)) {
                focusExit = true
                true
            } else {
                false
            }
        }.pointerInput(showExit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val interacted = event.type == PointerEventType.Press ||
                        (event.type == PointerEventType.Move && event.changes.any { it.position != it.previousPosition }) ||
                        (event.type == PointerEventType.Scroll && event.changes.any { it.scrollDelta != Offset.Zero })
                    if (showExit && interacted) {
                        revealExit()
                    }
                }
            }
        }) {
            content()
            if (showExit) {
                if (exitVisible) {
                    TvButton(
                        onClick = onReturnHome,
                        shape = ButtonDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).focusRequester(exitFocus)
                            .onFocusChanged { returnFocused = it.isFocused },
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        androidx.tv.material3.Text(stringResource(R.string.return_to_home))
                    }
                }
                if (state.confirmationPending) {
                    AlertDialog(
                        onDismissRequest = controller::cancelManualExit,
                        title = { Text(stringResource(R.string.kiosk_exit_title)) },
                        text = { Text(stringResource(R.string.kiosk_exit_message)) },
                        confirmButton = {
                            TextButton(onClick = controller::confirmManualExit) {
                                Text(stringResource(R.string.kiosk_exit_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = controller::cancelManualExit) {
                                Text(stringResource(R.string.kiosk_exit_cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Caller defines active playback (including buffering if desired); this never controls playback. */
@Composable
fun KioskPlayerSignals(playbackActive: Boolean, focusRequester: FocusRequester) {
    val controller = LocalKioskController.current
    val playerFocus = LocalPlayerFocus.current
    DisposableEffect(controller, playerFocus, focusRequester) {
        playerFocus?.value = focusRequester
        controller?.setPlayerPresent(true)
        onDispose {
            playerFocus?.let { if (it.value === focusRequester) it.value = null }
            controller?.setPlayerPresent(false)
        }
    }
    LaunchedEffect(controller, playbackActive) {
        controller?.setPlaybackActive(playbackActive)
    }
}
