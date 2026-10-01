package com.MegaStream.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.playback.gate.PlaybackGateVerdict
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.screens.license.LicenseActivationScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LicenseActivationRoute(
    navigation: LicenseNavigationViewModel,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Not a retained hiltViewModel: the existing screen disposes this instance on every exit,
    // including configuration recreation. Pending navigation remains in the activity-owned VM.
    val activation = remember(navigation, scope) { navigation.createActivationModel(scope) }
    val verdict by navigation.decision.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    LaunchedEffect(verdict) {
        if (verdict is PlaybackGateVerdict.Allowed) onContinue()
    }
    LaunchedEffect(activation, navigation) {
        navigation.decision.collect { activation.updateEntitlement(it.asLicenseEntitlement()) }
    }
    LaunchedEffect(activation, navigation.runtimeSnapshot) {
        navigation.runtimeSnapshot.collect {
            // Runtime publishes verified domain decisions. Re-evaluate at observation time rather
            // than letting a stale runtime observation overwrite a newer local activation decision.
            activation.updateEntitlement(navigation.verifiedEntitlement())
        }
    }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(onClick = onCancel) { Text(stringResource(R.string.license_nav_cancel)) }
            TvButton(enabled = !refreshing, onClick = {
                refreshing = true
                refreshFailed = false
                scope.launch {
                    try {
                        refreshFailed = navigation.refreshLicense() !is PlaybackGateVerdict.Allowed
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        refreshFailed = true
                    } finally {
                        refreshing = false
                    }
                }
            }) { Text(stringResource(if (refreshing) R.string.license_refreshing else R.string.license_refresh)) }
            TvButton(onClick = onContinue, enabled = verdict is PlaybackGateVerdict.Allowed) {
                Text(stringResource(R.string.license_nav_continue))
            }
        }
        if (refreshFailed) Text(stringResource(R.string.license_refresh_failed),
            color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp))
        LicenseActivationScreen(activation, Modifier.weight(1f))
    }
}
