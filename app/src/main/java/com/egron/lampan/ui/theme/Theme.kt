package com.egron.lampan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LampanLightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFE9),
    onPrimaryContainer = Color(0xFF083B36),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF1D1B17),
    surface = Color(0xFFFEFBF6),
    onSurface = Color(0xFF1D1B17),
    surfaceVariant = Color(0xFFEDE7DF),
    onSurfaceVariant = Color(0xFF4D443C),
    outline = Color(0xFFB7AFA7)
)

private val LampanDarkColors = darkColorScheme(
    primary = Color(0xFF3DD8C7),
    onPrimary = Color(0xFF082923),
    primaryContainer = Color(0xFF0C4038),
    onPrimaryContainer = Color(0xFFC8F4EE),
    secondary = Color(0xFF7FB3FF),
    onSecondary = Color(0xFF0D2344),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE4E1DC),
    surface = Color(0xFF141A18),
    onSurface = Color(0xFFE4E1DC),
    surfaceVariant = Color(0xFF2A3532),
    onSurfaceVariant = Color(0xFFB3C0BA),
    outline = Color(0xFF51605B)
)

private val LampanTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        letterSpacing = 0.2.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp
    )
)

@Composable
fun LampanTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) LampanDarkColors else LampanLightColors,
        typography = LampanTypography,
        content = content
    )
}
