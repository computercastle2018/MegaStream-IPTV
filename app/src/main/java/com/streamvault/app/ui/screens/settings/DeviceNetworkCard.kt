package com.MegaStream.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.dialogs.PremiumDialog
import com.MegaStream.app.ui.components.dialogs.PremiumDialogActionButton
import com.MegaStream.app.ui.components.dialogs.PremiumDialogFooterButton
import com.MegaStream.app.ui.theme.OnSurface
import com.MegaStream.app.ui.theme.OnSurfaceDim
import com.MegaStream.app.ui.theme.Primary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceNetworkCard(speedTestContent: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DeviceMacAddressStore(context) }
    val scope = rememberCoroutineScope()
    var savedMac by remember { mutableStateOf<DeviceMacAddress?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        savedMac = withContext(Dispatchers.IO) { store.read() }
        loaded = true
    }
    Column {
        ClickableSettingsRow(
            label = stringResource(R.string.network_details_title),
            value = stringResource(R.string.network_details_open),
            onClick = { showDetails = true }
        )
        NetworkDetailValue(R.string.network_saved_mac, savedMac?.address, true)
        if (savedMac != null) Text(stringResource(R.string.network_mac_manual), color = OnSurfaceDim)
    }
    if (showDetails) {
        var details by remember { mutableStateOf(DeviceNetworkDetails()) }
        var refreshing by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf(savedMac?.address.orEmpty()) }
        var saveError by remember { mutableStateOf(false) }
        var publicIp by remember { mutableStateOf<String?>(null) }
        var publicIpChecked by remember { mutableStateOf(false) }
        var checkingPublicIp by remember { mutableStateOf(false) }
        val invalidInput = input.isNotBlank() && normalizeDeviceMacAddress(input) == null
        fun refresh() {
            if (refreshing) return
            scope.launch {
                refreshing = true
                try {
                    details = withContext(Dispatchers.IO) { readDeviceNetworkDetails(context) }
                    publicIp = null
                    publicIpChecked = false
                } finally {
                    refreshing = false
                }
            }
        }
        LaunchedEffect(Unit) { refresh() }
        LaunchedEffect(loaded) { if (loaded) input = savedMac?.address.orEmpty() }
        fun checkPublicIp() {
            if (checkingPublicIp || refreshing) return
            scope.launch {
                checkingPublicIp = true
                try {
                    publicIp = withContext(Dispatchers.IO) { readDevicePublicIp(context) }
                    publicIpChecked = true
                } finally {
                    checkingPublicIp = false
                }
            }
        }
        fun persist(clear: Boolean) {
            if (saving || !loaded) return
            saving = true
            saveError = false
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { if (clear) { store.clear(); null } else store.saveManual(input) }
                }
                result.onSuccess {
                    savedMac = it
                    input = it?.address.orEmpty()
                }.onFailure { saveError = true }
                saving = false
            }
        }
        PremiumDialog(
            title = stringResource(R.string.network_details_title),
            onDismissRequest = { showDetails = false },
            widthFraction = 0.7f,
            bodyHeightFraction = 0.65f,
            content = {
                NetworkDetailValue(R.string.network_transport, details.transports
                    .joinToString(" / ") { context.getString(it.labelResource()) }.ifEmpty { null })
                NetworkDetailValue(R.string.network_validation, stringResource(details.validation.labelResource()))
                NetworkDetailValue(R.string.network_local_ip, details.localAddresses.joinToString("\n").ifEmpty { null }, true)
                NetworkDetailValue(R.string.network_dns, details.dnsServers.joinToString("\n").ifEmpty { null }, true)
                NetworkDetailValue(R.string.network_gateway, details.gateways.joinToString("\n").ifEmpty { null }, true)
                NetworkDetailValue(R.string.network_public_ip, publicIp ?: stringResource(
                    if (publicIpChecked) R.string.network_public_ip_failed else R.string.network_public_ip_not_checked
                ), publicIp != null)
                PremiumDialogActionButton(
                    label = stringResource(R.string.network_public_ip_lookup), onClick = ::checkPublicIp,
                    enabled = !checkingPublicIp && !refreshing
                )
                NetworkDetailValue(R.string.network_isp, details.cellularOperator ?: stringResource(R.string.network_isp_unavailable))
                if (details.cellularOperator != null) Text(stringResource(R.string.network_isp_cellular_source), color = OnSurfaceDim)
                NetworkDetailValue(R.string.network_readable_mac, details.readableMac?.address, true)
                if (details.readableMac != null) Text(stringResource(R.string.network_mac_readable), color = OnSurfaceDim)
                PremiumDialogActionButton(
                    label = stringResource(R.string.network_refresh), onClick = ::refresh, enabled = !refreshing && !checkingPublicIp
                )
                NetworkDetailValue(R.string.network_speed_provider, "Cloudflare")
                speedTestContent()
                Text(stringResource(R.string.network_mac_limit), color = OnSurfaceDim, style = MaterialTheme.typography.bodySmall)
                NetworkDetailValue(R.string.network_saved_mac, savedMac?.address, true)
                if (savedMac != null) Text(stringResource(R.string.network_mac_manual), color = OnSurfaceDim)
                Text(stringResource(R.string.network_manual_mac), color = OnSurface)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(64); saveError = false },
                        enabled = loaded && !saving,
                        singleLine = true,
                        isError = invalidInput,
                        placeholder = { androidx.compose.material3.Text("00:1A:79:12:34:56") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
                            focusedBorderColor = Primary, unfocusedBorderColor = OnSurfaceDim
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (invalidInput) Text(stringResource(R.string.network_mac_invalid), color = MaterialTheme.colorScheme.error)
                if (saveError) Text(stringResource(R.string.network_mac_save_error), color = MaterialTheme.colorScheme.error)
                PremiumDialogActionButton(
                    label = stringResource(R.string.network_mac_save),
                    onClick = { persist(false) },
                    enabled = loaded && !saving && normalizeDeviceMacAddress(input) != null,
                    emphasized = true
                )
                PremiumDialogActionButton(
                    label = stringResource(R.string.network_mac_clear),
                    onClick = { persist(true) },
                    enabled = loaded && !saving && savedMac != null
                )
            },
            footer = {
                PremiumDialogFooterButton(stringResource(R.string.network_close), onClick = { showDetails = false })
            }
        )
    }
}

