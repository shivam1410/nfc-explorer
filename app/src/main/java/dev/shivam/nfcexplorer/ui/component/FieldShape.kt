package dev.shivam.nfcexplorer.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The corner rounding shared by every text field and form button in the app.
 *
 * One value in one place, because the alternative is what this codebase already had: a rounder
 * field in the action editor and Material's default everywhere else, differing for no reason other
 * than which screen was edited most recently.
 */
val FieldShape = RoundedCornerShape(16.dp)
