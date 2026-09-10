package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two invariants the log's usefulness rests on, checked by machine.
 *
 * `security.md`: an invariant a human must remember is a wish; an invariant a
 * machine enforces is a boundary. Both of these are the kind that a reasonable
 * person breaks by accident while adding a feature, so neither is left to a
 * comment.
 */
class ActionLogContractTest {

    @Test
    fun `every action has an undo, and undoing twice is a no-op`() {
        // Rule 3: every action must have a working, tested undo *before it
        // ships*. Adding a fifth ActionKind with no inverse fails here rather
        // than reaching a stranger's phone.
        ActionKind.entries.forEach { kind ->
            assertEquals(
                "${kind.name}'s undo must undo back to itself",
                kind, kind.undo.undo,
            )
            assertTrue(
                "${kind.name} and its undo must be different actions",
                kind != kind.undo,
            )
        }
    }

    @Test
    fun `every destructive action is paired with a restorative one`() {
        // The pairing is what makes "never a truly destructive path" true:
        // nothing that takes a capability away lacks the thing that gives it
        // back.
        ActionKind.entries.filter { it.isDestructive }.forEach { destructive ->
            assertFalse(
                "${destructive.name}'s undo must not itself be destructive",
                destructive.undo.isDestructive,
            )
        }
        assertEquals(
            "destructive and restorative kinds must balance",
            ActionKind.entries.count { it.isDestructive },
            ActionKind.entries.count { !it.isDestructive },
        )
    }

    @Test
    fun `the log interface offers no way to change or erase history`() {
        // Append-only, enforced rather than described. A log the code can
        // rewrite is not evidence of anything - and the run that most needs
        // the record is the run that went wrong, which is exactly when
        // "clean up the bad rows" looks like a reasonable idea.
        //
        // Checked by reflection because a comment does not stop anyone adding
        // `fun delete()` in six months.
        val forbidden = listOf("update", "delete", "clear", "remove", "purge", "truncate", "set")
        val offenders = ActionLog::class.java.declaredMethods
            .map { it.name }
            .filter { name -> forbidden.any { name.startsWith(it, ignoreCase = true) } }

        assertTrue(
            "ActionLog must stay append-only; found: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the only writer returns an id an outcome can point at`() {
        // Outcomes are linked to attempts by id. If append ever stopped
        // returning one, ActionJournal could not close the attempt it opened
        // and every action would read as interrupted.
        val append = ActionLog::class.java.getMethod("append", NewEntry::class.java)
        assertEquals(java.lang.Long.TYPE, append.returnType)
    }
}
