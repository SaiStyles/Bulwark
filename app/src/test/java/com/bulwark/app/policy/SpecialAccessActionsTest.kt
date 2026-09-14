package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards in front of an app-op write, driven without a device.
 *
 * The one that matters most is the shared-uid refusal: `setUidMode` applies to
 * every package on that uid, so a revoke aimed at one app can silently take
 * the capability from several. Everything else here is the same contract
 * `PermissionActionsTest` holds its twin to.
 */
class SpecialAccessActionsTest {

    private companion object {
        const val ALL_FILES = "android:manage_external_storage"
        const val APP = "com.example.app"
        const val UID = 10231
        const val CODE = 92
    }

    /** Drives every branch, and records what was actually written. */
    private class FakeAccess(
        var door: SpecialAccessActions.Door = SpecialAccessActions.Door.PACKAGE,
        var sharing: List<String> = listOf(APP),
        var packageMode: Int = 0,
        var uidModeValue: Int = 3,
        override val isSupported: Boolean = true,
        /** When set, the write is swallowed so the read-back sees no change. */
        var swallowWrites: Boolean = false,
    ) : SpecialAccessActions.Access {
        val writes = mutableListOf<String>()

        override fun opCode(opName: String) = CODE
        override fun uidOf(packageName: String, userId: Int) = UID
        override fun packagesSharingUid(uid: Int) = sharing
        override fun mode(code: Int, uid: Int, packageName: String) = packageMode
        override fun uidMode(code: Int, uid: Int) = uidModeValue
        override fun door(code: Int, uid: Int) = door

        override fun setPackageMode(code: Int, uid: Int, packageName: String, mode: Int) {
            writes += "package:$packageName:$mode"
            if (!swallowWrites) packageMode = mode
        }

        override fun setUidMode(code: Int, uid: Int, mode: Int) {
            writes += "uid:$uid:$mode"
            if (!swallowWrites) {
                uidModeValue = mode
                packageMode = mode
            }
        }
    }

    private fun journalAnd(access: FakeAccess): Pair<SpecialAccessActions, FakeLog> {
        val log = FakeLog()
        return SpecialAccessActions(ActionJournal(log), access) to log
    }

    /** Minimal append-only log, same contract, no device. */
    private class FakeLog : ActionLog {
        val entries = mutableListOf<ActionRecord>()
        private var nextId = 1L
        override fun append(entry: NewEntry): Long {
            val id = nextId++
            entries += ActionRecord(
                id = id, atEpochMillis = id, packageName = entry.packageName,
                kind = entry.kind, phase = entry.phase, userId = entry.userId,
                previousState = entry.previousState, attemptId = entry.attemptId,
                detail = entry.detail, permission = entry.permission,
                appOp = entry.appOp, previousUidState = entry.previousUidState,
            )
            return id
        }
        override fun all() = entries.toList()
        override fun forPackage(packageName: String) = entries.filter { it.packageName == packageName }
    }

    // -- the shared-uid refusal ---------------------------------------------

    /**
     * The guard the feature rests on. Three apps on one uid means one
     * `setUidMode` changes all three, which is broader than anything the user
     * asked for and cannot be attributed afterwards.
     */
    @Test
    fun `a uid-held op on a shared uid is refused, not written`() {
        val access = FakeAccess(
            door = SpecialAccessActions.Door.UID,
            sharing = listOf(APP, "com.example.sibling", "com.example.other"),
        )
        val (actions, log) = journalAnd(access)

        val refused = assertThrows(SecurityException::class.java) {
            actions.revoke(APP, ALL_FILES)
        }

        assertTrue(refused.message!!.contains("com.example.sibling"))
        assertTrue(refused.message!!.contains("com.example.other"))
        assertTrue("nothing may be written", access.writes.isEmpty())
        assertTrue("and nothing may be logged", log.entries.isEmpty())
    }

