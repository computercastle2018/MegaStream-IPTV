package com.MegaStream.app.ui.theme

import com.MegaStream.app.ui.design.AppSpacing
import com.MegaStream.app.ui.design.LocalAppSpacing

typealias Spacing = AppSpacing

val LocalSpacing = LocalAppSpacing

fun defaultSpacing(): Spacing = AppSpacing()
