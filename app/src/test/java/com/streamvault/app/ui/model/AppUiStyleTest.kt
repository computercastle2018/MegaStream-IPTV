package com.MegaStream.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUiStyleTest {
    @Test
    fun storedTemplatesKeepTheirIdentityAndUnknownValuesUseClassic() {
        AppUiStyle.entries.forEach { style ->
            assertEquals(style, AppUiStyle.fromStorage(style.storageValue))
        }
        assertEquals(AppUiStyle.CLASSIC, AppUiStyle.fromStorage(null))
        assertEquals(AppUiStyle.CLASSIC, AppUiStyle.fromStorage("unknown"))
    }
}
