package com.MegaStream.player.tracks

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedPlaybackQualityPreferencesTest {
    @Test
    fun cachedServerQuality_decodesAllowlistedCapsAndAutoWithoutAnAdminCap() {
        val cases = mapOf(null to null, "auto" to null, "480" to 480, "720" to 720,
            "1080" to 1080, "2160" to 2160, "invalid" to null, " 1080" to null)
        for ((quality, expectedHeight) in cases) {
            val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
                if (method.name != "getString") throw UnsupportedOperationException(method.name)
                if (arguments?.first() == "playback-quality") quality else null
            } as SharedPreferences
            assertEquals(quality, expectedHeight, ManagedPlaybackQualityPreferences.readMaxHeight(preferences))
        }
    }
}
