package com.e2eechat.desktop.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The full Telegram Day/Night palette, ported one-to-one from the Swing client's `ui/Theme.java`
 * so the Compose front end is visually continuous with it. Every colour a component draws comes
 * from here — there are no literal `Color(...)` values in the UI code, the same discipline the
 * Swing theme enforces.
 */
data class AppColors(
    val pageBg: Color,
    val sidebarBg: Color,
    val sidebarHover: Color,
    val sidebarSelected: Color,
    val sidebarSelectedText: Color,
    val headerBg: Color,
    val divider: Color,
    val chatBg: Color,
    val bubbleIn: Color,
    val bubbleOut: Color,
    val bubbleError: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val timeIn: Color,
    val timeOut: Color,
    val tick: Color,
    val accent: Color,
    val accentHover: Color,
    val badge: Color,
    val badgeText: Color,
    val inputBg: Color,
    val composerBg: Color,
    val icon: Color,
    val iconHover: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightColors = AppColors(
    pageBg = Color(0xFFF4F6FA),
    sidebarBg = Color(0xFFF7F9FC),
    sidebarHover = Color(0xFFEFF2F8),
    sidebarSelected = Color(0xFFFFFFFF),
    sidebarSelectedText = Color(0xFF111827),
    headerBg = Color(0xFFFFFFFF),
    divider = Color(0xFFE9EDF3),
    chatBg = Color(0xFFFFFFFF),
    bubbleIn = Color(0xFFF1F4F9),
    bubbleOut = Color(0xFFD9E6FE),
    bubbleError = Color(0xFFFDE8E8),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF8B95A7),
    timeIn = Color(0xFF9AA4B5),
    timeOut = Color(0xFF6B8FCB),
    tick = Color(0xFF2F6FED),
    accent = Color(0xFF1668FF),
    accentHover = Color(0xFF0D57DB),
    badge = Color(0xFFF4756B),
    badgeText = Color(0xFFFFFFFF),
    inputBg = Color(0xFFF1F4F9),
    composerBg = Color(0xFFFFFFFF),
    icon = Color(0xFF8B95A7),
    iconHover = Color(0xFF4B5568),
    danger = Color(0xFFE5484D),
    isDark = false,
)

val DarkColors = AppColors(
    pageBg = Color(0xFF0B111C),
    sidebarBg = Color(0xFF121A27),
    sidebarHover = Color(0xFF1A2433),
    sidebarSelected = Color(0xFF223047),
    sidebarSelectedText = Color(0xFFFFFFFF),
    headerBg = Color(0xFF121A27),
    divider = Color(0xFF1F2937),
    chatBg = Color(0xFF0E1622),
    bubbleIn = Color(0xFF1C2634),
    bubbleOut = Color(0xFF2B4B7D),
    bubbleError = Color(0xFF4A2226),
    textPrimary = Color(0xFFE9EEF6),
    textSecondary = Color(0xFF8B95A7),
    timeIn = Color(0xFF71809A),
    timeOut = Color(0xFF9DB8E0),
    tick = Color(0xFF63A0F5),
    accent = Color(0xFF4D8DFF),
    accentHover = Color(0xFF3D7AE8),
    badge = Color(0xFFF4756B),
    badgeText = Color(0xFFFFFFFF),
    inputBg = Color(0xFF1A2433),
    composerBg = Color(0xFF121A27),
    icon = Color(0xFF8B95A7),
    iconHover = Color(0xFFC3CCDA),
    danger = Color(0xFFEF5350),
    isDark = true,
)

/** Access the active palette from any composable: `LocalAppColors.current`. */
val LocalAppColors = staticCompositionLocalOf { DarkColors }

/**
 * Wraps content in the chosen palette and a Material 3 scheme derived from it, so stock Material
 * components (text selection handles, the caret, ripples) match the hand-drawn surfaces rather than
 * a neighbouring default.
 */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.pageBg,
            onBackground = colors.textPrimary,
            surface = colors.sidebarBg,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.inputBg,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.pageBg,
            onBackground = colors.textPrimary,
            surface = colors.sidebarBg,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.inputBg,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
