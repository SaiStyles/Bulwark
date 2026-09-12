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
 * @property jobs what this phone says the package does. Empty is not proof of
 *   ordinariness while [checkIncomplete] is true.
 * @property checkIncomplete a critical-role read failed, so the holder we
 *   could not read might be this package.
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
     * **Fires on an incomplete check too.** If Bulwark could not read who the
     * dialer is, the package in front of you might be the dialer, and treating
     * an unread check as an all-clear is the failure this project keeps
     * paying for.
     */
    val needsCeremony: Boolean get() = !refused && (isCritical || checkIncomplete)
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
            "Bulwark could not finish checking what this does on your phone, so " +
                "it is treating it as something that might matter."
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
    val consequences = CriticalRoles.Job.entries.filter { it in jobs }.map { it.sentence() }
    val head = when {
        consequences.isEmpty() ->
            "Bulwark could not confirm what this package does on your phone."
        else -> consequences.joinToString(" ")
    }
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