    /** The ordinary case - one app on the uid - is unaffected. */
    @Test
    fun `a uid-held op on an unshared uid goes through the uid door`() {
        val access = FakeAccess(door = SpecialAccessActions.Door.UID, sharing = listOf(APP))
        val (actions, _) = journalAnd(access)

        actions.revoke(APP, ALL_FILES)

        assertEquals(listOf("uid:$UID:1"), access.writes)
    }

    /**
     * A package-held op never consults the sharing list: the package door
     * moves this app alone, so other apps on the uid are not affected and
     * refusing would be a refusal with no reason behind it.
     */
    @Test
    fun `a package-held op is written even when the uid is shared`() {
        val access = FakeAccess(
            door = SpecialAccessActions.Door.PACKAGE,
            sharing = listOf(APP, "com.example.sibling"),
        )
        val (actions, _) = journalAnd(access)

        actions.revoke(APP, ALL_FILES)

        assertEquals(listOf("package:$APP:1"), access.writes)
    }

    // -- the ordinary contract ----------------------------------------------

    @Test
    fun `revoking something already revoked does nothing and records nothing`() {
        val access = FakeAccess(packageMode = 1)
        val (actions, log) = journalAnd(access)

        actions.revoke(APP, ALL_FILES)

        assertTrue(access.writes.isEmpty())
        assertTrue("a row claiming a change that did not happen is a false record", log.entries.isEmpty())
    }

    /**
     * Rule 6. `setMode` returns void, and a write through the wrong door once
     * did nothing at all while looking exactly like success.
     */
    @Test
    fun `a write the platform accepts and ignores is reported, not believed`() {
        val access = FakeAccess(swallowWrites = true)
        val (actions, log) = journalAnd(access)

        val failed = assertThrows(IllegalStateException::class.java) {
            actions.revoke(APP, ALL_FILES)
        }

        assertTrue(failed.message!!.contains("accepted the change and did not make it"))
        // The attempt and the failure are both on the record.
        assertEquals(2, log.entries.size)
        assertEquals(Phase.FAILED, log.entries.last().phase)
    }

    @Test
    fun `both previous modes are recorded so the undo can put back what was there`() {
        val access = FakeAccess(
            door = SpecialAccessActions.Door.UID,
            packageMode = 0,
            uidModeValue = 0,
        )
        val (actions, log) = journalAnd(access)

        actions.revoke(APP, ALL_FILES)

        val attempt = log.entries.first()
        assertEquals(ActionKind.REVOKE_SPECIAL_ACCESS, attempt.kind)
        assertEquals(ALL_FILES, attempt.appOp)
        assertEquals(0, attempt.previousState)
        assertEquals(0, attempt.previousUidState)
    }

    @Test
    fun `giving it back is the inverse and is recorded as its own kind`() {
        val access = FakeAccess(packageMode = 1)
        val (actions, log) = journalAnd(access)

        actions.giveBack(APP, ALL_FILES)

        assertEquals(listOf("package:$APP:0"), access.writes)
        assertEquals(ActionKind.GRANT_SPECIAL_ACCESS, log.entries.first().kind)
        assertEquals(
            ActionKind.GRANT_SPECIAL_ACCESS,
            ActionKind.REVOKE_SPECIAL_ACCESS.undo,
        )
    }

    @Test
    fun `a phone with no app-op interface is refused rather than half-tried`() {
        val access = FakeAccess(isSupported = false)
        val (actions, log) = journalAnd(access)

        assertThrows(IllegalStateException::class.java) { actions.revoke(APP, ALL_FILES) }
        assertTrue(log.entries.isEmpty())
    }

    /** The never-remove list applies here exactly as it does everywhere else. */
    @Test
    fun `a refused package is refused before anything is read or written`() {
        val access = FakeAccess()
        val (actions, _) = journalAnd(access)

        assertThrows(IllegalArgumentException::class.java) {
            actions.revoke("not a package name!", ALL_FILES)
        }
        assertTrue(access.writes.isEmpty())
    }
}
