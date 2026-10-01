package com.MegaStream.app.kiosk.integration

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.MegaStream.app.device.rememberIsTelevisionDevice
import kotlinx.coroutines.delay

val LocalKioskController = staticCompositionLocalOf<KioskController?> { null }

@Composable
fun KioskHost(
    controller: KioskController,
    inPictureInPicture: Boolean,
    content: @Composable () -> Unit,
) {
    val state by controller.uiState.collectAsState()
    val exitFocus = remember { FocusRequester() }
    val isTelevision = rememberIsTelevisionDevice()
    val showExit = state.playerPresent && state.allowLocalExit && state.foreground && !inPictureInPicture
    var interaction by remember { mutableStateOf(0L) }
    var exitVisible by remember { mutableStateOf(false) }
    var exitFocused by remember { mutableStateOf(false) }
    var focusExit by remember { mutableStateOf(false) }
    fun revealExit() {
        exitVisible = true
        interaction++
    }
    LaunchedEffect(showExit) { exitVisible = false }
    LaunchedEffect(interaction, exitFocused, state.confirmationPending) {
        if (!exitFocused && !state.confirmationPending) {
            delay(4000)
            exitVisible = false
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
    CompositionLocalProvider(LocalKioskController provides controller) {
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
                    if (showExit && event.type in listOf(PointerEventType.Move, PointerEventType.Scroll, PointerEventType.Press)) {
                        revealExit()
                    }
                }
            }
        }) {
            content()
            if (showExit) {
                if (exitVisible) {
                    Button(
                        onClick = controller::beginManualExit,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).focusRequester(exitFocus)
                            .onFocusChanged { exitFocused = it.isFocused },
                    ) {
                        Text(stringResource(if (isTelevision) R.string.kiosk_manual_exit_hint else R.string.kiosk_manual_exit))
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
fun KioskPlayerSignals(playbackActive: Boolean) {
    val controller = LocalKioskController.current
    DisposableEffect(controller) {
        controller?.setPlayerPresent(true)
        onDispose { controller?.setPlayerPresent(false) }
    }
    LaunchedEffect(controller, playbackActive) {
        controller?.setPlaybackActive(playbackActive)
    }
}
