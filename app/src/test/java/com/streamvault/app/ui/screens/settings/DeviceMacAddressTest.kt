package com.MegaStream.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceMacAddressTest {
    @Test
    fun normalizesManualAddressesWithoutDiscardingInput() {
        assertEquals("00:1A:79:12:34:56", normalizeDeviceMacAddress(" 00:1a:79:12:34:56 "))
        assertEquals("A2:1A:79:12:34:56", normalizeDeviceMacAddress("a2-1a-79-12-34-56"))
        listOf("", "001A79123456", "00:1A:79:12:34", "00:1A:79:12:34:5G", "00:1A-79:12:34:56",
            "00:1A:79:12:34:56extra", "00:1A:79:12:34:\n56").forEach {
            assertNull(it, normalizeDeviceMacAddress(it))
        }
    }

    @Test
    fun rejectsNonDeviceAndMaskedAddressesButAllowsLocallyAdministeredAddresses() {
        listOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00", "01:1A:79:12:34:56").forEach {
            assertNull(it, normalizeDeviceMacAddress(it))
        }
        assertEquals("02:1A:79:12:34:56", normalizeDeviceMacAddress("02:1A:79:12:34:56"))
    }
}
