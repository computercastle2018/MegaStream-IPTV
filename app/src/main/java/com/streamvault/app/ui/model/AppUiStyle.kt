package com.MegaStream.app.ui.model

enum class AppUiStyle(
    val storageValue: String,
    val labelResId: Int,
    val descriptionResId: Int
) {
    CLASSIC(
        storageValue = "classic",
        labelResId = com.MegaStream.app.R.string.settings_app_ui_style_classic,
        descriptionResId = com.MegaStream.app.R.string.settings_app_ui_style_classic_desc
    ),
    MODERN(
        storageValue = "modern",
        labelResId = com.MegaStream.app.R.string.settings_app_ui_style_modern,
        descriptionResId = com.MegaStream.app.R.string.settings_app_ui_style_modern_desc
    );

    companion object {
        fun fromStorage(value: String?): AppUiStyle =
            entries.firstOrNull { it.storageValue == value?.lowercase() || it.name == value } ?: CLASSIC
    }
}
