package com.bulwark.app.policy

/**
 * What Bulwark did to this phone, in the order it did it.
 *
 * `safety-rules.md` rule 5 requires a local, exportable record so a user can
 * undo and so a bug report is possible. Rule 3 requires a *tested* undo before
 * anything destructive ships. This file is what both stand on, which is why it
 * was written before the first destructive action rather than after.
 *
 * ## Append-only, and structurally so
 *
 * There is no update and no delete anywhere in [ActionLog]. An outcome is a
 * **new row** pointing back at the attempt it belongs to, not an edit of it.
 *
 * That is how audit logs are built, and here it buys something specific: the
 * fact that an attempt was *made* can never be erased by the code that made
 * it. A bug in the outcome path loses the outcome, not the evidence. The
 * alternative - one mutable row per action - fails silently in exactly the
 * case the log exists for, which is the run that went wrong.
 *
 * `ActionLogAppendOnlyTest` asserts this by reflection rather than by comment,
 * because a comment does not stop anyone adding `fun delete()`.
 *
 * ## Written before the action, not after
 *
 * [ActionJournal] commits [Phase.ATTEMPTED] before the privileged call runs.
 * A process killed mid-uninstall therefore leaves a row saying "we were about
 * to do this and never recorded finishing" - see [unfinished]. Logging
 * afterwards would leave nothing at all, which is the one state a user cannot
 * recover from.
 */
interface ActionLog {

    /**
     * Records one fact and returns its id. The only way to write.
     *
     * Must be durable when it returns: [ActionJournal] relies on the attempt
     * surviving a process death that happens one instruction later.
     */
    fun append(entry: NewEntry): Long

    /** Everything recorded, oldest first. */
    fun all(): List<ActionRecord>

    /** Everything recorded for one package, oldest first. */
    fun forPackage(packageName: String): List<ActionRecord>
}

/** What Bulwark can do to a package. */
enum class ActionKind {
    /**
     * `setApplicationEnabledSetting(..., COMPONENT_ENABLED_STATE_DISABLED_USER, ...)`.
     * The app stops running, stays installed, keeps its data. The default
     * action per `safety-rules.md` rule 3.
     */
    DISABLE,

    /** Puts back what [DISABLE] changed, to its recorded previous state. */
    ENABLE,

    /**
     * Uninstall for one user. The APK stays on `/system`, which is what makes
     * it reversible; an escalation, not a default.
     */
    UNINSTALL,

    /** `installExistingPackageAsUser` - the undo for [UNINSTALL]. */
    INSTALL_EXISTING,

    /**
     * `revokeRuntimePermission` for one permission on one package.
     *
     * Destructive in the sense that matters here: it takes a capability away,
     * and an app that handles the refusal badly will crash. Reversible because
     * uid 2000 can grant as well as revoke - proven on the Agni 2, 2026-09-11.
     *
     * Rows of this kind must carry [NewEntry.permission]. Which permission it
     * was *is* the action; a revoke recorded against a package alone cannot be
     * undone, only guessed at, and [ActionJournal] refuses to write one.
     */
    REVOKE_PERMISSION,

    /** `grantRuntimePermission` - the undo for [REVOKE_PERMISSION]. */
    GRANT_PERMISSION,

    /**
     * Cut one app off from the network.
     *
     * Unlike every other kind here, this one changes **no system state**. It
     * records an intent, and the firewall enforces it while it is running. The
     * consequence is that the log is the *only* record - there is nothing to
     * read back off the platform tomorrow to find out what the user wanted.
     */
    BLOCK_NETWORK,

    /** Let it reach the network again. The undo for [BLOCK_NETWORK]. */
    ALLOW_NETWORK,

    /**
     * `setUidMode` / `setMode` on one app op - overlay, all-files, usage
     * access, install-unknown-apps.
     *
     * Not a runtime permission, and deliberately a separate kind: an app op
     * lives underneath permissions, has no prompt, and is taken away by a
     * different call. Measured writable on the Agni 2, 2026-09-14
     * (`../../context/layers/02-permissions/app-ops.md`).
     *
     * Rows of this kind must carry [NewEntry.appOp], for the same reason the
     * permission kinds must name their permission: the undo would otherwise
     * know the app and the intent and have nothing to act on.
     *
     * They should also carry [NewEntry.previousUidState] wherever the op was
     * held at uid level, because restoring only the package entry leaves state
     * the phone never had.
     */
    REVOKE_SPECIAL_ACCESS,

