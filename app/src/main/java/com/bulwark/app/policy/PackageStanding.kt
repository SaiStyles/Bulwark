package com.bulwark.app.policy

import com.bulwark.app.shizuku.CriticalRoles

/**
 * What Bulwark is willing to say about one package, and what it makes you do
 * to change it.
 *
 * Replaced the rating badges and the name-matched blocklist on 2026-09-12.
 * Everything here is either read from this phone or derived from it - nothing
 * is a stranger's verdict, and nothing is matched by substring.
 *
 * Pure, so every sentence a user is shown is tested without a device.
 */

/** Whether Bulwark can put this package back after removing it. */
enum class Restorability {
    /**
     * Preinstalled. `pm uninstall --user 0` leaves the APK on `/system` and
     * `pm install-existing` restores it. The app returns; its data does not.
     */
    BULWARK_CAN_RESTORE,

    /**
     * Installed by the user. There is no system copy, so Bulwark has nothing
     * to restore from - the app and its data are gone and the user reinstalls
     * it themselves.
     */
    GONE_FOR_GOOD,
}

/**
 * @property jobs what this phone says the package does.
 * @property checkIncomplete a critical-role read failed. Said on the row, but
 *   deliberately not escalated - see [Standing.needsCeremony].
 * @property isSelf Bulwark itself.
 */
data class Standing(
    val packageName: String,
    val jobs: Set<CriticalRoles.Job>,
    val restorability: Restorability,
    val checkIncomplete: Boolean = false,
    val isSelf: Boolean = false,
) {
    /** The phone named this package as doing one of its critical jobs. */
    val isCritical: Boolean get() = jobs.isNotEmpty()

    /**
     * The only refusal left.
     *
     * Not a judgement about danger - `safety-rules.md` gave those up. Bulwark
     * cannot uninstall Bulwark: the process is killed part-way through the
     * action, and it takes the log that makes the action reversible with it.
     * An action that cannot be completed or undone is not offered.
     */
    val refused: Boolean get() = isSelf

    /**
     * Warnings, then typing the name, then authentication.
     *
     * **Only for packages this phone actually named.** An earlier version also
     * fired on [checkIncomplete] - fail closed, so treat an unread job as
     * possibly this package. That was wrong in the way A2b was wrong before it
     * was gated on overlay: a condition that fires on nearly everything is not
     * a warning, it is friction, and it teaches people to type through the one
     * that mattered. One failed read would have put the ceremony on all 370.
     *
     * So an incomplete check is *said* rather than escalated - it appears in
     * [labels] and the screen says which job could not be read. Honest, and
     * still quiet enough that the ceremony keeps its meaning.
     */
    val needsCeremony: Boolean get() = !refused && isCritical
}

/** What one job means, in the words a person is shown. */
fun CriticalRoles.Job.sentence(): String = when (this) {
    CriticalRoles.Job.HOME ->
        "This is your home screen. Remove it and there is no screen to come back to."
    CriticalRoles.Job.DIALER ->
        "This is your phone's dialer - how calls are placed, including emergency calls."
    CriticalRoles.Job.SMS ->
        "This is your messaging app. Two-factor codes arrive here."
    CriticalRoles.Job.SYSTEM_UI ->
        "This draws your status bar, notifications and navigation. Without it the " +
            "phone is very hard to use at all."
    CriticalRoles.Job.SETTINGS ->
        "This is Settings - where you would go to undo things by hand."
    CriticalRoles.Job.PACKAGE_INSTALLER ->
        "This installs apps, so it is how anything gets put back by hand."
    CriticalRoles.Job.IMS ->
        "This carries your calls over mobile data and Wi-Fi on this phone."
}

/**
 * Every line to show on the row, most consequential first.
 *
 * Facts about this device only. There is no "recommended", no "safe", and no
 * count of what somebody else thinks - those went with the ratings, because
 * Bulwark could not stand behind them.
 */
