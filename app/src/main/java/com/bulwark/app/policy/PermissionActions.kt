package com.bulwark.app.policy

import com.bulwark.app.shizuku.CommandSafety
import com.bulwark.app.shizuku.RuntimePermissionAccess

/**
 * Revoke and grant, with every guard in the right order.
 *
 * The permission twin of [PackageActions], and deliberately the same shape:
 *
 * 1. **`CommandSafety.requireMutable`** - validates the name and refuses
 *    anything on the never-remove list. Stripping `READ_PHONE_STATE` from a
 *    telephony component is another route to a phone that cannot dial
 *    emergency services, and it is the same list that stops it.
 * 2. **Refuse if the device has no permission API we recognise.** Guardrail 1
 *    in `context/layers/02-permissions.md`: the undo for a revoke is a grant,
 *    and an action whose undo cannot be resolved is not reversible.
 * 3. **Read the current state** - so a revoke of something not held does
 *    nothing and records nothing, rather than writing a lie into the log.
 * 4. **`ActionJournal.perform`** - writes the attempt, runs the call, writes
 *    the outcome. The work is a lambda handed to the recorder, so there is no
 *    path that acts without recording first.
 * 5. **Read the state back** - because on 2026-09-11 a revoke of a
 *    `SYSTEM_FIXED` permission returned success and changed nothing.
 *
 * ## What is deliberately *not* here
 *
 * **Authentication**, for the same reason as [PackageActions]: it needs an
 * `Activity` and belongs at the UI edge. Nothing here may be called except
 * from a path holding `Result.Authenticated`.
 *
 * **A bulk revoke of *mixed* permissions.** [revokeAcrossApps] takes one
 * permission and a list of apps, and there is no overload that takes more -
 * the shape of the method is the enforcement, because a mixed batch is the one
 * `safety-rules.md` rule 1 still bans.
 *
 * **A bulk grant.** Deliberately absent. Giving capability to an app is the
 * direction an attacker would want, and the grant is an undo, which should be
 * one deliberate act at a time.
 *
 * Bulk restore was removed 2026-09-12, along with the rule that allowed it.
 * for the package one: same guards per step, logged individually, reported per
 * item.
 *
 * ## Proven on hardware
 *
 * Watched working on the Agni 2, 2026-09-11, against `com.jio.myjio` - a
 * target SAI approved for exactly this. `ACCESS_FINE_LOCATION` went granted ->
 * revoked -> granted, the read-back confirmed each change against the platform
 * rather than trusting the call, the real SQLite log recorded the attempt and
 * its outcome, and the permission came back with its `USER_SET` flag intact.
 *
 * `PermissionRoundTripOnHardware` is that run, kept as a test. It is the only
 * test in the project that changes the phone, and it is deliberately outside
 * the read-only smoke suite.
 *
 * **Still unproven: the authentication prompt in front of all this.** It needs
 * a fingerprint, which a test suite does not have, so it is checked by a person
 * using the app.
 */
