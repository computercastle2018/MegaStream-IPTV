package com.MegaStream.app.ui.screens.player.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.dialogs.PremiumDialog
import com.MegaStream.app.ui.components.dialogs.PremiumDialogActionButton
import com.MegaStream.app.ui.components.dialogs.LocalDialogCanInteract
import com.MegaStream.app.ui.design.requestFocusSafely

@Composable
internal fun PlayerRecoveryDialog(
    message: String,
    onRetry: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String = stringResource(R.string.player_retry),
    extraActions: @Composable () -> Unit = {}
) {
    val retryFocus = remember { FocusRequester() }
    PremiumDialog(
        title = stringResource(R.string.player_error_title),
        subtitle = message,
        onDismissRequest = {},
        modifier = modifier,
        widthFraction = 0.5f,
        heightFraction = null,
        content = {
            val canInteract = LocalDialogCanInteract.current
            LaunchedEffect(canInteract) {
                if (canInteract) {
                    retryFocus.requestFocusSafely(tag = "PlayerRecoveryDialog", target = "Retry")
                }
            }
            PremiumDialogActionButton(
                label = primaryLabel,
                onClick = onRetry,
                emphasized = true,
                modifier = Modifier.focusRequester(retryFocus)
            )
            extraActions()
            PremiumDialogActionButton(
                label = stringResource(R.string.player_recovery_home),
                onClick = onHome
            )
        }
    )
}