    /** Gives the app op back. The undo for [REVOKE_SPECIAL_ACCESS]. */
    GRANT_SPECIAL_ACCESS,
    ;

    /**
     * The action that puts this one back.
     *
     * Non-null for every kind, and `ActionKindTest` asserts it: rule 3 says
     * every action must have a working, tested undo *before it ships*. An
     * action added here with no inverse fails the build's tests rather than
     * reaching a stranger's phone, which is the difference between a rule and
     * a wish.
     */
    val undo: ActionKind
        get() = when (this) {
            DISABLE -> ENABLE
            ENABLE -> DISABLE
            UNINSTALL -> INSTALL_EXISTING
            INSTALL_EXISTING -> UNINSTALL
            REVOKE_PERMISSION -> GRANT_PERMISSION
            GRANT_PERMISSION -> REVOKE_PERMISSION
            BLOCK_NETWORK -> ALLOW_NETWORK
            ALLOW_NETWORK -> BLOCK_NETWORK
            REVOKE_SPECIAL_ACCESS -> GRANT_SPECIAL_ACCESS
            GRANT_SPECIAL_ACCESS -> REVOKE_SPECIAL_ACCESS
        }

    /** True when this takes a capability away rather than giving it back. */
    val isDestructive: Boolean
        get() = this == DISABLE || this == UNINSTALL || this == REVOKE_PERMISSION ||
            this == BLOCK_NETWORK || this == REVOKE_SPECIAL_ACCESS

    /** True when this action is about the firewall rather than system state. */
    val isNetworkRule: Boolean
        get() = this == BLOCK_NETWORK || this == ALLOW_NETWORK

    /** True when this action is about one permission rather than a whole app. */
    val isPermissionChange: Boolean
        get() = this == REVOKE_PERMISSION || this == GRANT_PERMISSION

    /**
     * True when this action is about one **app op** rather than a permission.
     *
     * Separate from [isPermissionChange] on purpose. `wordsFor()` maps runtime
     * permission strings to plain words and would produce nonsense for an op
     * string, so the two must never share a field or a branch.
     */
    val isSpecialAccessChange: Boolean
        get() = this == REVOKE_SPECIAL_ACCESS || this == GRANT_SPECIAL_ACCESS
}

/** Where one attempt got to. */
enum class Phase {
    /** Written **before** the privileged call. Durable by the time it runs. */
    ATTEMPTED,

    /** The privileged call returned without throwing. */
    SUCCEEDED,

    /** It threw, or the platform reported failure. [ActionRecord.detail] says what. */
    FAILED,
}

/**
 * A row to append. Separate from [ActionRecord] because ids and timestamps are
 * assigned by the log, not by the caller - a caller that can choose its own
 * timestamp can reorder history.
 */
data class NewEntry(
    val packageName: String,
    val kind: ActionKind,
    val phase: Phase,
    val userId: Int,
    /**
     * The enabled-state this package had *before* Bulwark touched it, for
     * [ActionKind.DISABLE] attempts.
     *
     * Reversible means "back to what it was", not "back to enabled". A package
     * sitting at `DISABLED_UNTIL_USED` that we set to `DISABLED_USER` must come
     * back to `DISABLED_UNTIL_USED`; restoring it to `COMPONENT_ENABLED_STATE_ENABLED`
     * would be Bulwark quietly making a change of its own while claiming to
     * undo one.
     */
    val previousState: Int? = null,
    /** For an outcome row: the id of the [Phase.ATTEMPTED] row it closes. */
    val attemptId: Long? = null,
    /** Free text. An error message on failure; otherwise usually null. */
    val detail: String? = null,
    /**
     * The app op this row is about, for the special-access kinds. Null for
     * everything else, and `ActionJournal` enforces both directions.
     *
     * Its own field rather than reusing [permission]: `ActionLogExport` and
     * `StepOutcome` both run [permission] through `wordsFor()`, which knows
     * runtime permissions and would print nonsense for `android:…` op strings.
     */
    val appOp: String? = null,
    /**
     * The **uid-level** mode this op had before, for the special-access kinds.
     *
     * [previousState] carries the package-level mode; this carries the uid one,
     * because an op can be held at either level and `checkOperation` resolves
     * the uid entry first. Restoring only one of the two leaves state the phone
     * never had - which happened once on hardware, 2026-09-14, and is why both
     * are recorded.
     */
    val previousUidState: Int? = null,
    /**
     * The permission this row is about, for [ActionKind.REVOKE_PERMISSION] and
     * [ActionKind.GRANT_PERMISSION]. Null for everything else.
     *
     * Its own column rather than a line in [detail], because the undo *reads*
     * it. [detail] is bounded free text written for a human to look at; a field
     * the code depends on has to be one the code can rely on being there.
     */
    val permission: String? = null,
)

