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
     * @return whatever [block] returned.
     * @throws Throwable whatever [block] threw, after recording the failure.
     */
    fun <T> perform(
        kind: ActionKind,
        packageName: String,
        userId: Int = 0,
        previousState: Int? = null,
        block: () -> T,
    ): T {
        val attemptId = log.append(
            NewEntry(
                packageName = packageName,
                kind = kind,
                phase = Phase.ATTEMPTED,
                userId = userId,
                previousState = previousState,
            )
        )

        val result = try {
            block()
        } catch (failure: Throwable) {
            recordOutcome(
                kind, packageName, userId, attemptId, Phase.FAILED,
                detail = failure.describe(),
                onFailure = failure,
            )
            throw failure
        }

        recordOutcome(kind, packageName, userId, attemptId, Phase.SUCCEEDED, detail = null)
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
