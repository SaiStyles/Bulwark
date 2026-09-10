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
        }

    /** True when this takes a capability away rather than giving it back. */
    val isDestructive: Boolean get() = this == DISABLE || this == UNINSTALL
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
        )
    }
}
