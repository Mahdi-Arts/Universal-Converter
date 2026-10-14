package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val UniversalDarkColorScheme = darkColorScheme(
    primary = UniversalCyanPrimary,
    onPrimary = Color(0xFF00363C),
    primaryContainer = UniversalCyanContainer,
    onPrimaryContainer = UniversalOnCyanContainer,
    secondary = UniversalViolet,
    onSecondary = Color(0xFF2E006A),
    secondaryContainer = UniversalVioletContainer,
    onSecondaryContainer = UniversalOnVioletContainer,
    tertiary = UniversalAmber,
    onTertiary = Color(0xFF452B00),
    background = StudioDarkBg,
    onBackground = StudioDarkTextPrimary,
    surface = StudioDarkSurface,
    onSurface = StudioDarkTextPrimary,
    surfaceVariant = StudioDarkSurfaceVariant,
    onSurfaceVariant = StudioDarkTextSecondary,
    outline = StudioDarkOutline,
    error = StatusError
)

private val UniversalLightColorScheme = lightColorScheme(
    primary = UniversalCyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF00363C),
    secondary = UniversalVioletDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9D5FF),
    onSecondaryContainer = Color(0xFF2E006A),
    tertiary = UniversalAmberDark,
    onTertiary = Color.White,
    background = StudioLightBg,
    onBackground = StudioLightTextPrimary,
    surface = StudioLightSurface,
    onSurface = StudioLightTextPrimary,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightTextSecondary,
    outline = StudioLightOutline,
    error = StatusError
)

@Composable
fun UniversalConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve brand multimedia colors by default
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> UniversalDarkColorScheme
        else -> UniversalLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatible alias
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = UniversalConverterTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
