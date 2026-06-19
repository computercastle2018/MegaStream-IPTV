package com.MegaStream.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.MegaStream.app.ui.design.AppColors
import com.MegaStream.app.ui.design.AppShapes
import com.MegaStream.app.ui.design.LocalAppShapes
import com.MegaStream.app.ui.design.LocalAppSpacing
import com.MegaStream.app.ui.design.rememberAppTypography
import com.MegaStream.app.ui.model.AppUiStyle

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = OnPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    background = AppColors.CanvasElevated,
    onBackground = AppColors.TextPrimary,
    error = AppColors.Live,
    onError = OnPrimary
)

private val ModernColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF64D2FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF02131B),
    surface = androidx.compose.ui.graphics.Color(0xFF111822),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF3F7FA),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1A2532),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB8C8D6),
    background = androidx.compose.ui.graphics.Color(0xFF070B10),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF3F7FA),
    secondary = androidx.compose.ui.graphics.Color(0xFF7BE4B6),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF02130D),
    tertiary = androidx.compose.ui.graphics.Color(0xFFFFCF6E),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF1E1300),
    error = androidx.compose.ui.graphics.Color(0xFFFF6B7A),
    onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
)

@Composable
fun MegaStreamTheme(
    uiStyle: AppUiStyle = AppUiStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val typography = rememberAppTypography()
    CompositionLocalProvider(
        LocalAppSpacing provides com.MegaStream.app.ui.design.AppSpacing(),
        LocalAppShapes provides AppShapes()
    ) {
        MaterialTheme(
            colorScheme = when (uiStyle) {
                AppUiStyle.CLASSIC -> DarkColorScheme
                AppUiStyle.MODERN -> ModernColorScheme
            },
            typography = typography,
            content = content
        )
    }
}
