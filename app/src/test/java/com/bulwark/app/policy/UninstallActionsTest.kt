package com.bulwark.app.policy

import com.bulwark.app.shizuku.PackageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation, driven without a device.
 *
 * `PackageRemoval` does the binder work and reads the result back; this covers
 * what the policy layer is responsible for - that the guard runs first, that
 * the state is recorded before the package goes away, and that a failure is
 * logged as a failure rather than swallowed.
 */
class UninstallActionsTest {

    private class FakeRemoval : PackageActions.Removal {
        val uninstalled = mutableListOf<String>()
        val restored = mutableListOf<String>()
        var failWith: Throwable? = null

        override fun uninstall(packageName: String, userId: Int, callingPackage: String) {
            failWith?.let { throw it }
            uninstalled += packageName
        }

        override fun installExisting(packageName: String, userId: Int) {
            failWith?.let { throw it }
            restored += packageName
        }
    }

    private class FakeState(private var value: Int = PackageState.DEFAULT) :
        PackageActions.StateAccess {
        override fun get(packageName: String, userId: Int) = value
        override fun set(packageName: String, state: Int, userId: Int, callingPackage: String) {
            value = state
        }
    }

    /** Same shape as the one in PackageActionsTest; kept local to this file. */
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
        override fun forPackage(packageName: String) =
            entries.filter { it.packageName == packageName }
    }

    private fun actions(
        log: FakeLog,
        removal: PackageActions.Removal? = null,
        state: PackageActions.StateAccess = FakeState(),
    ) = PackageActions(ActionJournal(log), "com.bulwark.app", state, removal)

    @Test
    fun uninstallRemovesThePackageAndRecordsIt() {
        val log = FakeLog()
        val removal = FakeRemoval()

        actions(log, removal).uninstall("com.example.bloat")

        assertEquals(listOf("com.example.bloat"), removal.uninstalled)
        val record = log.all().first { it.kind == ActionKind.UNINSTALL }
        assertEquals("com.example.bloat", record.packageName)
    }

    @Test
    fun theStateIsRecordedBeforeThePackageGoesAway() {
        // A restored package should come back to what Bulwark found, not to
        // whatever install-existing happens to leave behind. Once it is gone
        // the state is unreadable, so it has to be taken first.
        val log = FakeLog()

        actions(log, FakeRemoval(), FakeState(PackageState.DISABLED_USER))
            .uninstall("com.example.bloat")

        val record = log.all().first { it.kind == ActionKind.UNINSTALL }
        assertEquals(PackageState.DISABLED_USER, record.previousState)
    }

    @Test
    fun theGuardRunsBeforeAnythingIsRemoved() {
        val log = FakeLog()
        val removal = FakeRemoval()

        runCatching { actions(log, removal).uninstall("not a package name!!") }

        assertTrue("a malformed name must never reach the binder", removal.uninstalled.isEmpty())
    }

    @Test
    fun anEscalationNobodyWiredFailsLoudlyRatherThanDoingNothing() {
        // A silent no-op here would tell the user an app was removed while it
        // kept running, and write that into the log as a success.
        val thrown = runCatching { actions(FakeLog(), removal = null).uninstall("com.example.x") }
            .exceptionOrNull()

        assertNotNull("it must throw", thrown)
        assertTrue(
            "and say why: ${thrown?.message}",
            thrown!!.message!!.contains("No Removal wired"),
        )
    }

    @Test
    fun aFailedUninstallIsRecordedAsAFailureAndRaised() {
        val log = FakeLog()
        val removal = FakeRemoval().apply { failWith = IllegalStateException("still installed") }

        val thrown = runCatching { actions(log, removal).uninstall("com.example.bloat") }
            .exceptionOrNull()

        assertNotNull("rule 6: the caller must see this", thrown)
        val closed = log.all().filter { it.kind == ActionKind.UNINSTALL }
        assertFalse("the attempt must be on record", closed.isEmpty())
        assertTrue(
            "and it must not read as succeeded",
            closed.none { it.phase == Phase.SUCCEEDED },
        )
    }

    @Test
    fun putBackRestoresAndIsRecordedAsItsOwnAction() {
        val log = FakeLog()
        val removal = FakeRemoval()

        actions(log, removal).putBack("com.example.bloat")

        assertEquals(listOf("com.example.bloat"), removal.restored)
        assertTrue(log.all().any { it.kind == ActionKind.INSTALL_EXISTING })
    }
}