@Composable
private fun NetworkDetailValue(label: Int, value: String?, ltr: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(label), color = OnSurfaceDim, style = MaterialTheme.typography.labelMedium)
        val displayedValue = value ?: stringResource(R.string.network_unavailable)
        CompositionLocalProvider(LocalLayoutDirection provides if (ltr && value != null) LayoutDirection.Ltr else LocalLayoutDirection.current) {
            Text(displayedValue, color = OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun DeviceNetworkTransport.labelResource(): Int = when (this) {
    DeviceNetworkTransport.WIFI -> R.string.network_wifi
    DeviceNetworkTransport.ETHERNET -> R.string.network_ethernet
    DeviceNetworkTransport.CELLULAR -> R.string.network_cellular
    DeviceNetworkTransport.VPN -> R.string.network_vpn
    DeviceNetworkTransport.OTHER -> R.string.network_other
}

private fun DeviceNetworkValidation.labelResource(): Int = when (this) {
    DeviceNetworkValidation.VALIDATED -> R.string.network_validated
    DeviceNetworkValidation.CAPTIVE_PORTAL -> R.string.network_captive_portal
    DeviceNetworkValidation.NOT_VALIDATED -> R.string.network_not_validated
    DeviceNetworkValidation.DISCONNECTED -> R.string.network_disconnected
    DeviceNetworkValidation.UNAVAILABLE -> R.string.network_unavailable
}
