package app.wanki.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Rounded like a paper sheet's corners, never pill-shaped. */
val WankiShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), // text fields, small menus
    small = RoundedCornerShape(12.dp), // chips, snackbars
    medium = RoundedCornerShape(16.dp), // cards, menus
    large = RoundedCornerShape(20.dp), // sheets, toolbars, FABs
    extraLarge = RoundedCornerShape(24.dp), // dialogs, bottom sheets
)
