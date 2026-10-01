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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.MegaStream.app.R
import com.MegaStream.app.device.rememberIsTelevisionDevice

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
    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) controller.cancelManualExit()
    }
    CompositionLocalProvider(LocalKioskController provides controller) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (showExit && !state.confirmationPending && native.keyCode == KeyEvent.KEYCODE_MENU &&
                native.action == KeyEvent.ACTION_DOWN && (native.isLongPress || native.repeatCount > 0)) {
                exitFocus.requestFocus()
                true
            } else {
                false
            }
        }) {
            content()
            if (showExit) {
                Button(
                    onClick = controller::beginManualExit,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).focusRequester(exitFocus),
                ) {
                    Text(stringResource(if (isTelevision) R.string.kiosk_manual_exit_hint else R.string.kiosk_manual_exit))
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
