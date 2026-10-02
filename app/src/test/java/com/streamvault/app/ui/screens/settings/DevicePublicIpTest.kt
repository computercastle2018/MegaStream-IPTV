package com.MegaStream.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePublicIpTest {
    @Test
    fun extractsOnlyOneNumericPublicAddressFromBoundedTrace() {
        assertEquals("8.8.8.8", parseCloudflarePublicIp("fl=abc\nip=8.8.8.8\ncolo=LHR\n"))
        assertEquals("2606:4700::1111", parseCloudflarePublicIp("ip=2606:4700::1111\r\n"))
        listOf("", "ip=example.com", "ip=999.1.1.1", "ip=08.8.8.8", "ip=127.0.0.1", "ip=192.168.1.1",
            "ip=::1", "ip=fe80::1", "ip=fd00::1", "ip=224.0.0.1", "ip=8.8.8.8\nip=1.1.1.1",
            "ip=8.8.8.8\n" + "x".repeat(4_096)).forEach { assertNull(it.take(80), parseCloudflarePublicIp(it)) }
    }
}
