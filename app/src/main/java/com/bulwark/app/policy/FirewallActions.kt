package com.bulwark.app.policy

import com.bulwark.app.shizuku.CommandSafety

/**
 * Which apps the user has asked to cut off from the network.
 *
 * The firewall's half of the policy layer, and deliberately the same shape as
 * [PackageActions] and [PermissionActions]: guard first, record through the
 * journal, never act without a log entry.
 *
 * ## It changes no system state, and that is the whole difference
 *
 * A disable writes to `PackageManager`; a revoke writes to the permission
 * service. Both can be read back off the platform tomorrow. **A firewall rule
 * is only an intent** - the tunnel enforces it while it runs, and the moment it
 * stops the phone knows nothing about it.
 *
 * Two consequences run through the whole layer:
 *
 * 1. **The log is the only record.** There is no platform state to recover the
 *    user's wishes from, which is why the rules are derived from the log
 *    ([blockedPackages]) rather than kept in a second store that could drift
 *    from it.
 * 2. **Recording a rule is not enforcing it.** Nothing here may report that an
 *    app is cut off. It reports what the user asked for; whether that is true
 *    right now is the tunnel's question, and it must be answered by looking at
 *    the tunnel. See the guardrails on `context/layers/03-firewall.md`.
 *
 * ## The guard has to work with Shizuku dead
 *
 * This is the one layer that must run with no privilege at all, so it cannot
 * use `CommandSafety.requireMutable`'s convenience overload - that asks the
 * platform whether a package is a system one through the privileged binder,
 * and with Shizuku down it fails closed and refuses everything.
 *
 * So system-ness is resolved through [SystemPackages], which production backs
 * with the app's **own** `PackageManager`. Still never supplied by the caller:
 * `CommandSafety` is explicit that a caller who can claim "not a system app"
 * can unlock every OEM-renamed telephony package, and that stays true here.
 *
 * ## Why telephony is refused from a *firewall*
 *
 * It reads like over-caution until you notice modern calling is data. Cutting
 * the IMS stack off the network can take voice calling with it, including the
 * emergency call that `safety-rules.md` will not trade away. The never-remove
 * list is the same list for the same reason.
 */
class FirewallActions(
    private val journal: ActionJournal,
    private val systemPackages: SystemPackages,
) {

    /**
     * Whether a package shipped with the phone.
     *
     * A seam, because the answer must come from somewhere that works without
     * Shizuku - and because a test needs to drive both sides of it.
     */
    fun interface SystemPackages {
        fun isSystem(packageName: String): Boolean
    }

    /**
     * Records that [packageName] should not reach the network.
     *
     * Does **not** make it so. The tunnel does that, and only while it runs.
     *
     * @throws IllegalArgumentException if the name is malformed.
     * @throws SecurityException if it is on the never-remove list - see the
     *   note above on why telephony belongs there for a firewall too.
     */
    fun block(packageName: String) {
        CommandSafety.requireMutable(packageName, systemPackages.isSystem(packageName))
        if (packageName in blocked()) return // Already asked for. Record nothing.

        journal.perform(
            kind = ActionKind.BLOCK_NETWORK,
            packageName = packageName,
        ) {
            // Nothing to call. The record *is* the action, which is why this
            // block is empty rather than missing: every change still goes
            // through the journal, so a firewall rule appears in the same
            // history, the same export and the same restore as everything else.
        }
    }

    /** Lets [packageName] reach the network again. The undo for [block]. */
    fun allow(packageName: String) {
        CommandSafety.requireMutable(packageName, systemPackages.isSystem(packageName))
        if (packageName !in blocked()) return

        journal.perform(
            kind = ActionKind.ALLOW_NETWORK,
            packageName = packageName,
        ) { }
    }

    /**
     * What the user has asked to block, as of now.
     *
     * Read fresh from the log every time rather than cached: the tunnel is
     * built from this, and a stale set means enforcing yesterday's wishes.
     */
    fun blocked(): Set<String> = journal.history().blockedPackages()

    /**
     * Puts every network rule back, one at a time.
     *
     * The firewall's share of "put everything back", under the same rule 1
     * conditions as the other two: same guards per step, logged individually,
     * reported per item, continuing past a failure because stopping halfway
     * leaves *more* of the phone changed than finishing.
     */
    fun restoreEverything(): List<StepOutcome> = blocked().map { packageName ->
        runCatching { allow(packageName) }.fold(
            onSuccess = { StepOutcome(packageName, permission = null, succeeded = true) },
            onFailure = {
                StepOutcome(
                    packageName, permission = null, succeeded = false,
                    failure = "${it::class.java.simpleName}: ${it.message}",
                )
            },
        )
    }
}
