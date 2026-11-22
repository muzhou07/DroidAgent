package com.muzhou.droidagent.ui.theme

import com.muzhou.droidagent.data.ThemeMode
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

@Immutable
data class DsColors(
    val dark: Boolean,
    val background: Color,
    val sidebar: Color,
    val layer: Color,
    val card: Color,
    val search: Color,
    val input: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val brand: Color,
    val brandSoft: Color,
    val userBubble: Color,
    val codeBackground: Color,
    val codeHeader: Color,
    val inlineCode: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val divider: Color,
    val scrim: Color,
)

val LightDsColors = DsColors(
    dark = false,
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF9FAFB),
    layer = Color(0xFFFFFFFF),
    card = Color(0xFFF5F6F7),
    search = Color(0xFFE8EAED),
    input = Color(0xFFFFFFFF),
    border = Color(0x1A0F1115),
    borderStrong = Color(0x290F1115),
    textPrimary = Color(0xFF0F1115),
    textSecondary = Color(0xFF61666B),
    textTertiary = Color(0xFF8E9399),
    brand = Color(0xFF3964FE),
    brandSoft = Color(0xFFEDF3FE),
    userBubble = Color(0xFFF3F4F6),
    codeBackground = Color(0xFFF9FAFB),
    codeHeader = Color(0xFFF1F3F5),
    inlineCode = Color(0xFFF1F3F5),
    danger = Color(0xFFEC1313),
    success = Color(0xFF16A34A),
    warning = Color(0xFFDD8629),
    divider = Color(0x140F1115),
    scrim = Color(0x66000000),
)

val DarkDsColors = DsColors(
    dark = true,
    background = Color(0xFF151517),
    sidebar = Color(0xFF1B1B1C),
    layer = Color(0xFF1F1F21),
    card = Color(0xFF2C2C2E),
    search = Color(0xFF3A3A3D),
    input = Color(0xFF2C2C2E),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x33FFFFFF),
    textPrimary = Color(0xFFF9FAFB),
    textSecondary = Color(0xFFCFD3D6),
    textTertiary = Color(0xFF8E9399),
    brand = Color(0xFF5661FE),
    brandSoft = Color(0xFF283142),
    userBubble = Color(0xFF262628),
    codeBackground = Color(0xFF1B1B1C),
    codeHeader = Color(0xFF262628),
    inlineCode = Color(0xFF2C2C2E),
    danger = Color(0xFFF25A5A),
    success = Color(0xFF4ED17E),
    warning = Color(0xFFF7AD31),
    divider = Color(0x1FFFFFFF),
    scrim = Color(0x99000000),
)

val LocalDsColors = staticCompositionLocalOf { LightDsColors }

object DsTheme {
    val colors: DsColors
        @Composable get() = LocalDsColors.current
}

private val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
)

@Composable
fun DroidAgentTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkDsColors else LightDsColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.brand,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.card,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.card,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDsColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
