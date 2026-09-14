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

    /**
     * Drives every branch, records what was written, and **models the
     * platform's precedence**: the uid entry wins unless it is `MODE_DEFAULT`.
     *
     * The first version let a package write override a uid entry, which is the
     * one thing that is not true - and it hid the bug that shipped on
     * 2026-09-14. A fake that does not model the rule under test cannot fail
     * the way the phone does.
     */
    private class FakeAccess(
        var door: SpecialAccessActions.Door = SpecialAccessActions.Door.PACKAGE,
        var sharing: List<String> = listOf(APP),
        /** The package entry as stored. Not the effective answer. */
        var packageEntry: Int = 0,
        /** The uid entry as stored. `MODE_DEFAULT` means there is none. */
        var uidModeValue: Int = 3,
        override val isSupported: Boolean = true,
        /** When set, writes are swallowed so the read-back sees no change. */
        var swallowWrites: Boolean = false,
    ) : SpecialAccessActions.Access {
        val writes = mutableListOf<String>()

        override fun opCode(opName: String) = CODE
        override fun uidOf(packageName: String, userId: Int) = UID
        override fun packagesSharingUid(uid: Int) = sharing
        override fun uidMode(code: Int, uid: Int) = uidModeValue
        override fun packageMode(code: Int, uid: Int, packageName: String) = packageEntry
        override fun door(code: Int, uid: Int) = door

        /** Exactly how `checkOperation` resolves it. */
        override fun mode(code: Int, uid: Int, packageName: String) =
            if (uidModeValue != 3) uidModeValue else packageEntry

        override fun setPackageMode(code: Int, uid: Int, packageName: String, mode: Int) {
            writes += "package:$packageName:$mode"
            if (!swallowWrites) packageEntry = mode
        }

        override fun setUidMode(code: Int, uid: Int, mode: Int) {
            writes += "uid:$uid:$mode"
            if (!swallowWrites) uidModeValue = mode
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
        val access = FakeAccess(packageEntry = 1)
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
            packageEntry = 0,
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

    /**
     * Reversible means back to what it was, not back to permitted.
     *
     * An op held at uid level with no package entry has to come back that way:
     * uid restored to its recorded value, package entry cleared to
     * `MODE_DEFAULT`. Writing `MODE_ALLOWED` through the package door would
     * leave state the phone never had - the residue found on a real app on
     * 2026-09-14.
     */
    @Test
    fun `giving it back restores both entries as they were, not as allowed`() {
        val access = FakeAccess(packageEntry = 1, uidModeValue = 1)
        val (actions, log) = journalAnd(access)

        actions.giveBack(APP, ALL_FILES, previousPackageMode = 3, previousUidMode = 0)

        // Both moved, so both are written - and to the recorded values, not to
        // MODE_ALLOWED.
        assertEquals(listOf("uid:$UID:0", "package:$APP:3"), access.writes)
        assertEquals(ActionKind.GRANT_SPECIAL_ACCESS, log.entries.first().kind)
        assertEquals(
            ActionKind.GRANT_SPECIAL_ACCESS,
            ActionKind.REVOKE_SPECIAL_ACCESS.undo,
        )
    }

    /** What is recorded is each stored entry, never the effective answer. */
    @Test
    fun `the recorded previous state is the package entry, not the effective mode`() {
        val access = FakeAccess(
            door = SpecialAccessActions.Door.UID,
            packageEntry = 3,  // nothing stored against the package
            uidModeValue = 0,  // the uid entry is what allows it
        )
        val (actions, log) = journalAnd(access)

        actions.revoke(APP, ALL_FILES)

        val attempt = log.entries.first()
        assertEquals("the package entry, not the effective mode", 3, attempt.previousState)
        assertEquals(0, attempt.previousUidState)
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

    /**
     * The undo that restored nothing and said it worked.
     *
     * Happened on hardware, 2026-09-14: a row written by an earlier build
     * carried a wrong `previous_uid_state`, the undo faithfully wrote it back,
     * both entries landed, and the app stayed denied while Bulwark recorded
     * SUCCEEDED. Verifying the *entries* would not have caught it - the writes
     * did what they were told. Only asking whether the access is usable again
     * does.
     */
    @Test
    fun `an undo that leaves the app still denied is a failure, not a success`() {
        // Recorded values that restore to "still ignored" - the garbage case.
        val access = FakeAccess(packageEntry = 0, uidModeValue = 1)
        val (actions, log) = journalAnd(access)

        val failed = assertThrows(IllegalStateException::class.java) {
            actions.giveBack(APP, ALL_FILES, previousPackageMode = 0, previousUidMode = 1)
        }

        assertTrue(failed.message!!.contains("still"))
        assertEquals(Phase.FAILED, log.entries.last().phase)
    }

    /** And Bulwark does not paper over it by inventing a grant. */
    @Test
    fun `a failed undo changes nothing beyond what was recorded`() {
        val access = FakeAccess(packageEntry = 0, uidModeValue = 1)
        val (actions, _) = journalAnd(access)

        runCatching { actions.giveBack(APP, ALL_FILES, previousPackageMode = 0, previousUidMode = 1) }

        // Both doors already hold the recorded values, so nothing is written at
        // all - and crucially there is no MODE_ALLOWED rescue afterwards.
        assertTrue("no door should have been written", access.writes.isEmpty())
    }


    /**
     * A door already holding the right value is left alone.
     *
     * Writing `MODE_DEFAULT` to a uid entry that does not exist *creates* one,
     * recording "no override" where the phone had nothing. Inert, and still not
     * the state it was in - found on hardware after an otherwise exact
     * clipboard round trip, 2026-09-14.
     */
    @Test
    fun `an undo does not write a door that is already correct`() {
        // Package-held and already revoked; the uid entry never existed.
        val access = FakeAccess(packageEntry = 1, uidModeValue = 3)
        val (actions, _) = journalAnd(access)

        actions.giveBack(APP, ALL_FILES, previousPackageMode = 0, previousUidMode = 3)

        // The package door only. No uid entry is invented.
        assertEquals(listOf("package:$APP:0"), access.writes)
    }

}
