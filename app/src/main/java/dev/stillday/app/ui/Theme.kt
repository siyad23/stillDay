package dev.stillday.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.stillday.domain.ThemeChoice

internal fun stilldayColorScheme(theme: ThemeChoice, systemDark: Boolean): ColorScheme = when (theme) {
    ThemeChoice.CHARCOAL -> darkColorScheme(primary = Color(0xFFA9C5B0), onPrimary = Color(0xFF183824),
        background = Color(0xFF151917), surface = Color(0xFF151917), surfaceContainer = Color(0xFF202622),
        surfaceVariant = Color(0xFF303A33), onSurface = Color(0xFFE3E9E2), onSurfaceVariant = Color(0xFFB6C1B7), outlineVariant = Color(0xFF364038))
    ThemeChoice.MIDNIGHT -> darkColorScheme(primary = Color(0xFFB4C5DA), onPrimary = Color(0xFF1F344C),
        background = Color(0xFF12171E), surface = Color(0xFF12171E), surfaceContainer = Color(0xFF1D2530),
        surfaceVariant = Color(0xFF2C3745), onSurface = Color(0xFFE2E7EF), onSurfaceVariant = Color(0xFFB7C1CE), outlineVariant = Color(0xFF354150))
    ThemeChoice.SAND -> lightColorScheme(primary = Color(0xFF73654B), onPrimary = Color.White,
        background = Color(0xFFF7F3EB), surface = Color(0xFFF7F3EB), surfaceContainer = Color(0xFFEEEBE2),
        surfaceVariant = Color(0xFFE7E2D5), onSurface = Color(0xFF302F29), onSurfaceVariant = Color(0xFF6B685D), outlineVariant = Color(0xFFDBD6C9))
    ThemeChoice.PAPER -> lightColorScheme(primary = Color(0xFF536F5E), onPrimary = Color.White,
        background = Color(0xFFF8F9F5), surface = Color(0xFFF8F9F5), surfaceContainer = Color(0xFFEEF1E9),
        surfaceVariant = Color(0xFFE2E9DF), onSurface = Color(0xFF242C26), onSurfaceVariant = Color(0xFF626F65), outlineVariant = Color(0xFFD7DFD4))
    ThemeChoice.SYSTEM -> stilldayColorScheme(if (systemDark) ThemeChoice.CHARCOAL else ThemeChoice.PAPER, systemDark)
}

@Composable fun StilldayTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val base = stilldayColorScheme(choice, isSystemInDarkTheme())
    val colors = base.copy(secondary = base.primary, onSecondary = base.onPrimary,
        secondaryContainer = base.surfaceVariant, onSecondaryContainer = base.onSurface,
        tertiary = base.primary, onTertiary = base.onPrimary,
        tertiaryContainer = base.surfaceVariant, onTertiaryContainer = base.onSurface,
        outline = base.onSurfaceVariant.copy(alpha = 0.65f),
        inverseSurface = base.onSurface, inverseOnSurface = base.background, inversePrimary = base.primary)
    val context = LocalContext.current
    SideEffect {
        (context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = colors.background.luminance() > 0.5f
                isAppearanceLightNavigationBars = colors.background.luminance() > 0.5f
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 39.sp),
        headlineLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 38.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 27.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    ), content = content)
}