fun Standing.labels(): List<String> = buildList {
    if (refused) {
        add(
            "This is Bulwark. It cannot remove itself - doing so would kill the " +
                "action part-way through and destroy the record that undoes it."
        )
        return@buildList
    }
    // Jobs first and in a fixed order, so the same phone always reads the same
    // way and the loudest thing is the consequence rather than the plumbing.
    CriticalRoles.Job.entries.filter { it in jobs }.forEach { add(it.sentence()) }
    if (checkIncomplete) {
        add(
            "Bulwark could not finish checking what this does on your phone."
        )
    }
    add(
        when (restorability) {
            Restorability.BULWARK_CAN_RESTORE ->
                "Preinstalled. Bulwark can put this back, though its data is lost."
            Restorability.GONE_FOR_GOOD ->
                "You installed this. Bulwark cannot put it back - you would " +
                    "reinstall it yourself, and its data is gone."
        }
    )
}

/**
 * The second warning, which names what stops working rather than repeating
 * that this is serious.
 *
 * Returns null when there is no ceremony, so a caller cannot accidentally show
 * a grave second screen for an ordinary app.
 */
fun Standing.secondWarning(): String? {
    if (!needsCeremony) return null
    // Non-empty by construction: the ceremony only fires when the phone named
    // at least one job, so the warning always has a consequence to state.
    val head = CriticalRoles.Job.entries
        .filter { it in jobs }
        .joinToString(" ") { it.sentence() }
    val tail = when (restorability) {
        Restorability.BULWARK_CAN_RESTORE ->
            "Bulwark can put it back, but not the data, and not while the phone " +
                "is unusable."
        Restorability.GONE_FOR_GOOD ->
            "Bulwark cannot put this one back at all."
    }
    return "$head $tail"
}

/**
 * What the user types to confirm.
 *
 * The package name, exactly - GitHub's pattern for deleting a repository. It
 * is aimed at the accidental tap, not at disagreement: someone who has read
 * the label and typed this is deciding, and it is their phone.
 */
fun Standing.confirmationPhrase(): String = packageName

/** True when [typed] releases the action. Whitespace is forgiven; case is not. */
fun Standing.confirmationAccepts(typed: String): Boolean =
    typed.trim() == confirmationPhrase()

/**
 * The short word on the row's badge.
 *
 * Names the job rather than rating the package - the badge used to say
 * RECOMMENDED or RISKY, which was a stranger's verdict wearing Bulwark's
 * colours.
 */
fun CriticalRoles.Job.badge(): String = when (this) {
    CriticalRoles.Job.HOME -> "HOME SCREEN"
    CriticalRoles.Job.DIALER -> "DIALER"
    CriticalRoles.Job.SMS -> "MESSAGES"
    CriticalRoles.Job.SYSTEM_UI -> "SYSTEM UI"
    CriticalRoles.Job.SETTINGS -> "SETTINGS"
    CriticalRoles.Job.PACKAGE_INSTALLER -> "INSTALLER"
    CriticalRoles.Job.IMS -> "CALLS"
}

/**
 * The badge, or null where the row needs none.
 *
 * One badge at most. `design.md` rule 5: never more than one loud thing on a
 * screen, and a row that shouts twice has said nothing. When the phone names
 * several jobs the first in declaration order wins, which is the same fixed
 * order [labels] uses, so a row never reorders itself between reads.
 */
fun Standing.badge(): String? = when {
    refused -> "BULWARK"
    else -> CriticalRoles.Job.entries.firstOrNull { it in jobs }?.badge()
}

/**
 * What Bulwark is prepared to say about one package, assembled.
 *
 * [roles] null means the reading has not arrived yet - which is *not* the same
 * as a phone with no dialer, so it becomes an incomplete check rather than an
 * all-clear. It does not fire the ceremony; see [Standing.needsCeremony].
 */
fun standingFor(
    packageName: String,
    isSystem: Boolean,
    roles: CriticalRoles.Reading?,
    selfPackage: String,
): Standing = Standing(
    packageName = packageName,
    jobs = roles?.jobsFor(packageName).orEmpty(),
    restorability = if (isSystem) {
        Restorability.BULWARK_CAN_RESTORE
    } else {
        Restorability.GONE_FOR_GOOD
    },
    checkIncomplete = roles == null || !roles.complete,
    isSelf = packageName == selfPackage,
)
