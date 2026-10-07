package com.mokamusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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

private val MokaDarkColors = darkColorScheme(
    primary = Color(0xFFD69B6A),
    onPrimary = Color(0xFF2B1609),
    primaryContainer = Color(0xFF57351F),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFDDB89C),
    onSecondary = Color(0xFF3D2A1F),
    secondaryContainer = Color(0xFF4D392C),
    onSecondaryContainer = Color(0xFFFBE0CC),
    tertiary = Color(0xFFCDB5AC),
    onTertiary = Color(0xFF352824),
    background = Color(0xFF120E0D),
    onBackground = Color(0xFFF1E7E2),
    surface = Color(0xFF171210),
    onSurface = Color(0xFFF1E7E2),
    surfaceVariant = Color(0xFF2A211E),
    onSurfaceVariant = Color(0xFFD5C3BA),
    outline = Color(0xFF8E7A70),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val MokaLightColors = lightColorScheme(
    primary = Color(0xFF825124),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2A1708),
    secondary = Color(0xFF725A49),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC5),
    onSecondaryContainer = Color(0xFF29180D),
    tertiary = Color(0xFF6D5C55),
    onTertiary = Color.White,
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF211A17),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF211A17),
    surfaceVariant = Color(0xFFF2E3DC),
    onSurfaceVariant = Color(0xFF51443E),
    outline = Color(0xFF84736B),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val MokaTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

@Composable
fun MokaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MokaDarkColors else MokaLightColors,
        typography = MokaTypography,
        content = content
    )
}
