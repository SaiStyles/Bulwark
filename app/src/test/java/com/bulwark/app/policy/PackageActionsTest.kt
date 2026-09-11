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
        /** Fail only these, so partial-failure paths can be driven. */
        var failFor: Set<String> = emptySet(),
    ) : PackageActions.StateAccess {
        val states = initial.toMutableMap()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Triple<String, Int, String>>()

        override fun get(packageName: String, userId: Int): Int {
            reads += packageName
            val stored = states[packageName] ?: PackageState.ENABLED
            return if (resolvesDefaultToEnabled && stored == PackageState.DEFAULT) {
                PackageState.ENABLED
            } else {
                stored
            }
        }

        /** Packages the platform silently refuses to change, like SYSTEM_FIXED. */
        var silentlyIgnores: Set<String> = emptySet()

        /** The platform reports DEFAULT as ENABLED for anything shipped on. */
        var resolvesDefaultToEnabled = false

        override fun set(packageName: String, state: Int, userId: Int, callingPackage: String) {
            if (failOnSet || packageName in failFor) error("Shizuku died")
            writes += Triple(packageName, state, callingPackage)
            // Records the call and changes nothing - exactly what `pm revoke`
            // does to a SYSTEM_FIXED permission on real hardware.
            if (packageName in silentlyIgnores) return
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
    fun `switchBackOn twice does not re-disable`() {
        // The bug hardware testing found on 2026-09-10. "Undo" undid the
        // previous undo, so a second press performed a DISABLE under a label
        // promising the opposite. switchBackOn asks for the state it wants
        // rather than for one step back, so pressing it again is a no-op in
        // effect rather than a reversal.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.ENABLED))
        val (act, _) = actions(state)

        act.disable("com.oem.bloat")
        act.switchBackOn("com.oem.bloat")
        assertEquals(PackageState.ENABLED, state.states["com.oem.bloat"])

        act.switchBackOn("com.oem.bloat")
        assertEquals(
            "a second press must not switch it off again",
            PackageState.ENABLED, state.states["com.oem.bloat"],
        )
    }

    @Test
    fun `switchBackOn restores the recorded state, not ENABLED`() {
        // pm enable sets ENABLED (1). A package that shipped at DEFAULT (0)
        // must come back to 0, or Bulwark has made a change of its own while
        // claiming to undo one. This is the exact case seen on the test
        // device, where com.android.egg sat at DEFAULT.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.DEFAULT))
        val (act, _) = actions(state)

        act.disable("com.oem.bloat")
        act.switchBackOn("com.oem.bloat")

        assertEquals(
            "must restore DEFAULT, not ENABLED",
            PackageState.DEFAULT, state.states["com.oem.bloat"],
        )
    }

    @Test
    fun `switchBackOn with no recorded disable falls back to the system default`() {
        // Never ENABLED: switching on something the vendor shipped off would
        // be Bulwark deciding for the user.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.DISABLED_USER))
        val (act, _) = actions(state)

        act.switchBackOn("com.oem.bloat")

        assertEquals(PackageState.DEFAULT, state.states["com.oem.bloat"])
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
    fun `a change the platform silently ignores is reported as a failure`() {
        // Found on hardware 2026-09-11: pm revoke on a SYSTEM_FIXED permission
        // returns no error and changes nothing. A privileged call that returns
        // without throwing has not necessarily done anything, and a package
        // fixed by the vendor or by policy behaves the same way.
        //
        // Without the read-back, Bulwark would tell someone it switched an app
        // off while the app kept running - and would write that lie into the
        // log as a success.
        val state = FakeState(mapOf("com.fixed.app" to PackageState.ENABLED))
        state.silentlyIgnores = setOf("com.fixed.app")
        val (act, log) = actions(state)

        val thrown = runCatching { act.disable("com.fixed.app") }.exceptionOrNull()

        assertTrue("must not report success", thrown != null)
        assertTrue(
            "must explain why: ${thrown!!.message}",
            thrown.message!!.contains("did not apply"),
        )
        assertEquals("and the log must say it failed", Phase.FAILED, log.all().last().phase)
        assertEquals(PackageState.ENABLED, state.states["com.fixed.app"])
    }

    @Test
    fun `restoring to DEFAULT accepts ENABLED as the same thing`() {
        // DEFAULT means "whatever the phone shipped with", so the platform
        // reports a package that shipped enabled as ENABLED after a restore to
        // DEFAULT. Treating that difference as a silent failure would report
        // every such restore as broken.
        val state = FakeState(mapOf("com.oem.bloat" to PackageState.DEFAULT))
        state.resolvesDefaultToEnabled = true
        val (act, log) = actions(state)

        act.disable("com.oem.bloat")
        act.switchBackOn("com.oem.bloat")

        assertTrue(
            "the restore must be recorded as a success",
            log.all().last().phase == Phase.SUCCEEDED,
        )
    }

    @Test
    fun `there is no bulk APPLY method, and restore is the only exception`() {
        // Rule 1 forbids bulk changes; it was amended on 2026-09-11 to permit
        // bulk *restore* only, in writing. This test is where that exception
        // stays honest - the shape is still guarded, and the allowlist has
        // exactly one entry.
        val bulkShaped = listOf("all", "batch", "bulk", "each", "every")
        val allowed = setOf("restoreEverything")

        val offenders = PackageActions::class.java.declaredMethods
            // Kotlin emits `name$default` bridges for default arguments. They
            // are not API and tripped this guard on the first run.
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filter { name -> bulkShaped.any { name.contains(it, ignoreCase = true) } }
            .filterNot { it in allowed }

        assertTrue("no bulk apply may exist; found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `restoreEverything puts back everything that was changed`() {
        val state = FakeState(
            mapOf("com.a" to PackageState.ENABLED, "com.b" to PackageState.DEFAULT),
        )
        val (act, _) = actions(state)
        act.disable("com.a")
        act.disable("com.b")

        val steps = act.restoreEverything()

        assertEquals(2, steps.size)
        assertTrue("all should have succeeded", steps.all { it.succeeded })
        assertEquals(PackageState.ENABLED, state.states["com.a"])
        assertEquals("must restore DEFAULT, not ENABLED", PackageState.DEFAULT, state.states["com.b"])
    }

    @Test
    fun `restoreEverything works newest first`() {
        // Undo is a stack. Restoring in insertion order can put a dependency
        // back before the thing that needed it.
        val state = FakeState(
            mapOf("com.first" to PackageState.ENABLED, "com.second" to PackageState.ENABLED),
        )
        val (act, _) = actions(state)
        act.disable("com.first")
        act.disable("com.second")

        assertEquals(
            listOf("com.second", "com.first"),
            act.restoreEverything().map { it.packageName },
        )
    }

    @Test
    fun `restoreEverything continues past a failure and reports it`() {
        // Rule 6 says fail closed, and for a destructive action that is right.
        // Here it is backwards: stopping halfway through a restore leaves MORE
        // of the phone changed than finishing does.
        val state = FakeState(
            mapOf("com.a" to PackageState.ENABLED, "com.b" to PackageState.ENABLED),
        )
        val (act, _) = actions(state)
        act.disable("com.a")
        act.disable("com.b")

        state.failFor = setOf("com.a")
        val steps = act.restoreEverything()

        assertEquals("must attempt both", 2, steps.size)
        val failed = steps.single { !it.succeeded }
        assertEquals("com.a", failed.packageName)
        assertTrue("must say why: ${failed.failure}", failed.failure!!.contains("Shizuku died"))
        assertTrue("the other must still have been restored", steps.single { it.succeeded }.packageName == "com.b")
        assertEquals(PackageState.ENABLED, state.states["com.b"])
    }

    @Test
    fun `restoreEverything touches each package once`() {
        // Restoring a package twice would undo the first restore.
        val state = FakeState(mapOf("com.a" to PackageState.ENABLED))
        val (act, _) = actions(state)
        act.disable("com.a")
        act.switchBackOn("com.a")
        act.disable("com.a")

        val steps = act.restoreEverything()

        assertEquals(1, steps.size)
        assertEquals(PackageState.ENABLED, state.states["com.a"])
    }

    @Test
    fun `restoreEverything on an untouched phone does nothing and says so`() {
        val state = FakeState()
        val (act, _) = actions(state)
        assertTrue(act.restoreEverything().isEmpty())
        assertTrue("must not have touched anything", state.writes.isEmpty())
    }

    @Test
    fun `restoreEverything ignores permission changes, which are another class's job`() {
        // A package whose only change was a revoked permission must not be
        // handed to switchBackOn: it would find nothing to enable, report a
        // success, and tell the user a permission had been put back when
        // nothing had. PermissionActions restores those; the UI runs both
        // under one authentication.
        val log = FakeLog()
        log.append(
            NewEntry(
                "com.example.app", ActionKind.REVOKE_PERMISSION, Phase.ATTEMPTED, 0,
                permission = "android.permission.CAMERA",
            )
        )
        log.append(
            NewEntry(
                "com.example.app", ActionKind.REVOKE_PERMISSION, Phase.SUCCEEDED, 0,
                attemptId = 1, permission = "android.permission.CAMERA",
            )
        )
        val state = FakeState()
        val act = PackageActions(ActionJournal(log), "com.bulwark.app", state)

        assertTrue("no package-level restore is owed here", act.restoreEverything().isEmpty())
        assertTrue("and nothing may be written", state.writes.isEmpty())
    }
}
