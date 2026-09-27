package me.malm.realmdo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's line icons: 24-unit paths stroked at 2, round caps, the same set
 * the design was drawn with. `Icon` tints them, so the colour here is only a
 * placeholder. Kept as paths rather than the extended icon library, which
 * would bring thousands of icons for these twenty.
 */
object Glyph {
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5")
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Minus = stroke("minus", "M5 12h14")
    val Repeat = stroke("repeat", "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4")
    val Calendar = stroke(
        "calendar",
        "M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM4 10h16M9 3v4M15 3v4",
    )
    val Clock = stroke("clock", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0zM12 8v4l3 2")
    val Today = stroke("today", "M4 7l2 2 3-3M12 8h8M4 16l2 2 3-3M12 17h8")
    val Order = stroke("order", "M4 6h10M4 12h7M4 18h4M18 5v14M15 16l3 3 3-3")
    val History = stroke("history", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2")
    val Sliders = stroke(
        "sliders",
        "M4 7h10M18 7h2M4 17h4M12 17h8M18 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0zM12 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
    )
    val Undo = stroke("undo", "M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3")
    val Note = stroke("note", "M6 3h9l4 4v14H6zM9 12h7M9 16h5")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val Back = stroke("back", "M19 12H5M11 6l-6 6 6 6")
    val ChevronDown = stroke("chevron-down", "M6 9l6 6 6-6")
    val ChevronUp = stroke("chevron-up", "M6 15l6-6 6 6")
    val ChevronRight = stroke("chevron-right", "M9 6l6 6-6 6")
    val Copy = stroke(
        "copy",
        "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM4 16V6a2 2 0 0 1 2-2h10",
    )
    val Swap = stroke("swap", "M7 4L3 8l4 4M3 8h14M17 20l4-4-4-4M21 16H7")
    val Speaker = stroke("speaker", "M4 10v4h4l5 4V6L8 10zM16 9a4 4 0 0 1 0 6")
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16zM13 7l4 4")
    val Grip = stroke("grip", "M5 9h14M5 15h14")
    val Hidden = stroke(
        "hidden",
        "M3 3l18 18M10.6 5.1A10 10 0 0 1 22 12a13 13 0 0 1-2.2 3.1M6.6 6.6A13 13 0 0 0 2 12a10 10 0 0 0 13.4 5.4",
    )
    val Play = fill("play", "M8 5.5v13l10.5-6.5z")
    val Pause = fill("pause", "M7 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM14 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
    val Previous = fill("previous", "M6 5h1.5v14H6zM19 5.5v13L9 12z")
    val Next = fill("next", "M16.5 5H18v14h-1.5zM5 5.5v13L15 12z")

    /** The glyph for a task kind: calendar, repeat or clock. */
    fun kind(kind: String): ImageVector = when (kind) {
        "interval" -> Repeat
        "slot" -> Clock
        else -> Calendar
    }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    private fun stroke(name: String, d: String): ImageVector = builder(name).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun fill(name: String, d: String): ImageVector = builder(name).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
