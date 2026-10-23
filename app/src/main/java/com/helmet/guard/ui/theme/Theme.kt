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
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val GuardBlue = Color(0xFF2764FF)
val GuardCyan = Color(0xFF087F86)
val GuardGreen = Color(0xFF13845B)
val GuardOrange = Color(0xFFA45A05)
val GuardRed = Color(0xFFD92D4C)
val Ink = Color(0xFF10213D)
val Cloud = Color(0xFFF4F7FC)

private val LightColors = lightColorScheme(
    primary = GuardBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF), onPrimaryContainer = Color(0xFF153775),
    secondary = GuardCyan, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F2F3), onSecondaryContainer = Color(0xFF064E54),
    tertiary = GuardOrange, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEAD1), onTertiaryContainer = Color(0xFF643600),
    error = GuardRed, onError = Color.White,
    errorContainer = Color(0xFFFFE2E7), onErrorContainer = Color(0xFF8F102A),
    background = Cloud, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFE8EEF8), onSurfaceVariant = Color(0xFF52627A),
    surfaceTint = GuardBlue,
    surfaceDim = Color(0xFFE1E7F1), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FD),
    surfaceContainer = Color(0xFFF0F4FB), surfaceContainerHigh = Color(0xFFE9EFF8),
    surfaceContainerHighest = Color(0xFFE2EAF5),
    outline = Color(0xFF76869F), outlineVariant = Color(0xFFD9E2EF),
    inverseSurface = Color(0xFF20304A), inverseOnSurface = Color(0xFFF1F5FC),
    inversePrimary = Color(0xFFB0C5FF), scrim = Color(0xFF071326)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C0FF), onPrimary = Color(0xFF002D73),
    primaryContainer = Color(0xFF203F76), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF78D9DF), onSecondary = Color(0xFF003A40),
    secondaryContainer = Color(0xFF164B53), onSecondaryContainer = Color(0xFFB9F2F3),
    tertiary = Color(0xFFFFBE78), onTertiary = Color(0xFF4C2800),
    tertiaryContainer = Color(0xFF603D19), onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFFFB2BE), onError = Color(0xFF670D23),
    errorContainer = Color(0xFF702339), onErrorContainer = Color(0xFFFFD9E0),
    background = Color(0xFF091321), onBackground = Color(0xFFE6EDF8),
    surface = Color(0xFF111E30), onSurface = Color(0xFFE6EDF8),
    surfaceVariant = Color(0xFF243349), onSurfaceVariant = Color(0xFFB5C4DB),
    surfaceTint = Color(0xFFA8C0FF),
    surfaceDim = Color(0xFF091321), surfaceBright = Color(0xFF303F55),
    surfaceContainerLowest = Color(0xFF070F1B), surfaceContainerLow = Color(0xFF101B2C),
    surfaceContainer = Color(0xFF152237), surfaceContainerHigh = Color(0xFF1E2C42),
    surfaceContainerHighest = Color(0xFF28374D),
    outline = Color(0xFF8394AE), outlineVariant = Color(0xFF34445D),
    inverseSurface = Color(0xFFE6EDF8), inverseOnSurface = Ink,
    inversePrimary = Color(0xFF2457C6), scrim = Color.Black
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
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = GuardTypography, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(26.dp)
    ), content = content)
}