/** One recorded fact, as it comes back out. */
data class ActionRecord(
    val id: Long,
    val atEpochMillis: Long,
    val packageName: String,
    val kind: ActionKind,
    val phase: Phase,
    val userId: Int,
    val previousState: Int?,
    val attemptId: Long?,
    val detail: String?,
    /** The permission, for the two permission kinds. Null for the rest. */
    val permission: String? = null,
    /** The app op, for the two special-access kinds. Null for the rest. */
    val appOp: String? = null,
    /** The uid-level mode before the change, for the special-access kinds. */
    val previousUidState: Int? = null,
)

/**
 * Attempts with no recorded outcome - the interrupted ones.
 *
 * **These are reported, never undone automatically.** We do not know whether
 * the call landed, and `safety-rules.md` rule 6 says stop and report on
 * ambiguity rather than proceed on a guess. Automatically "undoing" an
 * uninstall that never happened would be Bulwark taking an unrequested action
 * on the strength of not knowing.
 *
 * Pure function over the records so it is testable without a device.
 */
fun List<ActionRecord>.unfinished(): List<ActionRecord> {
    val closed = mapNotNullTo(HashSet()) { it.attemptId }
    return filter { it.phase == Phase.ATTEMPTED && it.id !in closed }
}

/**
 * What it would take to put everything back, newest change undone first.
 *
 * Only [Phase.SUCCEEDED] actions appear: an attempt that failed changed
 * nothing, and an attempt with no outcome is [unfinished] and belongs in front
 * of a human instead.
 *
 * Reverse chronological because undo is a stack. Disabling A then B and
 * undoing in insertion order can restore a dependency before the thing that
 * needed it, which on some OEM builds is the difference between working and
 * not.
 *
 * Returns *intent*, not effect. Nothing here runs anything; the caller still
 * passes each one through `CommandSafety.requireMutable` and the guard.
 */
fun List<ActionRecord>.undoPlan(): List<NewEntry> {
    val succeeded = filter { it.phase == Phase.SUCCEEDED }.mapNotNull { outcome ->
        val attemptId = outcome.attemptId ?: return@mapNotNull null
        firstOrNull { it.id == attemptId }
    }
    return succeeded.asReversed().map { attempt ->
        NewEntry(
            packageName = attempt.packageName,
            kind = attempt.kind.undo,
            phase = Phase.ATTEMPTED,
            userId = attempt.userId,
            // Carried through so ENABLE restores the state DISABLE found,
            // rather than assuming it found an enabled package.
            previousState = attempt.previousState,
            // And so a permission undo knows which permission. Without this
            // the plan names an app and an intent to grant, with nothing to
            // grant - which is not a plan, it is a shape of one.
            permission = attempt.permission,
        )
    }
}

/**
 * The apps the user has asked the firewall to cut off, as of now.
 *
 * Derived from the log rather than stored separately, which is the whole
 * reason the firewall needed no new storage: the record, the undo and "put
 * everything back" all come free, and there is no second copy of the truth to
 * drift from the first.
 *
 * Only [Phase.SUCCEEDED] entries count. An attempt with no outcome is
 * [unfinished] and means Bulwark does not know what happened - and a firewall
 * rule Bulwark is unsure about must not be treated as in force, because the
 * screen would then claim a protection nobody can vouch for.
 *
 * Last write wins per package, which is what makes block-then-allow leave
 * nothing behind.
 *
 * Pure, so the set the tunnel is built from is tested without a device.
 */
fun List<ActionRecord>.blockedPackages(): Set<String> {
    val closed = filter { it.phase == Phase.SUCCEEDED }.mapNotNull { it.attemptId }.toSet()
    val decided = mutableMapOf<String, ActionKind>()
    filter { it.phase == Phase.ATTEMPTED && it.id in closed && it.kind.isNetworkRule }
        .forEach { decided[it.packageName] = it.kind }
    return decided.filterValues { it == ActionKind.BLOCK_NETWORK }.keys
}
