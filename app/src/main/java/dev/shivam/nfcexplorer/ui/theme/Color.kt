package dev.shivam.nfcexplorer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Semantic roles, used consistently across every screen so a colour means one thing:
 *
 * | role | meaning |
 * |---|---|
 * | `primary` | writable — an action is possible |
 * | `error` | locked, failed, refused |
 * | `tertiary` | one-way or irreversible (OTP, lock control) — caution, not failure |
 * | `onSurfaceVariant` | read-only, absent, not established |
 *
 * Colour is never the only carrier of an access verdict; the text always states it too, so the
 * screens stay readable for colour-blind users and in screenshots.
 */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF62D8EC),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5A),
    onPrimaryContainer = Color(0xFFAAECFF),

    secondary = Color(0xFFB1CBD2),
    onSecondary = Color(0xFF1C3439),
    secondaryContainer = Color(0xFF334B50),
    onSecondaryContainer = Color(0xFFCDE7EE),

    // Caution, not failure: OTP and lock-control pages.
    tertiary = Color(0xFFF2BE6D),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E4100),
    onTertiaryContainer = Color(0xFFFFDEA8),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0E1416),
    onBackground = Color(0xFFDDE4E6),
    surface = Color(0xFF0E1416),
    onSurface = Color(0xFFDDE4E6),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBFC8CB),
    surfaceContainer = Color(0xFF1A2124),
    surfaceContainerHigh = Color(0xFF242B2E),
    outline = Color(0xFF899295),
    outlineVariant = Color(0xFF3F484B),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAAECFF),
    onPrimaryContainer = Color(0xFF001F26),

    secondary = Color(0xFF4A6268),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EE),
    onSecondaryContainer = Color(0xFF051F24),

    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF271900),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF5FAFC),
    onBackground = Color(0xFF171D1E),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF3F484B),
    surfaceContainer = Color(0xFFE9EFF1),
    surfaceContainerHigh = Color(0xFFE3E9EB),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBFC8CB),
)
