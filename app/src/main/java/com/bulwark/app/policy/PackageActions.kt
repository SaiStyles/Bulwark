package com.bulwark.app.policy

import com.bulwark.app.shizuku.CommandSafety
import com.bulwark.app.shizuku.PackageState

/**
 * Disable and enable, with every guard in the right order.
 *
 * This is the only place the three separate protections meet, and the order
 * they run in is the whole design:
 *
 * 1. **`CommandSafety.requireMutable`** - validates the name and refuses
 *    anything on the never-remove list. It resolves system-ness itself rather
 *    than accepting it from above, so a caller cannot unlock an OEM-renamed
 *    telephony package by claiming it is a user app.
 * 2. **Read the current state** - this is what makes the undo honest. Doing it
 *    after the guard means we never read state for a package we would refuse.
 * 3. **`ActionJournal.perform`** - writes the attempt, runs the call, writes
 *    the outcome. Not a logging call placed next to the work; the work is a
 *    lambda handed to the recorder, so there is no path that acts without
 *    recording first.
 *
 * ## What is deliberately *not* here
 *
 * **Authentication.** `DestructiveActionGuard` needs an `Activity` and returns
 * through a callback, so it belongs at the UI edge where an Activity exists.
 * Putting it here would drag Android's window system into the policy layer and
 * make all of this untestable, which is how a guard ends up unverified.
 *
 * The rule that keeps that honest: **nothing calls [disable] except a code
 * path that has just been handed `Result.Authenticated`.** That is a
 * convention, and conventions decay - so it is asserted at the one call site
 * rather than hoped for, and the call site is small enough to read in full.
 *
 * **Bulk *apply*.** There is no `disableAll`. `safety-rules.md` rule 1 forbids
 * it and the absence of the method is the enforcement: forty changes at once
 * means nobody can tell which one broke the phone.
 *
 * Bulk **restore** is allowed and lives in [restoreEverything] - rule 1 was
 * amended for it on 2026-09-11, in writing rather than by drifting. Undoing
 * accumulated change is the opposite of accumulating it, and a user who has to
 * reverse thirty things by hand is not being kept safe.
 *
 * ## Proven on hardware
 *
 * Watched working on the Agni 2, 2026-09-10: read state, disable (`0` -> `3`),
 * restore (`3` -> `0`, not `1`), correct log, survives reboot. Evidence in
 * `context/devices/lava-agni-2.md`, including the UI bug that run exposed and
 * fifteen passing unit tests did not.
 */
