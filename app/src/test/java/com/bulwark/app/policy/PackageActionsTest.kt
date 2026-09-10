package com.bulwark.app.policy

import com.bulwark.app.shizuku.PackageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the guards run in, asserted.
 *
 * Every one of these is a property that reads as obviously true and would fail
 * silently if the order changed: a refused package must not even be *read*, a
 * failed call must leave a record, and an undo must restore what was there
 * rather than what we assume was there.
 */
class PackageActionsTest {

    /** Records every privileged call, and can be told to fail. */
    private class FakeState(
        initial: Map<String, Int> = emptyMap(),
        var failOnSet: Boolean = false,
    ) : PackageActions.StateAccess {
        val states = initial.toMutableMap()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Triple<String, Int, String>>()

        override fun get(packageName: String, userId: Int): Int {
            reads += packageName
            return states[packageName] ?: PackageState.ENABLED
        }

        override fun set(packageName: String, state: Int, userId: Int, callingPackage: String) {
            if (failOnSet) error("Shizuku died")
            writes += Triple(packageName, state, callingPackage)
            states[packageName] = state
        }
    }

    private class FakeLog : ActionLog {
        val entries = mutableListOf<ActionRecord>()
        private var nextId = 1L
        override fun append(entry: NewEntry): Long {
            val id = nextId++
            entries += ActionRecord(
                id, id, entry.packageName, entry.kind, entry.phase,
                entry.userId, entry.previousState, entry.attemptId, entry.detail,
            )
            return id
        }
        override fun all() = entries.toList()
        override fun forPackage(packageName: String) = entries.filter { it.packageName == packageName }
    }

    private fun actions(state: FakeState, log: FakeLog = FakeLog()) =
        PackageActions(ActionJournal(log), "com.bulwark.app", state) to log

    @Test
    fun `disable records what it found, then sets DISABLED_USER`() {
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.ENABLED))
        val (act, log) = actions(state)

        act.disable("com.oem.bloat")

        assertEquals(
            "must use the user-disabled state, not DISABLED",
            PackageState.DISABLED_USER, state.writes.single().second,
        )
        assertEquals("com.bulwark.app", state.writes.single().third)
        val attempt = log.all().first()
        assertEquals(ActionKind.DISABLE, attempt.kind)
        assertEquals(PackageState.ENABLED, attempt.previousState)
    }

    @Test
    fun `a protected package is refused before it is even read`() {
        // Order matters. Reading state for a package we would refuse is a
        // privileged call made on behalf of a request that was never allowed.
        val state = FakeState()
        val (act, log) = actions(state)

        val thrown = runCatching { act.disable("com.android.phone") }.exceptionOrNull()

        assertTrue(thrown is SecurityException)
        assertTrue("must not have read state", state.reads.isEmpty())
        assertTrue("must not have written anything", state.writes.isEmpty())
        assertTrue("a refused action is not an action to log", log.all().isEmpty())
    }

    @Test
    fun `a malformed name is rejected as malformed`() {
        val state = FakeState()
        val (act, _) = actions(state)
        val thrown = runCatching {
            act.disable("com.evil; pm uninstall com.android.phone")
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(state.reads.isEmpty())
    }

    @Test
    fun `an already-disabled package is left alone and not logged`() {
        // Logging a no-op would put an entry in the user's history for
        // something that did not happen, and give undoLast something to undo.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.DISABLED_USER))
        val (act, log) = actions(state)

        act.disable("com.oem.bloat")

        assertTrue(state.writes.isEmpty())
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `a failed disable is recorded and rethrown`() {
        val state = FakeState(failOnSet = true)
        val (act, log) = actions(state)

        val thrown = runCatching { act.disable("com.oem.bloat") }.exceptionOrNull()

        assertTrue("rule 6: the caller must see it", thrown != null)
        assertEquals(Phase.FAILED, log.all().last().phase)
        assertTrue(log.all().last().detail!!.contains("Shizuku died"))
    }

    @Test
    fun `undo restores the state that was actually there, not enabled`() {
        // The property the whole previousState mechanism exists for. A package
        // the vendor shipped at DISABLED_UNTIL_USED must not be switched fully
        // on by an undo.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.DISABLED_UNTIL_USED))
        val (act, _) = actions(state)

        act.disable("com.oem.bloat")
        assertTrue("disable must have happened", state.writes.isNotEmpty())
        act.undoLast("com.oem.bloat")

        assertEquals(
            "must go back to DISABLED_UNTIL_USED, not ENABLED",
            PackageState.DISABLED_UNTIL_USED, state.states["com.oem.bloat"],
        )
    }

    @Test
    fun `a plain disable and undo round-trips`() {
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.ENABLED))
        val (act, _) = actions(state)

        act.disable("com.oem.bloat")
        assertEquals(PackageState.DISABLED_USER, state.states["com.oem.bloat"])

        assertTrue(act.undoLast("com.oem.bloat"))
        assertEquals(PackageState.ENABLED, state.states["com.oem.bloat"])
    }

    @Test
    fun `undo reads the log, not the caller's memory`() {
        // A user who force-stopped Bulwark mid-session must still be able to
        // undo, so the plan comes from the log rather than from UI state. This
        // asserts it by undoing through a *different* PackageActions instance
        // sharing only the log.
        val log = FakeLog()
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.ENABLED))
        PackageActions(ActionJournal(log), "com.bulwark.app", state)
            .disable("com.oem.bloat")

        val afterRestart = PackageActions(ActionJournal(log), "com.bulwark.app", state)
        assertTrue(afterRestart.undoLast("com.oem.bloat"))
        assertEquals(PackageState.ENABLED, state.states["com.oem.bloat"])
    }

    @Test
    fun `undo with nothing to undo says so rather than throwing`() {
        val (act, _) = actions(FakeState())
        assertFalse(act.undoLast("com.never.touched"))
    }

    @Test
    fun `a failed action leaves nothing to undo`() {
        // It changed nothing, so undoing it would be Bulwark making an
        // unrequested change of its own.
        val state = FakeState(failOnSet = true)
        val (act, _) = actions(state)
        runCatching { act.disable("com.oem.bloat") }

        state.failOnSet = false
        assertFalse("a failure is not a change", act.undoLast("com.oem.bloat"))
    }

    @Test
    fun `undo only ever touches the package it was asked about`() {
        val state = FakeState(
            mapOf("com.a" to PackageState.ENABLED, "com.b" to PackageState.ENABLED),
        )
        val (act, _) = actions(state)
        act.disable("com.a")
        act.disable("com.b")

        act.undoLast("com.a")

        assertEquals(PackageState.ENABLED, state.states["com.a"])
        assertEquals(
            "com.b must be untouched",
            PackageState.DISABLED_USER, state.states["com.b"],
        )
    }

    @Test
    fun `there is no bulk method to call`() {
        // safety-rules.md rule 1 forbids "remove everything matching a
        // pattern". The absence of the method is the enforcement, so this
        // asserts the absence rather than trusting a comment.
        val forbidden = listOf("all", "batch", "bulk", "each", "every")
        val offenders = PackageActions::class.java.declaredMethods
            .map { it.name }
            .filter { name -> forbidden.any { name.contains(it, ignoreCase = true) } }
        assertTrue("no bulk operations may exist; found: $offenders", offenders.isEmpty())
    }
}
