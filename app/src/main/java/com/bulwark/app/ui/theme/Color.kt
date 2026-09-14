package com.bulwark.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Bulwark's palette.
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
//
// **Every meaning now has two values**, and that is the point of this file's
// 2026-09-14 rewrite. Until then the semantic colours were single light-theme
// hex values while the Material scheme flipped underneath them, so a dark
// orange caution landed on a dark surface and the warning became the least
// readable text on the screen. Colour carries meaning here - `design.md` rule
// 4 - which makes an unreadable colour a lost meaning, not a cosmetic fault.
// ---------------------------------------------------------------------------

// -- Material roles, light ---------------------------------------------------
//
// A deep teal rather than the Compose template's purple, which shipped
// untouched until 2026-09-14. Bulwark is read by someone who may already be
// worried; the scheme is calm and low-chroma so that the few colours which do
// mean something can be the loud ones.

internal val LightPrimary = Color(0xFF1F5E57)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFA8F0E4)
internal val LightOnPrimaryContainer = Color(0xFF00201C)
internal val LightSecondary = Color(0xFF4A635F)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFCCE8E2)
internal val LightOnSecondaryContainer = Color(0xFF06201C)
internal val LightBackground = Color(0xFFF5FBF8)
internal val LightOnBackground = Color(0xFF171D1B)
internal val LightSurface = Color(0xFFF5FBF8)
internal val LightOnSurface = Color(0xFF171D1B)
internal val LightSurfaceVariant = Color(0xFFDAE5E1)
internal val LightOnSurfaceVariant = Color(0xFF3F4946)
internal val LightOutline = Color(0xFF6F7976)
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)

// -- Material roles, dark ----------------------------------------------------
//
// Designed, not inverted. The use scene is not an afterthought: somebody
// checking whether their phone is watching them is plausibly doing it at
// night, in bed, possibly angling the screen away from someone. A near-black
// surface with high-contrast text is the right instrument for that, and the
// same reasoning is why Audit and Activity carry FLAG_SECURE.

internal val DarkPrimary = Color(0xFF8CD4C8)
internal val DarkOnPrimary = Color(0xFF00382F)
internal val DarkPrimaryContainer = Color(0xFF005048)
internal val DarkOnPrimaryContainer = Color(0xFFA8F0E4)
internal val DarkSecondary = Color(0xFFB1CCC6)
internal val DarkOnSecondary = Color(0xFF1C3531)
internal val DarkSecondaryContainer = Color(0xFF334B47)
internal val DarkOnSecondaryContainer = Color(0xFFCCE8E2)
internal val DarkBackground = Color(0xFF0E1513)
internal val DarkOnBackground = Color(0xFFDDE4E1)
internal val DarkSurface = Color(0xFF0E1513)
internal val DarkOnSurface = Color(0xFFDDE4E1)
internal val DarkSurfaceVariant = Color(0xFF3F4946)
internal val DarkOnSurfaceVariant = Color(0xFFBEC9C5)
internal val DarkOutline = Color(0xFF899390)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

/**
 * The meanings Bulwark's UI is allowed to express, resolved for one theme.
 *
 * Read through `MaterialTheme.bulwark`, never imported directly - an import is
 * how a light-only value ends up on a dark screen, which is the bug this type
 * exists to make impossible.
 */
@Suppress("LongParameterList")
data class BulwarkColors(
    /** Bulwark refuses to touch it. Not a warning - a closed door. */
    val refused: Color,
    /** Something to read before acting. Never a refusal. */
    val caution: Color,
    /** Behind a caution. Readable, never decorative. */
    val cautionContainer: Color,
    /** Text on [cautionContainer]. */
    val onCautionContainer: Color,
    /** A source could not be read, so the answer is incomplete. */
    val incomplete: Color,
    /** Worth a person's attention. Not an accusation - see `permissions/`. */
    val worthLookingAt: Color,
    /** The community database's own ratings, in its order of severity. */
    val ratingRecommended: Color,
    val ratingAdvanced: Color,
    val ratingExpert: Color,
    val ratingUnsafe: Color,
)

internal val LightBulwarkColors = BulwarkColors(
    refused = Color(0xFF14548F),
    caution = Color(0xFF7A3E00),
    cautionContainer = Color(0xFFFFF3E0),
    onCautionContainer = Color(0xFF2B1700),
    incomplete = Color(0xFF8A4B00),
    worthLookingAt = Color(0xFFA5271F),
    ratingRecommended = Color(0xFF1F6D24),
    ratingAdvanced = Color(0xFFB34700),
    ratingExpert = Color(0xFF6A1B9A),
    ratingUnsafe = Color(0xFFB3261E),
)

/**
 * The same meanings for a dark surface.
 *
 * Not the light values lightened: each is chosen to clear 4.5:1 against
 * [DarkSurface], because a caution nobody can read is a caution nobody was
 * given.
 */
internal val DarkBulwarkColors = BulwarkColors(
    refused = Color(0xFFA8C8FF),
    caution = Color(0xFFFFB874),
    cautionContainer = Color(0xFF3B2400),
    onCautionContainer = Color(0xFFFFDDB8),
    incomplete = Color(0xFFFFB874),
    worthLookingAt = Color(0xFFFFB4AB),
    ratingRecommended = Color(0xFF7CD98A),
    ratingAdvanced = Color(0xFFFFB77A),
    ratingExpert = Color(0xFFD9B3FF),
    ratingUnsafe = Color(0xFFFFB4AB),
)
