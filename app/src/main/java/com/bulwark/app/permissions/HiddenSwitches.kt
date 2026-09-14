package com.bulwark.app.permissions

/**
 * App ops with **no switch anywhere in Settings**.
 *
 * The part of Bulwark that Android cannot be talked into doing. A phone tracks
 * around 58 ops per app and Settings exposes roughly a dozen of them - the
 * runtime permissions, and the handful under Special app access. The rest are
 * allowed by default, enforced by the platform, and invisible: there is no
 * screen where a person can stop an app reading their clipboard.
 *
 * ## Why these are not `Access` values
 *
 * `Access` answers "what rare, dangerous capability does this app hold", and
 * its card is meant to be short enough to read. Clipboard read is held by
 * nearly every app on the phone - on the Agni 2, by jio, Instagram and Brave
 * among others - so folding it in would take a list of eleven notable apps and
 * turn it into a list of sixty, which is `design.md` rule 5 broken on purpose.
 *
 * Different question, different card: not "who has something rare" but "who
 * holds a switch you were never offered".
 *
 * ## Scope, deliberately small
 *
 * Clipboard only, for now. It is inert to revoke - an app that asks gets an
 * empty clipboard rather than an error - and it is verifiably in use: Brave
 * read the clipboard 20 hours before this was written, and jio 559 days
 * before, both recorded by the phone and shown to nobody.
 *
 * `WAKE_LOCK` and `RUN_ANY_IN_BACKGROUND` are the obvious next two and are
 * **not here yet**. Nothing in Android expects a user to withdraw them, so
 * what breaks is a genuine unknown, and `conventions.md` says to find that out
 * on an emulator rather than on the phone somebody depends on.
 */
enum class HiddenSwitch(
    /** The platform's own op string. The stable half of the API. */
    val opName: String,
    /** Short, for a control. */
    val shortLabel: String,
    /** What it lets an app do, said to a person. */
    val plainMeaning: String,
) {
    READ_CLIPBOARD(
        opName = "android:read_clipboard",
        shortLabel = "Clipboard reading",
        plainMeaning = "Can read anything you copy, including passwords and codes.",
    ),

    WRITE_CLIPBOARD(
        opName = "android:write_clipboard",
        shortLabel = "Clipboard writing",
        plainMeaning = "Can replace what you have copied, including an address you are about to paste.",
    ),
    ;

    companion object {
        /** Op string to switch, for turning a reading back into a value. */
        fun forOp(opName: String): HiddenSwitch? = entries.firstOrNull { it.opName == opName }

        /** Every op this file is about, for the reader to ask for in one call. */
        val allOps: Set<String> get() = entries.map { it.opName }.toSet()
    }
}

/** One app, and the hidden switches it currently holds. */
data class HiddenSwitchHolder(
    val packageName: String,
    val switches: Set<HiddenSwitch>,
    /** Null means Bulwark could not tell, never "no". */
    val isSystem: Boolean?,
)

/**
 * The card's headline.
 *
 * Counts and stops. It does **not** imply these apps are doing something
 * wrong: a browser reads the clipboard so it can offer to open a link you
 * copied, which is the feature working. What Bulwark contributes is that the
 * switch exists at all, and that you were never shown it.
 */
fun hiddenSwitchHeadline(holders: List<HiddenSwitchHolder>): String {
    if (holders.isEmpty()) {
        return "No app on this phone holds one of these. That is unusual, so " +
            "treat it as worth a second look rather than good news."
    }
    val apps = if (holders.size == 1) "1 app" else "${holders.size} apps"
    return "$apps can read or change your clipboard."
}

/**
 * The line under the headline.
 *
 * Says the thing that makes this card worth existing, and says it once:
 * Android has no screen for this.
 */
fun hiddenSwitchDetail(holders: List<HiddenSwitchHolder>): String? {
    if (holders.isEmpty()) return null
    return "Android has no setting for this - there is no screen where you can " +
        "turn it off, and nothing ever asked you. Most apps have an ordinary " +
        "reason to hold it. Bulwark can take it away and give it back."
}

/** One row. */
fun HiddenSwitchHolder.line(): String =
    switches.sortedBy { it.name }.joinToString(" ") { it.plainMeaning }