class PermissionActions(
    private val journal: ActionJournal,
    /** Seam for tests. Production passes the real privileged calls. */
    private val access: Access = PlatformAccess,
) {

    /** The three privileged calls this needs, behind a seam tests can drive. */
    interface Access {
        fun isGranted(packageName: String, permission: String, userId: Int): Boolean
        fun revoke(packageName: String, permission: String, userId: Int)
        fun grant(packageName: String, permission: String, userId: Int)

        /**
         * Whether this device has a permission API Bulwark recognises.
         *
         * False means no permission change is offered at all, rather than one
         * offered and quietly broken.
         */
        val isSupported: Boolean
    }

    private object PlatformAccess : Access {
        override fun isGranted(packageName: String, permission: String, userId: Int) =
            RuntimePermissionAccess.isGranted(packageName, permission, userId)

        override fun revoke(packageName: String, permission: String, userId: Int) =
            RuntimePermissionAccess.revoke(packageName, permission, userId)

        override fun grant(packageName: String, permission: String, userId: Int) =
            RuntimePermissionAccess.grant(packageName, permission, userId)

        override val isSupported: Boolean get() = RuntimePermissionAccess.canChangePermissions
    }

    /**
     * Takes [permission] away from [packageName].
     *
     * @throws IllegalArgumentException if the name is malformed.
     * @throws SecurityException if the package is on the never-remove list.
     * @throws IllegalStateException if this device has no permission API we
     *   recognise, or if the platform accepted the call and changed nothing.
     */
    fun revoke(packageName: String, permission: String, userId: Int = 0) {
        CommandSafety.requireMutable(packageName)
        requireSupported()

        // Nothing held, nothing to take away. Do nothing, log nothing - a row
        // saying we revoked something the app never had is a false record, and
        // the log is the thing a user is meant to be able to trust.
        if (!access.isGranted(packageName, permission, userId)) return

        journal.perform(
            kind = ActionKind.REVOKE_PERMISSION,
            packageName = packageName,
            userId = userId,
            permission = permission,
        ) {
            access.revoke(packageName, permission, userId)
            confirmApplied(packageName, permission, expectGranted = false, userId = userId)
        }
    }

    /**
     * Gives [permission] back - the undo for [revoke].
     *
     * Kept as an ordinary public method rather than hidden behind an "undo"
     * button, because hardware testing on 2026-09-10 showed what a generic
     * undo does on its second press: it undoes the undo, performing a
     * destructive action under a non-destructive label.
     */
    fun grant(packageName: String, permission: String, userId: Int = 0) {
        CommandSafety.requireMutable(packageName)
        requireSupported()

        if (access.isGranted(packageName, permission, userId)) return

        journal.perform(
            kind = ActionKind.GRANT_PERMISSION,
            packageName = packageName,
            userId = userId,
            permission = permission,
        ) {
            access.grant(packageName, permission, userId)
            confirmApplied(packageName, permission, expectGranted = true, userId = userId)
        }
    }

    /**
     * Takes **one** permission away from several apps.
     *
     * The second exception to `safety-rules.md` rule 1, decided 2026-09-11 and
     * amended in writing. The argument is on `context/layers/02-permissions.md`
     * and is not repeated here; the conditions it imposes are all visible in
     * this signature and the loop below.
     *
     * **One permission, structurally.** The batch cannot be mixed because the
     * method cannot express a mixed one - a single [permission], a list of
     * apps. That is the condition rule 1 actually rests on: every change in the
     * batch is the same change, so the user can still say what was taken, and
     * the system's own prompt can name it in the one place malware cannot
     * rewrite.
     *
     * **The list is given, never matched.** No predicate, no pattern, no
     * "everything holding this" convenience. Rule 1's ban on
     * remove-everything-matching is untouched, and an empty list is refused
     * rather than treated as "all".
     *
     * ## Stops at the first failure, unlike the restore
     *
     * A batch continues past failures because stopping halfway
     * leaves *more* of the phone changed. This is the opposite case: it takes
     * capability away, so rule 6's fail-closed applies as written. If the
     * privileged path has died, grinding through nine more apps is how a bad
     * situation becomes an unattributable one.
     *
     * The caller is told what was done and what was skipped - never a bare
     * count of successes.
     *
     * @return one [StepOutcome] per app **attempted**, in order. Shorter than
     *   [packageNames] when it stopped early; the remainder were not touched.
     * @throws IllegalArgumentException if [packageNames] is empty.
     */
    fun revokeAcrossApps(
        permission: String,
        packageNames: List<String>,
        userId: Int = 0,
    ): List<StepOutcome> {
        require(packageNames.isNotEmpty()) {
            "A batch revoke needs the apps chosen explicitly. An empty list is " +
                "not a request to change everything."
        }

        val outcomes = mutableListOf<StepOutcome>()
        for (packageName in packageNames.distinct()) {
            val outcome = runCatching { revoke(packageName, permission, userId) }.fold(
                onSuccess = { StepOutcome(packageName, permission, succeeded = true) },
                onFailure = {
                    StepOutcome(
                        packageName, permission, succeeded = false,
                        failure = "${it::class.java.simpleName}: ${it.message}",
                    )
                },
            )
            outcomes += outcome
            if (!outcome.succeeded) break
        }
        return outcomes
    }

    private fun requireSupported() = check(access.isSupported) {
        "Bulwark does not recognise this phone's permission system, so it will " +
            "not change permissions on it. Nothing has been changed."
    }

    /**
     * Reads the grant back and fails if the platform did not actually change it.
     *
     * The whole reason this class exists in the shape it does.
     * `pm revoke` on a `SYSTEM_FIXED` permission returns without error and
     * changes nothing (Agni 2, 2026-09-11). Without this read-back Bulwark
     * would tell someone an app had lost the microphone while the app kept
     * recording, and record that in the log as a success - which makes the
     * record wrong as well as the screen.
     *
     * `Revocable` exists to stop us *offering* those. This is the second line:
     * a permission can become fixed between the audit and the tap, and the
     * flags are not a promise about the future.
     */
    private fun confirmApplied(
        packageName: String,
        permission: String,
        expectGranted: Boolean,
        userId: Int,
    ) {
        val actual = access.isGranted(packageName, permission, userId)
        if (actual == expectGranted) return
        error(
            "The system did not apply this. $permission is still " +
                "${if (actual) "allowed" else "not allowed"} for $packageName. " +
                "Some permissions are fixed by the phone's maker or by policy " +
                "and cannot be changed."
        )
    }


    /**
     * The earliest *successful* change to each app-and-permission pair, newest
     * pair first.
     *
     * Earliest per pair because that is the one that knows the state Bulwark
     * found. Newest pair first, matching the Changes screen's order,
     * where the order exists because undo is a stack.
     */
    private fun firstChangePerPermission(): List<ActionRecord> {
        val history = journal.history()
        val closed = history.filter { it.phase == Phase.SUCCEEDED }
            .mapNotNull { it.attemptId }
            .toSet()
        return history
            .filter { it.phase == Phase.ATTEMPTED && it.id in closed && it.kind.isPermissionChange }
            .groupBy { it.packageName to it.permission }
            .map { (_, changes) -> changes.first() }
            .asReversed()
    }
}
