/*
 * Theme.kt
 * Exchange (Android)
 *
 * Material 3 theme wrapper. Uses dynamic color on Android 12+ so the
 * accent matches the user's wallpaper; falls back to our Exchange-blue
 * palette on older devices.
 */

package me.nettrash.exchange.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = ExchangeBlue,
    surface = ExchangeSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = ExchangeBlueDark,
    surface = ExchangeSurfaceDark,
)

@Composable
fun ExchangeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
