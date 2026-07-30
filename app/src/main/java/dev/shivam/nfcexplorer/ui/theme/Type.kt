package dev.shivam.nfcexplorer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val NfcTypography = Typography()

/**
 * Style for raw tag bytes.
 *
 * Monospaced so hex columns align down the page and a shifted nibble is visible at a glance,
 * with widened letter spacing because dense hex is hard to scan when the glyphs touch.
 */
val HexTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.6.sp,
    fontWeight = FontWeight.Normal,
)

/** Smaller variant for the secondary column (binary, decimal, ASCII) beside the hex. */
val HexSecondaryTextStyle = HexTextStyle.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
)

/** Page index gutter — same metrics as the hex it labels, so rows line up exactly. */
val PageIndexTextStyle = HexTextStyle.copy(fontWeight = FontWeight.Medium)
