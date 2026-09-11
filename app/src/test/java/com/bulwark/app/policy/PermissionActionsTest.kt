package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the guards run in, asserted - the permission half.
 *
 * The properties here are the ones that read as obviously true and fail
 * silently when the order changes: a refused package must not even be read, a
 * platform that ignores a call must not be reported as success, and a restore
 * must put back what Bulwark *found* rather than reversing its own last move.
 *
 * The privileged calls underneath are unverified on hardware. These tests
 * cannot fix that and do not pretend to - they assert the policy layer, which
 * is the half that can be checked without a phone.
 */
class PermissionActionsTest {

    private class FakeAccess(
        granted: Set<Pair<String, String>> = emptySet(),
        override var isSupported: Boolean = true,
    ) : PermissionActions.Access {
        val granted = granted.toMutableSet()
        val reads = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<Triple<String, String, Boolean>>()

        /** Pairs the platform accepts a call for and silently ignores. */
        var silentlyIgnores: Set<Pair<String, String>> = emptySet()

        /** Pairs whose call throws, for driving the failure paths. */
        var failFor: Set<Pair<String, String>> = emptySet()

        override fun isGranted(packageName: String, permission: String, userId: Int): Boolean {
            reads += packageName to permission
            return (packageName to permission) in granted
        }

        override fun revoke(packageName: String, permission: String, userId: Int) =
            change(packageName, permission, granting = false)

        override fun grant(packageName: String, permission: String, userId: Int) =
            change(packageName, permission, granting = true)

        private fun change(packageName: String, permission: String, granting: Boolean) {
            val pair = packageName to permission
            if (pair in failFor) error("Shizuku died")
            writes += Triple(packageName, permission, granting)
            // Records the call and changes nothing - exactly what the platform
            // does to a SYSTEM_FIXED permission on real hardware.
            if (pair in silentlyIgnores) return
            if (granting) granted += pair else granted -= pair
        }
    }

    private class FakeLog : ActionLog {
        val entries = mutableListOf<ActionRecord>()
        private var nextId = 1L
        override fun append(entry: NewEntry): Long {
            val id = nextId++
            entries += ActionRecord(
                id = id,
                atEpochMillis = id,
                packageName = entry.packageName,
                kind = entry.kind,
                phase = entry.phase,
                userId = entry.userId,
                previousState = entry.previousState,
                attemptId = entry.attemptId,
                detail = entry.detail,
                permission = entry.permission,
            )
            return id
        }
        override fun all() = entries.toList()
        override fun forPackage(packageName: String) = entries.filter { it.packageName == packageName }
    }

    private val camera = "android.permission.CAMERA"

    private fun actions(access: FakeAccess, log: FakeLog = FakeLog()) =
        PermissionActions(ActionJournal(log), access) to log

    @Test
    fun `revoking records which permission, then takes it away`() {
        val access = FakeAccess(setOf("com.example.app" to camera))
        val (act, log) = actions(access)

        act.revoke("com.example.app", camera)

        assertEquals(
            listOf(Triple("com.example.app", camera, false)),
            access.writes,
        )
        val attempt = log.all().first()
        assertEquals(ActionKind.REVOKE_PERMISSION, attempt.kind)
        assertEquals(camera, attempt.permission)
        assertEquals(Phase.SUCCEEDED, log.all().last().phase)
    }

    @Test
    fun `a protected package is refused before anything is read`() {
        // The guard runs first, so a package on the never-remove list is never
        // even asked about. Stripping READ_PHONE_STATE from a telephony
        // component is another route to a phone that cannot call for help.
        val access = FakeAccess()
        val (act, log) = actions(access)

        val failure = runCatching {
            act.revoke("com.android.phone", "android.permission.READ_PHONE_STATE")
        }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertTrue("nothing may be read for a refused package", access.reads.isEmpty())
        assertTrue("and nothing logged", log.all().isEmpty())
    }

