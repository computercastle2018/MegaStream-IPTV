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
    primary = androidx.compose.ui.graphics.Color(0xFFFF4053),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    background = androidx.compose.ui.graphics.Color(0xFF100608),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5ECEE),
    surface = androidx.compose.ui.graphics.Color(0xFF1D1115),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5ECEE),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A1A1F),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC3A6AC),
    secondary = androidx.compose.ui.graphics.Color(0xFFECCB7B),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF292012),
    tertiary = androidx.compose.ui.graphics.Color(0xFFFF4053),
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
    primary = androidx.compose.ui.graphics.Color(0xFFE01B32),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surface = androidx.compose.ui.graphics.Color(0xFF1D1115),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5ECEE),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A1A1F),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC3A6AC),
    background = androidx.compose.ui.graphics.Color(0xFF100608),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5ECEE),
    secondary = androidx.compose.ui.graphics.Color(0xFFFF4053),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2A0A12),
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
