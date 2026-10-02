package com.MegaStream.app.ui.screens.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.NetworkInterface
import java.util.Locale

internal enum class DeviceNetworkTransport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }
internal enum class DeviceNetworkValidation { VALIDATED, CAPTIVE_PORTAL, NOT_VALIDATED, DISCONNECTED, UNAVAILABLE }

internal data class DeviceNetworkDetails(
    val localAddresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val gateways: List<String> = emptyList(),
    val transports: List<DeviceNetworkTransport> = emptyList(),
    val validation: DeviceNetworkValidation = DeviceNetworkValidation.UNAVAILABLE,
    val readableMac: DeviceMacAddress? = null,
    val cellularOperator: String? = null
)

internal fun readDeviceNetworkDetails(context: Context): DeviceNetworkDetails = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return DeviceNetworkDetails()
    val network = manager.activeNetwork
        ?: return DeviceNetworkDetails(validation = DeviceNetworkValidation.DISCONNECTED)
    val capabilities = manager.getNetworkCapabilities(network)
    val links = manager.getLinkProperties(network)
    val transportTypes = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to DeviceNetworkTransport.WIFI,
        NetworkCapabilities.TRANSPORT_ETHERNET to DeviceNetworkTransport.ETHERNET,
        NetworkCapabilities.TRANSPORT_CELLULAR to DeviceNetworkTransport.CELLULAR,
        NetworkCapabilities.TRANSPORT_VPN to DeviceNetworkTransport.VPN
    )
    DeviceNetworkDetails(
        localAddresses = links?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress }.distinct(),
        dnsServers = links?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.distinct(),
        gateways = links?.routes.orEmpty().filter { it.isDefaultRoute && it.hasGateway() }
            .mapNotNull { it.gateway?.hostAddress }.distinct(),
        transports = if (capabilities == null) emptyList() else transportTypes
            .filter { capabilities.hasTransport(it.first) }.map { it.second }
            .ifEmpty { listOf(DeviceNetworkTransport.OTHER) },
        validation = when {
            capabilities == null -> DeviceNetworkValidation.UNAVAILABLE
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> DeviceNetworkValidation.CAPTIVE_PORTAL
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> DeviceNetworkValidation.VALIDATED
            else -> DeviceNetworkValidation.NOT_VALIDATED
        },
        readableMac = readInterfaceMac(links?.interfaceName),
        cellularOperator = if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            readCellularOperator(context)
        } else null
    )
}.getOrElse { DeviceNetworkDetails() }

private fun readCellularOperator(context: Context): String? = runCatching {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
    val dataTelephony = if (subscriptionId >= 0) telephony.createForSubscriptionId(subscriptionId) else telephony
    dataTelephony.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

private fun readInterfaceMac(interfaceName: String?): DeviceMacAddress? = runCatching {
    if (interfaceName == null) return null
    val bytes = NetworkInterface.getByName(interfaceName)?.hardwareAddress ?: return null
    if (bytes.size != 6) return null
    val address = normalizeDeviceMacAddress(bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xff) })
        ?: return null
    DeviceMacAddress(address, DeviceMacSource.READABLE)
}.getOrNull()
