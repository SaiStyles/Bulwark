package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The firewall's rules, asserted without a device.
 *
 * This layer changes no system state, so there is nothing to read back off the
 * platform and the log is the only record. That makes these tests the whole of
 * the evidence for the rule model - unusually load-bearing, and worth saying so.
 */
class FirewallActionsTest {

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

    private fun actions(
        log: FakeLog = FakeLog(),
        system: Set<String> = emptySet(),
    ) = FirewallActions(ActionJournal(log)) { it in system } to log

    @Test
    fun `blocking records the intent and shows up in the rules`() {
        val (act, log) = actions()

        act.block("com.example.app")

        assertEquals(setOf("com.example.app"), act.blocked())
        assertEquals(ActionKind.BLOCK_NETWORK, log.all().first().kind)
        assertEquals(Phase.SUCCEEDED, log.all().last().phase)
    }

    @Test
    fun `allowing takes it back out`() {
        val (act, _) = actions()
        act.block("com.example.app")

        act.allow("com.example.app")

        assertTrue(act.blocked().isEmpty())
    }

    @Test
    fun `last decision wins, so block-allow-block leaves one rule`() {
        val (act, _) = actions()
        act.block("com.example.app")
        act.allow("com.example.app")
        act.block("com.example.app")

        assertEquals(setOf("com.example.app"), act.blocked())
    }

    @Test
    fun `asking twice records once`() {
        // A rule already asked for is not a change, and a log full of
        // no-op entries makes the real history harder to read.
        val (act, log) = actions()
        act.block("com.example.app")
        act.block("com.example.app")

        assertEquals(2, log.all().size) // one attempt, one outcome
    }

    @Test
    fun `telephony is refused, because modern calling is data`() {
        // Reads like over-caution for a firewall until you notice that cutting
        // the IMS stack off the network can take voice calling with it -
        // including the emergency call safety-rules.md will not trade away.
        val (act, log) = actions(system = setOf("com.android.phone"))

        val failure = runCatching { act.block("com.android.phone") }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertTrue("nothing may be recorded", log.all().isEmpty())
        assertTrue(act.blocked().isEmpty())
    }

    @Test
    fun `a third-party app that merely looks like telephony is allowed`() {
        // The never-remove list applies its structural fragments to system
        // packages only. An app the user installed themselves called
        // "com.simplenote.android" must not be refused for containing "sim" -
        // the bug lesson 4 records, reached through a new door.
        val (act, _) = actions(system = emptySet())

        act.block("com.simplenote.android")

        assertEquals(setOf("com.simplenote.android"), act.blocked())
    }

    @Test
    fun `a malformed name is refused`() {
        val (act, log) = actions()

        val failure = runCatching { act.block("com.example; rm -rf /") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `an interrupted rule is not treated as in force`() {
        // An attempt with no outcome means Bulwark does not know what
        // happened. A firewall rule it is unsure about must not be enforced or
        // displayed as active - the screen would be claiming a protection
        // nobody can vouch for.
        val log = FakeLog()
        log.append(NewEntry("com.example.app", ActionKind.BLOCK_NETWORK, Phase.ATTEMPTED, 0))
        val act = FirewallActions(ActionJournal(log)) { false }

        assertTrue(act.blocked().isEmpty())
    }

    @Test
    fun `whole-app and permission history is ignored`() {
        // The log carries every layer. A disabled app is not a blocked one.
        val log = FakeLog()
        val id = log.append(NewEntry("com.example.app", ActionKind.DISABLE, Phase.ATTEMPTED, 0))
        log.append(NewEntry("com.example.app", ActionKind.DISABLE, Phase.SUCCEEDED, 0, attemptId = id))
        val act = FirewallActions(ActionJournal(log)) { false }

        assertTrue(act.blocked().isEmpty())
    }

    @Test
    fun `restore puts every rule back and reports per app`() {
        val (act, _) = actions()
        act.block("com.a")
        act.block("com.b")

        val steps = act.restoreEverything()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it.succeeded })
        assertTrue(act.blocked().isEmpty())
    }

    @Test
    fun `restore on a phone with no rules does nothing`() {
        val (act, _) = actions()
        assertTrue(act.restoreEverything().isEmpty())
    }

    @Test
    fun `there is no bulk block, and restore is the only exception`() {
        // Rule 1 again, and the firewall gets no special dispensation: the
        // amendment for bulk revoke was written for one permission across
        // chosen apps, and says nothing about cutting many apps off at once.
        val bulkShaped = listOf("all", "batch", "bulk", "each", "every", "across")
        // "allow" contains "all". A substring guard catching a word that
        // merely looks like its target is the exact bug ProtectedPackages
        // records as FRAGMENT_FALSE_FRIENDS, where "simplenote" matched "sim"
        // - reached here by a different door, and caught on the first run
        // because the test was written before the method list was tidy.
        val allowed = setOf("restoreEverything", "allow")

        val offenders = FirewallActions::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.contains('$') }
            .filter { name -> bulkShaped.any { name.contains(it, ignoreCase = true) } }
            .filterNot { it in allowed }

        assertFalse("no bulk block may exist; found: $offenders", offenders.isNotEmpty())
    }
}
