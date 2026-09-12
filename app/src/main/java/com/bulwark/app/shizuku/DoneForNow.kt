package com.bulwark.app.shizuku

/**
 * Closing the door that setting Bulwark up opened.
 *
 * Bulwark asks people to switch on wireless debugging and start Shizuku. Both
 * then stay on, usually forever, because nothing ever tells anyone to turn them
 * off - and wireless debugging is a standing way in, which is the exposure
 * `RatSignals` can describe and until now could not do anything about.
 *
 * This is the other half of that: detection said the door is open, this offers
 * to close it.
 *
 * ## Two actions, not one
 *
 * They are separate because they close different things and cost differently,
 * and because **measurement showed they are independent**: switching wireless
 * debugging off does *not* kill a running Shizuku server (Agni 2, 2026-09-12).
 * The design note had assumed otherwise. Order still matters in one direction -
 * once the server is gone it cannot do anything else - but neither action
 * implies the other.
 *
 * ## Why the second one is a signpost and not a switch
 *
 * Bulwark cannot turn wireless debugging off itself. The setting is
 * `Settings.Global.adb_wifi_enabled`, and writing it needs shell privilege that
 * this app can only reach three ways, all of them refused for now:
 *
 * - a Shizuku **user service**, which is the clean route and is **dead on this
 *   hardware class** - `bindUserService` NPEs inside `LoadedApk`
 *   (`_shared/app-architecture.md`, verified 2026-09-10);
 * - `Shizuku.newProcess`, which is `private` in the API and spawns a shell -
 *   reflecting into a *library's* private method has no compatibility contract
 *   at all, and nothing else in this app shells out;
 * - the settings **ContentProvider** through `getContentProviderExternal`,
 *   which is in-pattern but drifts four ways across API 26-35 for one boolean.
 *
 * `IAdbManager` does not help: its methods answer pairing prompts, they do not
 * turn the feature off. The Global setting *is* the switch.
 *
 * So Bulwark says what to do and opens the right screen. **The offer must not
 * pretend to be the action** - a button that reads like it did the thing, and
 * did not, is the false sense of protection `safety-rules.md` calls worse than
 * none.
 */
enum class CloseAction {
    /** Stop the Shizuku server. Bulwark can do this itself. */
    STOP_SHIZUKU,

    /** Open Developer Options. The person flips the switch. */
    TURN_OFF_WIRELESS_DEBUGGING,
}

/**
 * One thing that can still be closed, with what it costs to close it.
 *
 * [cost] is not optional and is never empty. Advice that hides its cost does
 * not survive being followed - this project learned that by recommending
 * lockdown and taking a phone off the network.
 */
data class Closeable(
    val action: CloseAction,
    /** The button. Says who does it - Bulwark, or you. */
    val label: String,
    /** What actually happens. */
    val what: String,
    /** What it costs next time. Always stated. */
    val cost: String,
)

/**
 * What is still open, given what is running.
 *
 * Empty when there is nothing to close, and the screen then shows nothing at
 * all rather than a cheerful all-clear. Bulwark praising Bulwark is a voice
 * this project has already corrected once.
 */
fun whatCanBeClosed(
    shizukuRunning: Boolean,
    wirelessDebuggingOn: Boolean,
): List<Closeable> = buildList {
    if (shizukuRunning) {
        add(
            Closeable(
                action = CloseAction.STOP_SHIZUKU,
                label = "Stop Shizuku",
                what = "Shizuku's server stops - for every app on this phone, " +
                    "not only Bulwark. Anything else you use Shizuku for loses " +
                    "access until it is started again. The pairing itself is " +
                    "untouched; this is not deleting anything.",
                cost = "Next time you want Bulwark, start Shizuku again. That is " +
                    "one tap, as long as wireless debugging is still on.",
            ),
        )
    }
    if (wirelessDebuggingOn) {
        add(
            Closeable(
                action = CloseAction.TURN_OFF_WIRELESS_DEBUGGING,
                label = "Open Developer options",
                what = "Bulwark cannot switch this one off for you - Android does " +
                    "not let an ordinary app write it. This opens the screen; the " +
                    "switch is Wireless debugging, and you turn it off.",
                cost = "Shizuku uses wireless debugging to start after a reboot. " +
                    "With it off you turn it back on by hand before using Bulwark " +
                    "again, and Bulwark cannot do that part either - starting " +
                    "Shizuku needs the access Shizuku provides.",
            ),
        )
    }
}

/**
 * The line above the actions. Null when there is nothing to offer.
 *
 * States what is still open. It does not congratulate anyone, and it does not
 * call the current state unsafe - leaving both on is a reasonable choice for
 * someone who uses Bulwark daily, and the screen is an offer, not a scolding.
 */
fun doneForNowHeadline(closeable: List<Closeable>): String? = when {
    closeable.isEmpty() -> null
    closeable.size == 2 ->
        "Finished for now? Shizuku is running and wireless debugging is on. " +
            "Both can be closed, and both cost a step to undo."
    closeable.single().action == CloseAction.STOP_SHIZUKU ->
        "Finished for now? Shizuku is still running."
    else ->
        "Wireless debugging is still on. Shizuku is already stopped, but the " +
            "channel it used is the part that stays open."
}
