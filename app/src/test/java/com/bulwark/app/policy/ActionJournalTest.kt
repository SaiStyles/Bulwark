package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo record, asserted.
 *
 * `safety-rules.md` rule 3 requires a *tested* undo before anything
 * destructive ships, and rule 5 requires the log. These tests are what lets
 * the next commit add `disable` without that being a leap of faith.
 *
 * The in-memory log below is deliberately not the SQLite one: every property
 * worth asserting here is about ordering and recovery, and forcing those onto
 * a device would mean they got run rarely instead of on every build.
 */
class ActionJournalTest {

    /** Minimal append-only [ActionLog]. Same contract, no device. */
    private class FakeLog(var failOn: Phase? = null) : ActionLog {
        val entries = mutableListOf<ActionRecord>()
        private var nextId = 1L

        override fun append(entry: NewEntry): Long {
            if (entry.phase == failOn) error("log is full")
            val id = nextId++
            entries += ActionRecord(
                id = id,
                atEpochMillis = id, // Monotonic and deterministic.
                packageName = entry.packageName,
                kind = entry.kind,
                phase = entry.phase,
                userId = entry.userId,
                previousState = entry.previousState,
                attemptId = entry.attemptId,
                detail = entry.detail,
                permission = entry.permission,
                appOp = entry.appOp,
                previousUidState = entry.previousUidState,
            )
            return id
        }

        override fun all(): List<ActionRecord> = entries.toList()
        override fun forPackage(packageName: String) = entries.filter { it.packageName == packageName }
    }

    @Test
    fun `the attempt is written before the action runs`() {
        // The whole reason this class exists. If the log were written
        // afterwards, a process killed mid-uninstall would leave no trace of
        // an operation that had already changed the phone.
        val log = FakeLog()
        var seenDuringAction: List<ActionRecord> = emptyList()

        ActionJournal(log).perform(ActionKind.DISABLE, "com.oem.bloat") {
            seenDuringAction = log.all()
        }

        assertEquals("attempt must exist while the action runs", 1, seenDuringAction.size)
        assertEquals(Phase.ATTEMPTED, seenDuringAction.single().phase)
        assertEquals("com.oem.bloat", seenDuringAction.single().packageName)
    }

    @Test
    fun `a successful action records attempt then outcome, linked`() {
        val log = FakeLog()
        val result = ActionJournal(log).perform(ActionKind.DISABLE, "com.oem.bloat") { "ok" }

        assertEquals("ok", result)
        val (attempt, outcome) = log.all()
        assertEquals(Phase.ATTEMPTED, attempt.phase)
        assertEquals(Phase.SUCCEEDED, outcome.phase)
        assertEquals("outcome must point at its attempt", attempt.id, outcome.attemptId)
    }

    @Test
    fun `a failing action is recorded and then rethrown`() {
        // Rule 6, fail closed. Swallowing here would let a caller iterate a
        // list of packages and quietly half-apply it.
        val log = FakeLog()
        val journal = ActionJournal(log)

        val thrown = runCatching {
            journal.perform(ActionKind.UNINSTALL, "com.oem.bloat") {
                error("Shizuku died")
            }
        }.exceptionOrNull()

        assertNotNull("must rethrow", thrown)
        val outcome = log.all().last()
        assertEquals(Phase.FAILED, outcome.phase)
        assertTrue("must say what went wrong: ${outcome.detail}", outcome.detail!!.contains("Shizuku died"))
    }

    @Test
    fun `a log failure never masks the action failure`() {
        // A caller told "could not write to the log" when the real event was
        // "uninstall failed" debugs the wrong thing.
        val log = FakeLog(failOn = Phase.FAILED)
        val thrown = runCatching {
            ActionJournal(log).perform(ActionKind.UNINSTALL, "com.oem.bloat") {
                error("the real problem")
            }
        }.exceptionOrNull()

        assertTrue(thrown!!.message!!.contains("the real problem"))
        assertEquals(
            "the logging failure must be attached, not dropped",
            1, thrown.suppressed.size,
        )
    }

    @Test
    fun `an interrupted action is found afterwards`() {
        // Simulates the process dying between the attempt and the outcome:
        // the attempt row exists, nothing closed it.
        val log = FakeLog()
        log.append(NewEntry("com.oem.bloat", ActionKind.UNINSTALL, Phase.ATTEMPTED, 0))

        val interrupted = ActionJournal(log).interrupted()
        assertEquals(1, interrupted.size)
        assertEquals("com.oem.bloat", interrupted.single().packageName)
    }

    @Test
    fun `a completed action is not reported as interrupted`() {
        val log = FakeLog()
        ActionJournal(log).perform(ActionKind.DISABLE, "com.oem.bloat") { }
        assertTrue(ActionJournal(log).interrupted().isEmpty())
    }

    @Test
    fun `a failed action is not reported as interrupted either`() {
        // It has an outcome. We know it did not apply, so there is nothing
        // ambiguous for the user to check by hand.
        val log = FakeLog()
        runCatching {
            ActionJournal(log).perform(ActionKind.DISABLE, "com.oem.bloat") { error("nope") }
        }
        assertTrue(ActionJournal(log).interrupted().isEmpty())
    }

    @Test
    fun `the previous enabled-state is carried into the undo`() {
        // Reversible means back to what it was, not back to enabled. A package
        // sitting at DISABLED_UNTIL_USED (4) must not come back as ENABLED.
        val log = FakeLog()
        ActionJournal(log).perform(
            ActionKind.DISABLE, "com.oem.bloat", userId = 0, previousState = 4,
        ) { }

        val plan = log.all().undoPlan().single()
        assertEquals(ActionKind.ENABLE, plan.kind)
        assertEquals("must restore the state we found", 4, plan.previousState)
    }

