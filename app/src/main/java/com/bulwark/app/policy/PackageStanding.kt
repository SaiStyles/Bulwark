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
     * `pm install-existing` can reinstall from it.
     *
     * **Not a guarantee, and the copy must not read like one.** It is proven on
     * three packages on one device, which is evidence rather than a promise:
     * the call has a signature fallback chain that can run out, a restored app
     * whose dependency was also removed can come back broken, and an OTA can
     * change what is on `/system`. SAI's rule, 2026-09-13 - if one app could
     * break the claim, the user gets the doubt rather than the confidence.
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
    /**
     * Shizuku. Refused for the same reason as [isSelf] and not a moral one:
     * switching it off removes the privilege needed to switch it back on.
     */
    val isPrivilegeSource: Boolean = false,
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
    val refused: Boolean get() = isSelf || isPrivilegeSource

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
     * So an incomplete check is *said* rather than escalated.
     *
     * **And never for an app the user installed** (SAI, 2026-09-13), whatever
     * role the device says it holds. `com.jio.myjio` holds the SMS role here
     * and is still theirs: they chose it, Android already lets them remove it
     * in two taps, and Bulwark making them type a package name for their own
     * app is ceremony about someone else's decision. The gate is for what came
     * with the phone.
     */
    val needsCeremony: Boolean get() =
        !refused && isCritical && restorability == Restorability.BULWARK_CAN_RESTORE
}

/**
 * What one job *is*, in the words a person is shown.
 *
 * **Identity, never consequence.** These said what would break - "remove it and
 * there is no screen to come back to", "including emergency calls" - and SAI
 * cut them on 2026-09-13 for a reason worth keeping: the device can name about
 * seven things, and writing rich consequence copy for those teaches people to
 * expect it on the other three hundred, where Bulwark has nothing to say. The
 * same trap the removal ratings were.
 *
 * Naming the thing is enough. Someone typing out a package name to remove
 * their own dialer does not need to be told what a dialer does.
 */
fun CriticalRoles.Job.sentence(): String = when (this) {
    CriticalRoles.Job.HOME -> "This is your home screen."
    CriticalRoles.Job.DIALER -> "This is your phone's dialer."
    CriticalRoles.Job.SMS -> "This is your messaging app."
    CriticalRoles.Job.SYSTEM_UI -> "This is the system interface - your status bar and navigation."
    CriticalRoles.Job.SETTINGS -> "This is Settings."
    CriticalRoles.Job.PACKAGE_INSTALLER -> "This is what installs apps."
    CriticalRoles.Job.IMS -> "This is what carries your calls."
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
            if (isPrivilegeSource) {
                "This is Shizuku, where Bulwark's permission to change anything " +
                    "comes from. Switching it off would take away the ability to " +
                    "switch it back on - that took a computer to undo."
            } else {
                "This is Bulwark. It cannot remove itself - doing so would kill " +
                    "the action part-way through and destroy the record that " +
                    "undoes it."
            }
        )
        return@buildList
    }
    // Jobs first and in a fixed order, so the same phone always reads the same
    // way and the loudest thing is the consequence rather than the plumbing.
    CriticalRoles.Job.entries.filter { it in jobs }.forEach { add(it.sentence()) }
    add(
        when (restorability) {
            Restorability.BULWARK_CAN_RESTORE ->
                "Preinstalled, so the phone keeps a copy Bulwark can try to " +
                    "reinstall - the version it shipped with, without your data " +
                    "or any updates. It has worked on every app tried so far, " +
                    "which is not the same as a promise about this one."
            Restorability.GONE_FOR_GOOD ->
                "You installed this. Bulwark cannot put it back - you would " +
                    "reinstall it yourself, and its data is gone."
        }
    )
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
    isPrivilegeSource -> "SHIZUKU"
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
    isPrivilegeSource = packageName == PRIVILEGE_SOURCE,
)

/** Shizuku's package. One name, exact, so it cannot collide. */
const val PRIVILEGE_SOURCE = "moe.shizuku.privileged.api"
