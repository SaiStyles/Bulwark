package com.bulwark.app.policy

/**
 * The only way to run a destructive action: through the log.
 *
 * `safety-rules.md` rule 5 says log every action. A logging *call* placed next
 * to an action is a convention, and conventions get forgotten in the diff that
 * adds the second action. This makes the log the thing you call to act at all
 * - there is no path that performs the work without recording it first,
 * because the work is a lambda passed to the recorder.
 *
 * ```
 * journal.perform(ActionKind.DISABLE, pkg, userId, previousState) {
 *     privileged.setEnabledSetting(pkg, DISABLED_USER, userId)
 * }
 * ```
 *
 * ## Order matters, and this is the order
 *
 * 1. `ATTEMPTED` is appended **and committed**.
 * 2. The block runs.
 * 3. `SUCCEEDED` or `FAILED` is appended.
 *
 * A process killed between 1 and 3 leaves an attempt with no outcome, which
 * [unfinished] finds and the UI reports: *"Bulwark was interrupted while
 * changing this. It may or may not have applied."* Logging after the fact
 * would leave nothing at all - and the run that dies mid-operation is
 * precisely the run a user needs the log for.
 *
 * ## Fail closed
 *
 * A failing block records `FAILED` and then **rethrows**. Rule 6: stop and
 * report, never continue as if it worked and never retry destructive
 * operations automatically. Swallowing here would let a caller iterate a list
 * and quietly half-apply it.
 *
 * Logging is best-effort *relative to the action*: if appending the outcome
 * throws, the original failure is what reaches the caller, with the logging
 * failure attached as a suppressed exception. Losing the outcome row is
 * recoverable - the attempt row is still there and shows as interrupted.
 */
class ActionJournal(private val log: ActionLog) {

    /**
     * Records the intent, runs [block], records how it went.
     *
     * @param previousState for [ActionKind.DISABLE], the enabled-state the
     *   package had before this call, so the undo restores what was there
     *   rather than assuming it was enabled.
     * @param permission required for the permission kinds, forbidden for the
     *   rest. Both directions are enforced: a row that names a permission for a
     *   whole-app action describes something that did not happen.
     * @return whatever [block] returned.
     * @throws Throwable whatever [block] threw, after recording the failure.
     */
    fun <T> perform(
        kind: ActionKind,
        packageName: String,
        userId: Int = 0,
        previousState: Int? = null,
        permission: String? = null,
        appOp: String? = null,
        previousUidState: Int? = null,
        block: () -> T,
    ): T {
        // A permission action that does not say which permission cannot be
        // undone - the undo would know the app and the intent and have nothing
        // to act on. Checked here rather than in the caller because this is the
        // only door into the log, so there is no second caller to forget it.
        require(!kind.isPermissionChange || permission != null) {
            "$kind must record which permission it changed"
        }
        require(kind.isPermissionChange || permission == null) {
            "$kind is not a permission change; it must not record one"
        }
        // The same rule for app ops, and for the same reason: an undo that
        // knows the app and the intent but not which op has nothing to act on.
        require(!kind.isSpecialAccessChange || appOp != null) {
            "$kind must record which app op it changed"
        }
        require(kind.isSpecialAccessChange || appOp == null) {
            "$kind is not a special-access change; it must not record an app op"
        }

        val attemptId = log.append(
            NewEntry(
                packageName = packageName,
                kind = kind,
                phase = Phase.ATTEMPTED,
                userId = userId,
                previousState = previousState,
                permission = permission,
                appOp = appOp,
                previousUidState = previousUidState,
            )
        )

        val result = try {
            block()
        } catch (failure: Throwable) {
            recordOutcome(
                kind, packageName, userId, attemptId, Phase.FAILED,
                detail = failure.describe(),
                permission = permission,
                onFailure = failure,
            )
            throw failure
        }

        recordOutcome(
            kind, packageName, userId, attemptId, Phase.SUCCEEDED,
            detail = null, permission = permission,
        )
        return result
    }

    /** Attempts with no outcome. Reported to the user, never auto-undone. */
    fun interrupted(): List<ActionRecord> = log.all().unfinished()

    /** Everything, oldest first - the export and the history screen. */
    fun history(): List<ActionRecord> = log.all()

    private fun recordOutcome(
        kind: ActionKind,
        packageName: String,
        userId: Int,
        attemptId: Long,
        phase: Phase,
        detail: String?,
        permission: String? = null,
        onFailure: Throwable? = null,
    ) {
        try {
            log.append(
                NewEntry(
                    packageName = packageName,
                    kind = kind,
                    phase = phase,
                    userId = userId,
                    attemptId = attemptId,
                    detail = detail,
                    // Repeated on the outcome row so a reader of one row knows
                    // what it is about. The rows are separate records, not a
                    // header and a continuation.
                    permission = permission,
                )
            )
        } catch (logFailure: Throwable) {
            // The action's own failure is the one the caller must see. Attach
            // this rather than replace it: a caller told "could not write to
            // the log" when the real event was "uninstall failed" would debug
            // the wrong thing.
            if (onFailure != null) onFailure.addSuppressed(logFailure) else throw logFailure
        }
    }

    /**
     * A short, bounded description of a failure.
     *
     * Bounded because this is written to a file the user may hand to someone
     * else, and an unbounded platform message can carry more of the device's
     * state than the user expects to be sharing.
     */
    private fun Throwable.describe(): String {
        val text = message?.take(MAX_DETAIL).orEmpty()
        return if (text.isBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private companion object {
        const val MAX_DETAIL = 300
    }
}
