package com.example.firstapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DisplayFont = FontFamily.Serif
private val BodyFont = FontFamily.SansSerif

val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp
    )
)
