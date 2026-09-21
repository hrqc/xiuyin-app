package com.xiuyin.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val XiuyinBackground = Color(0xFFFAF6EF)
val XiuyinNavy = Color(0xFF5A4236)
val XiuyinTeal = Color(0xFFA65F3F)
val XiuyinGold = Color(0xFFDFC6A4)
val XiuyinInk = Color(0xFF3F352D)
val XiuyinMuted = Color(0xFF74675B)
val XiuyinLine = Color(0xFFE5DACE)

private val XiuyinColors = lightColorScheme(
    primary = XiuyinTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4E1D3),
    onPrimaryContainer = Color(0xFF6D3C28),
    secondary = Color(0xFF5E7057),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EDDF),
    onSecondaryContainer = Color(0xFF3F5138),
    tertiary = Color(0xFF84613C),
    tertiaryContainer = Color(0xFFF3E7D4),
    onTertiaryContainer = Color(0xFF614425),
    background = XiuyinBackground,
    onBackground = XiuyinInk,
    surface = Color(0xFFFFFDF8),
    onSurface = XiuyinInk,
    surfaceVariant = Color(0xFFF1EAE0),
    onSurfaceVariant = XiuyinMuted,
    outline = Color(0xFF8C7B6B),
    outlineVariant = XiuyinLine,
    error = Color(0xFFAF393E),
    onError = Color.White,
)

private val XiuyinTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 43.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun XiuyinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XiuyinColors,
        typography = XiuyinTypography,
        content = content,
    )
}
