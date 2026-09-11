package com.bulwark.app.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
// ---------------------------------------------------------------------------
// Bulwark's semantic palette.
//
// Named by what they mean, not what they look like. These were inlined as raw
// hex at every use site - 0xFF7A3E00 appeared four times across two files - so
// a change meant finding every copy, and nothing said which shade meant
// "caution" versus "refused".
//
// The meanings are the app's vocabulary and they are deliberately few:
// Bulwark either refuses, cautions, or states a community rating. A palette
// that grows past this is usually a sign the UI has started expressing
// shades of opinion it cannot actually justify.
// ---------------------------------------------------------------------------

/** Bulwark refuses to touch it. Not a warning - a closed door. */
val Refused = Color(0xFF1565C0)

/** Something the user should read before acting. Never a refusal. */
val CautionText = Color(0xFF7A3E00)

/** Background behind a caution. Light enough to read on. */
val CautionBackground = Color(0xFFFFF3E0)

/** A source could not be read, so the answer is incomplete. */
val Incomplete = Color(0xFFB25C00)

/** The community database's own ratings, in its order of severity. */
val RatingRecommended = Color(0xFF2E7D32)
val RatingAdvanced = Color(0xFFE65100)
val RatingExpert = Color(0xFF6A1B9A)
val RatingUnsafe = Color(0xFFB71C1C)

/** Worth a person's attention. Not an accusation - see `permissions/`. */
val WorthLookingAt = Color(0xFFB71C1C)
