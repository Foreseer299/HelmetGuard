package com.helmet.guard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

val GuardBlue = Color(0xFF2764FF)
val GuardCyan = Color(0xFF087F86)
val GuardGreen = Color(0xFF13845B)
val GuardOrange = Color(0xFFA45A05)
val GuardRed = Color(0xFFD92D4C)
val Ink = Color(0xFF10213D)
val Cloud = Color(0xFFF4F7FC)

private val LightColors = lightColorScheme(
    primary = GuardBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF082968),
    secondary = Color(0xFF087F86),
    secondaryContainer = Color(0xFFC2F2F2),
    tertiary = GuardOrange,
    error = GuardRed,
    background = Cloud,
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF8),
    onBackground = Ink,
    onSurface = Ink,
    outline = Color(0xFFAAB8CF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB7FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF17499F),
    secondary = Color(0xFF62D9DB),
    tertiary = Color(0xFFFFB968),
    error = Color(0xFFFFB2BA),
    background = Color(0xFF09111F),
    surface = Color(0xFF111C2E),
    surfaceVariant = Color(0xFF1C2A40),
    onBackground = Color(0xFFE5ECF8),
    onSurface = Color(0xFFE5ECF8),
    outline = Color(0xFF7D8CA5)
)

private val GuardTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

@Composable
fun HelmetGuardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = GuardTypography, content = content)
}