    @Test
    fun `a malformed package name is refused`() {
        val access = FakeAccess()
        val (act, _) = actions(access)

        val failure = runCatching { act.revoke("com.example; rm -rf /", camera) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `a device with no recognised permission API is refused, not guessed at`() {
        // Guardrail 1: the undo for a revoke is a grant. If the call cannot be
        // resolved, the action is not reversible, and an irreversible revoke
        // is not something to ship behind a reversible-sounding label.
        val access = FakeAccess(setOf("com.example.app" to camera), isSupported = false)
        val (act, log) = actions(access)

        val failure = runCatching { act.revoke("com.example.app", camera) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue("nothing may be attempted", access.writes.isEmpty())
        assertTrue("and nothing logged", log.all().isEmpty())
    }

    @Test
    fun `revoking something not held does nothing and records nothing`() {
        // A row saying we took away something the app never had is a false
        // record, and the log is the thing the user is meant to be able to
        // trust when something breaks.
        val access = FakeAccess()
        val (act, log) = actions(access)

        act.revoke("com.example.app", camera)

        assertTrue(access.writes.isEmpty())
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `a revoke the platform ignores is a failure, not a success`() {
        // Hardware, 2026-09-11: the call returns cleanly and the permission
        // stays granted. Without the read-back Bulwark tells someone the
        // microphone is gone while the app keeps recording, and writes that
        // into the log as a success.
        val access = FakeAccess(setOf("com.example.app" to camera))
        access.silentlyIgnores = setOf("com.example.app" to camera)
        val (act, log) = actions(access)

        val failure = runCatching { act.revoke("com.example.app", camera) }.exceptionOrNull()

        assertTrue("the caller must see this", failure is IllegalStateException)
        assertEquals(Phase.FAILED, log.all().last().phase)
        assertEquals(
            "the failure row must name the permission too",
            camera, log.all().last().permission,
        )
    }

    @Test
    fun `a failed call is recorded before it is rethrown`() {
        val access = FakeAccess(setOf("com.example.app" to camera))
        access.failFor = setOf("com.example.app" to camera)
        val (act, log) = actions(access)

        runCatching { act.revoke("com.example.app", camera) }

        assertEquals(2, log.all().size)
        assertEquals(Phase.ATTEMPTED, log.all().first().phase)
        assertEquals(Phase.FAILED, log.all().last().phase)
        assertTrue(log.all().last().detail!!.contains("Shizuku died"))
    }

    @Test
    fun `granting is the undo, and it is confirmed too`() {
        val access = FakeAccess()
        val (act, log) = actions(access)

        act.grant("com.example.app", camera)

        assertTrue((("com.example.app" to camera)) in access.granted)
        assertEquals(ActionKind.GRANT_PERMISSION, log.all().first().kind)
        assertEquals(
            "revoke and grant must be each other's undo",
            ActionKind.GRANT_PERMISSION, ActionKind.REVOKE_PERMISSION.undo,
        )
    }

    @Test
    fun `only the two permitted bulk shapes exist`() {
        // Rule 1 has exactly two written exceptions now: restore, and a revoke
        // of ONE permission across chosen apps. The allowlist is where those
        // stay honest - a third entry means someone widened the rule without
        // amending it.
        val bulkShaped = listOf("all", "batch", "bulk", "each", "every", "across")
        val allowed = setOf("restoreEverything", "revokeAcrossApps")

        val offenders = PermissionActions::class.java.declaredMethods
            // Kotlin emits `name$default` bridges for default arguments.
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filter { name -> bulkShaped.any { name.contains(it, ignoreCase = true) } }
            .filterNot { it in allowed }

        assertTrue("no other bulk shape may exist; found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `there is no bulk grant, in any shape`() {
        // The asymmetry is the point. Taking capability away in a batch is
        // permitted; handing it out in a batch is the direction an attacker
        // would want, and the grant is an undo besides.
        val grantShaped = PermissionActions::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            // Kotlin names a lambda inside grant() `grant$lambda$1` and does
            // not mark it synthetic, so the `$` filter is doing real work here
            // rather than being defensive - this test failed on it first run.
            .filterNot { it.contains('$') }
            .filter { it.contains("grant", ignoreCase = true) }

        assertEquals(
            "grant must exist exactly once, one app at a time: $grantShaped",
            listOf("grant"), grantShaped,
        )
    }

    @Test
    fun `a batch takes one permission and a list, so it cannot be mixed`() {
        // Structural, not a runtime check: a mixed batch cannot be expressed.
        // That is the condition rule 1 actually rests on - every change in the
        // batch is the same change, so the prompt can name it and the user can
        // remember it.
        val batch = PermissionActions::class.java.declaredMethods
            .single { it.name == "revokeAcrossApps" && !it.isSynthetic }

        assertEquals(String::class.java, batch.parameterTypes[0])
        assertEquals(List::class.java, batch.parameterTypes[1])
    }

    @Test
    fun `a batch revokes one permission from every app given`() {
        val access = FakeAccess(
            setOf("com.a" to camera, "com.b" to camera, "com.c" to camera),
        )
        val (act, log) = actions(access)

        val steps = act.revokeAcrossApps(camera, listOf("com.a", "com.b", "com.c"))

        assertEquals(3, steps.size)
        assertTrue(steps.all { it.succeeded })
        assertTrue(access.granted.isEmpty())
        // Every step logged individually: a half-finished batch is still a
        // readable history, which is one of rule 1's conditions.
        assertEquals(6, log.all().size)
    }

    @Test
    fun `a batch stops at the first failure instead of grinding on`() {
        // The opposite of the restore, and deliberately. This takes capability
        // away, so rule 6's fail-closed applies as written: if the privileged
        // path has died, changing nine more apps turns a bad situation into an
        // unattributable one.
        val access = FakeAccess(
            setOf("com.a" to camera, "com.b" to camera, "com.c" to camera),
        )
        access.failFor = setOf("com.b" to camera)
        val (act, _) = actions(access)

        val steps = act.revokeAcrossApps(camera, listOf("com.a", "com.b", "com.c"))

        assertEquals("it must not attempt the third", 2, steps.size)
        assertTrue(steps.first().succeeded)
        assertFalse(steps.last().succeeded)
        assertTrue("the untouched app keeps its permission", ("com.c" to camera) in access.granted)
    }

    @Test
    fun `a batch refuses an empty list rather than reading it as everything`() {
        val access = FakeAccess()
        val (act, _) = actions(access)

        val failure = runCatching { act.revokeAcrossApps(camera, emptyList()) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `a batch still refuses a protected package, and stops there`() {
        // Every step passes the same guards as a single action. A batch is not
        // a way around the never-remove list.
        val access = FakeAccess(setOf("com.a" to camera))
        val (act, _) = actions(access)

        val steps = act.revokeAcrossApps(
            camera, listOf("com.android.phone", "com.a"),
        )

        assertEquals(1, steps.size)
        assertFalse(steps.single().succeeded)
        assertTrue(steps.single().failure!!.contains("SecurityException"))
        assertTrue("nothing else may be touched", access.writes.isEmpty())
    }

    @Test
    fun `restore puts every changed permission back`() {
        val access = FakeAccess(
            setOf("com.a" to camera, "com.b" to "android.permission.RECORD_AUDIO"),
        )
        val (act, _) = actions(access)
        act.revoke("com.a", camera)
        act.revoke("com.b", "android.permission.RECORD_AUDIO")

        val steps = act.restoreEverything()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it.succeeded })
        assertTrue(("com.a" to camera) in access.granted)
        assertTrue(("com.b" to "android.permission.RECORD_AUDIO") in access.granted)
    }

    @Test
    fun `restore reports per permission, naming what it could not put back`() {
        // Rule 1 makes this a condition of bulk restore being allowed at all:
        // a user told everything was put back when one failed is worse off
        // than one told exactly which.
        val access = FakeAccess(setOf("com.a" to camera, "com.b" to camera))
        val (act, _) = actions(access)
        act.revoke("com.a", camera)
        act.revoke("com.b", camera)
        access.failFor = setOf("com.a" to camera)

        val steps = act.restoreEverything()

        assertEquals(2, steps.size)
        val failed = steps.single { !it.succeeded }
        assertEquals("com.a", failed.packageName)
        assertEquals(camera, failed.permission)
        assertTrue(failed.failure!!.contains("Shizuku died"))
        // Continues past the failure: stopping halfway through a restore
        // leaves more of the phone changed than finishing it.
        assertTrue(steps.single { it.succeeded }.packageName == "com.b")
    }

    @Test
    fun `restore names a failed permission step in words`() {
        val step = StepOutcome("com.a", camera, succeeded = false, failure = "nope")
        assertEquals("com.a (Camera)", step.describe)
        assertEquals("com.a", StepOutcome("com.a", null, succeeded = true).describe)
    }

    @Test
    fun `restore puts back what Bulwark found, not the reverse of its last move`() {
        // The bug hardware testing found in the disable path on 2026-09-10: an
        // undo of an undo performs a destructive action inside an operation
        // labelled as putting things back. Here Bulwark revoked, then granted
        // back; "put everything back" must leave it granted, which is the
        // state it found.
        val access = FakeAccess(setOf("com.a" to camera))
        val (act, _) = actions(access)
        act.revoke("com.a", camera)
        act.grant("com.a", camera)

        val steps = act.restoreEverything()

        assertEquals(1, steps.size)
        assertTrue("it must still be granted", ("com.a" to camera) in access.granted)
        assertEquals(
            "and nothing further may be written to the platform",
            2, access.writes.size,
        )
    }

    @Test
    fun `restore on a phone with no permission changes does nothing`() {
        val access = FakeAccess()
        val (act, _) = actions(access)
        assertTrue(act.restoreEverything().isEmpty())
    }

    @Test
    fun `restore ignores whole-app changes, which are another class's job`() {
        // Both halves run under one authentication at the UI edge. If this one
        // also tried to handle DISABLE rows it would report success for work
        // it never did.
        val log = FakeLog()
        log.append(
            NewEntry("com.a", ActionKind.DISABLE, Phase.ATTEMPTED, 0, previousState = 1)
        )
        log.append(
            NewEntry("com.a", ActionKind.DISABLE, Phase.SUCCEEDED, 0, attemptId = 1)
        )
        val access = FakeAccess()
        val act = PermissionActions(ActionJournal(log), access)

        assertTrue(act.restoreEverything().isEmpty())
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `an interrupted revoke is not restored, because it may never have happened`() {
        // An attempt with no outcome is ambiguous, and rule 6 says report
        // rather than act on a guess. Restoring it would be Bulwark granting a
        // permission back that it may never have taken.
        val log = FakeLog()
        log.append(
            NewEntry("com.a", ActionKind.REVOKE_PERMISSION, Phase.ATTEMPTED, 0, permission = camera)
        )
        val access = FakeAccess()
        val act = PermissionActions(ActionJournal(log), access)

        assertTrue(act.restoreEverything().isEmpty())
        assertFalse(("com.a" to camera) in access.granted)
    }
}
