package com.MegaStream.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.MegaStream.app.ui.design.AppColors
import com.MegaStream.app.ui.design.AppShapes
import com.MegaStream.app.ui.design.LocalAppShapes
import com.MegaStream.app.ui.design.LocalAppSpacing
import com.MegaStream.app.ui.design.rememberAppTypography
import com.MegaStream.app.ui.model.AppUiStyle

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.CLASSIC }
val LocalAppUiStyleManaged = staticCompositionLocalOf { false }

private val StudioColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF69DCB1),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF09221B),
    background = androidx.compose.ui.graphics.Color(0xFF101414),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF3F5F2),
    surface = androidx.compose.ui.graphics.Color(0xFF191F1E),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF3F5F2),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252E2B),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB9C8C1),
    secondary = androidx.compose.ui.graphics.Color(0xFFECCB7B),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF292012),
    tertiary = androidx.compose.ui.graphics.Color(0xFF9DCEE2),
    error = androidx.compose.ui.graphics.Color(0xFFFF7C87)
)

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
    uiStyleManaged: Boolean = false,
    content: @Composable () -> Unit
) {
    val typography = rememberAppTypography()
    CompositionLocalProvider(
        LocalAppUiStyle provides uiStyle,
        LocalAppUiStyleManaged provides uiStyleManaged,
        LocalAppSpacing provides com.MegaStream.app.ui.design.AppSpacing(),
        LocalAppShapes provides if (uiStyle == AppUiStyle.STUDIO) AppShapes(
            small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp), xSmall = RoundedCornerShape(4.dp),
            xLarge = RoundedCornerShape(8.dp), pill = RoundedCornerShape(6.dp)
        ) else AppShapes()
    ) {
        MaterialTheme(
            colorScheme = when (uiStyle) {
                AppUiStyle.CLASSIC -> DarkColorScheme
                AppUiStyle.MODERN -> ModernColorScheme
                AppUiStyle.STUDIO -> StudioColorScheme
            },
            typography = typography,
            content = content
        )
    }
}