class PackageActions(
    private val journal: ActionJournal,
    /** Our own package name, recorded by the platform as who asked. */
    private val callingPackage: String,
    /** Seam for tests. Production passes the real privileged calls. */
    private val state: StateAccess = PlatformState,
) {

    /** The two privileged calls this needs, behind a seam so tests can drive them. */
    interface StateAccess {
        fun get(packageName: String, userId: Int): Int
        fun set(packageName: String, state: Int, userId: Int, callingPackage: String)
    }

    private object PlatformState : StateAccess {
        override fun get(packageName: String, userId: Int) =
            PackageState.get(packageName, userId)

        override fun set(packageName: String, state: Int, userId: Int, callingPackage: String) =
            PackageState.set(packageName, state, userId, callingPackage)
    }

    /**
     * Switches [packageName] off, reversibly, recording what it was first.
     *
     * @throws IllegalArgumentException if the name is malformed.
     * @throws SecurityException if it is on the never-remove list.
     * @throws Exception if the privileged call fails - after recording that it
     *   failed. Rule 6: the caller must see this, never a swallowed error.
     */
    fun disable(packageName: String, userId: Int = 0) {
        CommandSafety.requireMutable(packageName)

        // Read before acting. If this throws we have changed nothing, and an
        // unreadable state is not something to guess at - a disable whose undo
        // is a guess is not reversible, it is merely usually reversible.
        val previous = state.get(packageName, userId)

        if (PackageState.isDisabled(previous)) return // Already off. Do nothing, log nothing.

        journal.perform(
            kind = ActionKind.DISABLE,
            packageName = packageName,
            userId = userId,
            previousState = previous,
        ) {
            state.set(packageName, PackageState.DISABLED_USER, userId, callingPackage)
            confirmApplied(packageName, PackageState.DISABLED_USER, userId)
        }
    }

    /**
     * Reads the state back and fails if the platform did not actually change it.
     *
     * **A privileged call that returns without throwing has not necessarily
     * done anything.** Found on hardware 2026-09-11: `pm revoke` on a
     * `SYSTEM_FIXED` permission returns no error and changes nothing. The same
     * shape is possible here - a fixed or policy-controlled package where the
     * set is accepted and quietly ignored.
     *
     * Without this, Bulwark would tell someone it switched an app off while the
     * app kept running. That is the false sense of protection `safety-rules.md`
     * calls worse than none, and it would be recorded in the log as a success,
     * which makes the log wrong too.
     *
     * One extra binder read per action, which is nothing beside the action.
     */
    private fun confirmApplied(packageName: String, expected: Int, userId: Int) {
        val actual = state.get(packageName, userId)
        if (actual == expected) return
        // DEFAULT is the platform saying "whatever I shipped with", so a
        // restore that asked for DEFAULT and reads back as ENABLED did land.
        if (expected == PackageState.DEFAULT && actual == PackageState.ENABLED) return
        error(
            "The system did not apply this. Asked for state $expected, " +
                "it is still $actual. Some packages are fixed by the phone's " +
                "maker or by policy and cannot be changed."
        )
    }

    /**
     * Puts [packageName] back to [previousState], or to the system default
     * when nothing was recorded.
     *
     * Still passes `requireMutable`. Enabling is not destructive, but the
     * validation half matters just as much - and a package on the never-remove
     * list should never have been disabled by us, so being asked to re-enable
     * one means something upstream is wrong and should fail loudly.
     */
    fun enable(packageName: String, previousState: Int?, userId: Int = 0) {
        CommandSafety.requireMutable(packageName)

        journal.perform(
            kind = ActionKind.ENABLE,
            packageName = packageName,
            userId = userId,
            previousState = previousState,
        ) {
            val target = previousState ?: PackageState.DEFAULT
            state.set(packageName, target, userId, callingPackage)
            confirmApplied(packageName, target, userId)
        }
    }

    /**
     * Switches [packageName] back on, to the state Bulwark found it in.
     *
     * This is what the UI calls, **not** [undoLast]. Hardware testing on
     * 2026-09-10 found the difference the hard way: an "Undo" button that
     * undoes the most recent change will, on a second press, undo the *undo* -
     * performing a disable under a non-destructive label. The log recorded it
     * faithfully, which is how it was caught.
     *
     * So the UI asks for the state it wants rather than for "one step back",
     * and this looks up the state to restore instead of assuming `ENABLED`.
     * Falls back to the system default when Bulwark has no record of
     * disabling it - `pm enable` sets `ENABLED` (1), which for a package that
     * shipped at `DEFAULT` (0) is a change of its own.
     */
    fun switchBackOn(packageName: String, userId: Int = 0) =
        enable(packageName, stateBeforeLastDisable(packageName), userId)

    /** The state recorded by the most recent *successful* disable, if any. */
    private fun stateBeforeLastDisable(packageName: String): Int? {
        val history = journal.history().filter { it.packageName == packageName }
        val closed = history.filter { it.phase == Phase.SUCCEEDED }.mapNotNull { it.attemptId }.toSet()
        return history.lastOrNull {
            it.kind == ActionKind.DISABLE && it.phase == Phase.ATTEMPTED && it.id in closed
        }?.previousState
    }

    /** What happened to one package during [restoreEverything]. */
    data class RestoreStep(
        val packageName: String,
        val succeeded: Boolean,
        /** Why it failed, in the platform's words. Null on success. */
        val failure: String? = null,
    )

    /**
     * Puts every package Bulwark changed back to the state it found.
     *
     * The one bulk operation that exists, and only because it is a *restore*:
     * see `safety-rules.md` rule 1, amended 2026-09-11 with the reasoning and
     * the conditions this method has to satisfy.
     *
     * ## Continues past a failure, on purpose
     *
     * Rule 6 says fail closed, and for a destructive action that is right -
     * stopping leaves less of the phone changed. Here it is backwards:
     * stopping halfway through a restore leaves *more* of the phone changed
     * than finishing. So each package is attempted, each result is recorded,
     * and **the caller is handed every outcome** rather than a boolean.
     *
     * The honesty condition rule 1 imposes is that this never reports a bare
     * "done". A user told everything was put back when two packages failed is
     * worse off than one told exactly which two.
     *
     * Newest first, from [undoPlan] - undo is a stack, and restoring in
     * insertion order can put a dependency back before the thing that needed
     * it.
     *
     * @return one [RestoreStep] per package, in the order attempted. Empty
     *   when Bulwark has changed nothing.
     */
    fun restoreEverything(userId: Int = 0): List<RestoreStep> {
        val history = journal.history()
        // One entry per package: restoring a package twice is pointless, and
        // the second attempt would undo the first.
        val packages = history.undoPlan().map { it.packageName }.distinct()

        return packages.map { packageName ->
            runCatching { switchBackOn(packageName, userId) }.fold(
                onSuccess = { RestoreStep(packageName, succeeded = true) },
                onFailure = {
                    RestoreStep(
                        packageName,
                        succeeded = false,
                        failure = "${it::class.java.simpleName}: ${it.message}",
                    )
                },
            )
        }
    }

    /**
     * Undoes the most recent successful change to [packageName].
     *
     * Reads the plan from the log rather than from anything the UI is holding,
     * because the log is the thing that survives the process dying. A user who
     * force-stopped Bulwark mid-session and came back must still be able to
     * undo.
     *
     * Returns false when there is nothing to undo, rather than throwing:
     * "nothing to do" is a normal answer here, not a failure.
     */
    fun undoLast(packageName: String, userId: Int = 0): Boolean {
        val step = journal.history()
            .filter { it.packageName == packageName }
            .undoPlan()
            .firstOrNull() ?: return false

        when (step.kind) {
            ActionKind.ENABLE -> enable(packageName, step.previousState, userId)
            ActionKind.DISABLE -> disable(packageName, userId)
            // Uninstall and its undo do not exist yet. Refusing loudly beats
            // silently doing nothing, which would read to the user as "undo
            // worked" when nothing had happened.
            else -> error("No undo implemented for ${step.kind}")
        }
        return true
    }
}
