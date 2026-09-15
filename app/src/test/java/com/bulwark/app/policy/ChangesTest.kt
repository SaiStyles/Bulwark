package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list a person acts on to undo one thing at a time.
 *
 * The property that matters most: an attempt Bulwark never saw finish must not
 * appear here. Offering to reverse something that may never have happened is
 * the opposite of the guarantee this list exists to deliver.
 */
class ChangesTest {

    private var nextId = 1L

    /** An attempt and its outcome, the way the journal writes them. */
    private fun done(
        pkg: String,
        kind: ActionKind,
        permission: String? = null,
        at: Long = 0L,
    ): List<ActionRecord> {
        val attemptId = nextId++
        val attempt = ActionRecord(
            id = attemptId, atEpochMillis = at, packageName = pkg, kind = kind,
            phase = Phase.ATTEMPTED, userId = 0, previousState = null,
            attemptId = null, detail = null, permission = permission,
        )
        val outcome = attempt.copy(
            id = nextId++, phase = Phase.SUCCEEDED, attemptId = attemptId,
        )
        return listOf(attempt, outcome)
    }

    /** An attempt with no outcome. Bulwark does not know whether it landed. */
    private fun unfinished(pkg: String, kind: ActionKind, permission: String? = null) =
        listOf(
            ActionRecord(
                id = nextId++, atEpochMillis = 0, packageName = pkg, kind = kind,
                phase = Phase.ATTEMPTED, userId = 0, previousState = null,
                attemptId = null, detail = null, permission = permission,
            ),
        )

    @Test
    fun `a clean phone lists nothing and says so`() {
        assertTrue(emptyList<ActionRecord>().currentChanges().isEmpty())
        assertEquals(
            "Bulwark has not changed anything on this phone.",
            changesHeadline(emptyList()),
        )
    }

    @Test
    fun `each kind of change is listed`() {
        val log = done("com.a", ActionKind.DISABLE) +
            done("com.b", ActionKind.REVOKE_PERMISSION, "android.permission.CAMERA") +
            done("com.c", ActionKind.BLOCK_NETWORK)
        val changes = log.currentChanges()

        assertEquals(3, changes.size)
        assertEquals(
            setOf(ChangeKind.SWITCHED_OFF, ChangeKind.PERMISSION_TAKEN, ChangeKind.INTERNET_BLOCKED),
            changes.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `undone changes leave nothing behind`() {
        // Switch off then on, block then allow, revoke then grant. The phone is
        // back where it started and the list must agree.
        val log = done("com.a", ActionKind.DISABLE) + done("com.a", ActionKind.ENABLE) +
            done("com.c", ActionKind.BLOCK_NETWORK) + done("com.c", ActionKind.ALLOW_NETWORK) +
            done("com.b", ActionKind.REVOKE_PERMISSION, "P") +
            done("com.b", ActionKind.GRANT_PERMISSION, "P")
        assertTrue(log.currentChanges().isEmpty())
    }

    @Test
    fun `an unfinished attempt is never offered as undoable`() {
        // safety-rules rule 6. Bulwark does not know whether it landed, so
        // offering to reverse it would act on a guess.
        val log = unfinished("com.ghost", ActionKind.DISABLE)
        assertTrue(log.currentChanges().isEmpty())
    }

    @Test
    fun `two permissions from one app are two entries`() {
        // Undoing one must leave the other. Keying by package alone would
        // collapse them and quietly give back more than was asked.
        val log = done("com.a", ActionKind.REVOKE_PERMISSION, "android.permission.CAMERA") +
            done("com.a", ActionKind.REVOKE_PERMISSION, "android.permission.RECORD_AUDIO")
        val changes = log.currentChanges()

        assertEquals(2, changes.size)
        assertEquals(
            setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            changes.mapNotNull { it.permission }.toSet(),
        )
    }

    @Test
    fun `giving one permission back leaves the other taken`() {
        val log = done("com.a", ActionKind.REVOKE_PERMISSION, "CAMERA") +
            done("com.a", ActionKind.REVOKE_PERMISSION, "MIC") +
            done("com.a", ActionKind.GRANT_PERMISSION, "CAMERA")
        val changes = log.currentChanges()

        assertEquals(1, changes.size)
        assertEquals("MIC", changes.single().permission)
    }

    @Test
    fun `an app can be switched off and blocked at once, and both show`() {
        // Different kinds on the same package are different changes.
        val log = done("com.a", ActionKind.DISABLE) + done("com.a", ActionKind.BLOCK_NETWORK)
        assertEquals(2, log.currentChanges().size)
    }

    @Test
    fun `newest first`() {
        val log = done("com.old", ActionKind.DISABLE, at = 100) +
            done("com.new", ActionKind.DISABLE, at = 900)
        assertEquals(listOf("com.new", "com.old"), log.currentChanges().map { it.packageName })
    }

    @Test
    fun `the headline counts before anything is committed to`() {
        // "Put everything back" used to ask for a decision without showing what
        // the decision covered.
        val log = done("com.a", ActionKind.DISABLE) + done("com.b", ActionKind.DISABLE) +
            done("com.c", ActionKind.BLOCK_NETWORK)
        val line = changesHeadline(log.currentChanges())

        assertTrue(line, line.startsWith("3 changes:"))
        assertTrue(line, line.contains("2 apps switched off"))
        // "set to block", never "blocked" - a rule is not its enforcement, and
        // this headline has no way to know whether the tunnel is up.
        assertTrue(line, line.contains("1 app set to block"))
        assertTrue(line, !line.contains("1 app blocked"))
    }

    @Test
    fun `one change reads as one, not as 1 changes`() {
        val line = changesHeadline(done("com.a", ActionKind.DISABLE).currentChanges())
        assertTrue(line, line.startsWith("1 change:"))
        assertTrue(line, line.contains("1 app switched off"))
    }

    @Test
    fun `a blocked row never implies the block is in force`() {
        // The firewall card is where enforcement is stated. This list states
        // the rule. Conflating them would claim a protection that lapses.
        val blocked = Change("com.a", ChangeKind.INTERNET_BLOCKED)
        assertTrue(blocked.describe().contains("only while Bulwark's tunnel runs"))
    }

    @Test
    fun `a taken permission says the app can ask again`() {
        // REVOKE_IS_NOT_A_LOCK, in the place someone reviews their changes.
        val taken = Change("com.a", ChangeKind.PERMISSION_TAKEN, permission = "P")
        assertTrue(taken.describe().contains("can ask again"))
    }

    @Test
    fun `every kind has an undo and a label`() {
        ChangeKind.entries.forEach {
            assertTrue(it.name, Change("com.a", it).undoLabel().isNotBlank())
            // The mapping must exist for every kind, or a row appears with no
            // working button.
            assertTrue(it.name, it.undoKind.name.isNotBlank())
        }
    }

    @Test
    fun `switched off is described as reversible and data-keeping`() {
        val off = Change("com.a", ChangeKind.SWITCHED_OFF)
        assertTrue(off.describe().contains("Still installed"))
        assertTrue(off.describe().contains("data kept"))
    }
}
