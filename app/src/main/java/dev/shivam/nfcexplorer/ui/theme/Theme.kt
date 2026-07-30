package dev.shivam.nfcexplorer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 theme for the app.
 *
 * Dynamic colour is **off by default**. It is a nice default for consumer apps, but this one
 * assigns fixed meanings to `primary`, `error` and `tertiary` (writable / locked / irreversible),
 * and a wallpaper-derived palette can collapse the distance between them — which would make a
 * locked page and a writable one hard to tell apart. Callers can opt in explicitly.
 */
@Composable
fun NfcExplorerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NfcTypography,
        content = content,
    )
}