    @Test
    fun `the undo plan runs newest first`() {
        // Undo is a stack. Restoring in insertion order can put a dependency
        // back before the thing that needed it.
        val log = FakeLog()
        val journal = ActionJournal(log)
        journal.perform(ActionKind.DISABLE, "com.first") { }
        journal.perform(ActionKind.UNINSTALL, "com.second") { }

        val plan = log.all().undoPlan()
        assertEquals(listOf("com.second", "com.first"), plan.map { it.packageName })
        assertEquals(
            listOf(ActionKind.INSTALL_EXISTING, ActionKind.ENABLE),
            plan.map { it.kind },
        )
    }

    @Test
    fun `the undo plan ignores failed and interrupted actions`() {
        // A failed action changed nothing, so undoing it would be Bulwark
        // making an unrequested change. An interrupted one is ambiguous and
        // belongs in front of a human instead - rule 6.
        val log = FakeLog()
        val journal = ActionJournal(log)
        journal.perform(ActionKind.DISABLE, "com.worked") { }
        runCatching { journal.perform(ActionKind.DISABLE, "com.failed") { error("no") } }
        log.append(NewEntry("com.interrupted", ActionKind.UNINSTALL, Phase.ATTEMPTED, 0))

        val plan = log.all().undoPlan()
        assertEquals(listOf("com.worked"), plan.map { it.packageName })
    }

    @Test
    fun `history is per package when asked`() {
        val log = FakeLog()
        val journal = ActionJournal(log)
        journal.perform(ActionKind.DISABLE, "com.a") { }
        journal.perform(ActionKind.DISABLE, "com.b") { }

        assertEquals(2, log.forPackage("com.a").size) // attempt + outcome
        assertTrue(log.forPackage("com.nothing").isEmpty())
    }

    @Test
    fun `an empty log has nothing to undo and nothing outstanding`() {
        val log = FakeLog()
        assertTrue(log.all().undoPlan().isEmpty())
        assertTrue(log.all().unfinished().isEmpty())
        assertNull(log.all().firstOrNull())
        assertFalse(ActionJournal(log).history().isNotEmpty())
    }

    @Test
    fun `a permission action must record which permission`() {
        // Without it the undo knows the app and the intent and has nothing to
        // act on. The journal is the only door into the log, so this is the
        // one place the requirement cannot be forgotten by a second caller.
        val log = FakeLog()
        val journal = ActionJournal(log)

        val failure = runCatching {
            journal.perform(ActionKind.REVOKE_PERMISSION, "com.example") { }
        }.exceptionOrNull()

        assertTrue(
            "a permission action with no permission must be refused",
            failure is IllegalArgumentException,
        )
        assertTrue("and nothing may be recorded", log.all().isEmpty())
    }

    @Test
    fun `a whole-app action must not record a permission`() {
        // The other direction, and it matters as much: a DISABLE row naming a
        // permission describes something that did not happen, and the log is
        // the only account of what did.
        val log = FakeLog()
        val journal = ActionJournal(log)

        val failure = runCatching {
            journal.perform(
                ActionKind.DISABLE, "com.example",
                permission = "android.permission.CAMERA",
            ) { }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `the permission is on both the attempt and the outcome`() {
        // Two separate records, not a header and a continuation: whoever reads
        // one row must be able to tell what it is about.
        val log = FakeLog()
        val journal = ActionJournal(log)

        journal.perform(
            ActionKind.REVOKE_PERMISSION, "com.example",
            permission = "android.permission.CAMERA",
        ) { }

        assertEquals(2, log.all().size)
        assertTrue(log.all().all { it.permission == "android.permission.CAMERA" })
    }

    /**
     * The same rule the permission kinds have, for the same reason: an undo
     * that knows the app and the intent but not which op has nothing to act on.
     */
    @Test
    fun `a special-access action must record which app op it changed`() {
        val journal = ActionJournal(FakeLog())

        val refused = assertThrows(IllegalArgumentException::class.java) {
            journal.perform(
                kind = ActionKind.REVOKE_SPECIAL_ACCESS,
                packageName = "com.example.app",
            ) { }
        }
        assertTrue(refused.message!!.contains("must record which app op"))
    }

    @Test
    fun `an action that is not about an app op must not record one`() {
        val journal = ActionJournal(FakeLog())

        val refused = assertThrows(IllegalArgumentException::class.java) {
            journal.perform(
                kind = ActionKind.DISABLE,
                packageName = "com.example.app",
                appOp = "android:manage_external_storage",
            ) { }
        }
        assertTrue(refused.message!!.contains("must not record an app op"))
    }

    /**
     * Both previous modes are carried. An op can be held at uid or package
     * level and restoring only one leaves state the phone never had - which
     * happened on hardware before this field existed.
     */
    @Test
    fun `a special-access action records both the package and uid modes`() {
        val log = FakeLog()

        ActionJournal(log).perform(
            kind = ActionKind.REVOKE_SPECIAL_ACCESS,
            packageName = "com.example.app",
            previousState = 3,
            previousUidState = 0,
            appOp = "android:manage_external_storage",
        ) { }

        val attempt = log.entries.first()
        assertEquals("android:manage_external_storage", attempt.appOp)
        assertEquals(3, attempt.previousState)
        assertEquals(0, attempt.previousUidState)
    }

}
