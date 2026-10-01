package com.MegaStream.app.controlplane

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ControlPlaneUrlPolicyTest {
    @Test fun exactOriginAndCanonicalDefaultPortAreAllowed() {
        listOf("https://megastrem.megastation.uk", "https://megastrem.megastation.uk:443/api/v1/test?x=1").forEach {
            assertTrue(it, ControlPlaneUrlPolicy.isAllowed(it))
            assertTrue(it, ControlPlaneUrlPolicy.isAllowed(it.toHttpUrl()))
        }
    }

    @Test fun rawAmbiguitiesAreRejectedBeforeCanonicalization() {
        listOf(
            "http://megastrem.megastation.uk", "//megastrem.megastation.uk", "/api/v1/test",
            "https:megastrem.megastation.uk", "https:///megastrem.megastation.uk",
            "https://megastrem.megastation.uk:444", "https://megastrem.megastation.uk:0443", "https://megastrem.megastation.uk:",
            "https://@megastrem.megastation.uk", "https://user@megastrem.megastation.uk", "https://megastrem.megastation.uk#",
            "https://megastrem.megastation.uk/#secret", "https://megastrem.megastation.uk\\@evil.example",
            "https://megastrem.megastation.uk.evil.example", "https://megastrem.megastation.uk.",
            "https://%6degastrem.megastation.uk", " https://megastrem.megastation.uk", "https://megastrem.megastation.uk\n",
            "https://MEGASTREM.megastation.uk", "HTTPS://megastrem.megastation.uk",
        ).forEach { assertFalse(it, ControlPlaneUrlPolicy.isAllowed(it)) }
    }

    @Test fun parsedForeignOriginsUserInfoAndFragmentsAreRejected() {
        listOf("http://megastrem.megastation.uk", "https://evil.example", "https://megastrem.megastation.uk:444",
            "https://user:password@megastrem.megastation.uk", "https://megastrem.megastation.uk/#").forEach {
            assertFalse(it, ControlPlaneUrlPolicy.isAllowed(it.toHttpUrl()))
        }
    }
}
