package com.bulwark.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence most likely to be wrong in the direction that hurts.
 *
 * A firewall rule changes no system state, so a screen showing "blocked" while
 * nothing enforces it is claiming a protection that does not exist. These
 * assert that Bulwark says the true thing in every state, including the
 * uncomfortable one.
 */
class FirewallStatusTest {

    @Test
    fun `no rules means nothing is claimed`() {
        assertEquals(
            FirewallState.NOTHING_BLOCKED,
            firewallState(ruleCount = 0, consentNeeded = false, vpnUp = false),
        )
        // Even with a tunnel up for some other reason.
        assertEquals(
            FirewallState.NOTHING_BLOCKED,
            firewallState(ruleCount = 0, consentNeeded = false, vpnUp = true),
        )
    }

    @Test
    fun `rules with no tunnel is not in force, and says so`() {
        // The state this whole design is arranged around: after every reboot,
        // and whenever the service is killed or consent withdrawn.
        val state = firewallState(ruleCount = 3, consentNeeded = false, vpnUp = false)
        assertEquals(FirewallState.NOT_IN_FORCE, state)
        assertFalse(state.isEnforcing)

        val headline = firewallHeadline(state, 3)
        assertTrue(
            "it must say the apps are not being stopped: $headline",
            headline.contains("nothing is stopping them"),
        )
        assertTrue(
            "and must not read as working: $headline",
            !headline.contains("blocked from the internet"),
        )
    }

    @Test
    fun `a lapsed firewall is never softened into a pause`() {
        // "Paused" would let someone close the app believing they were
        // covered. The word has to carry the fact.
        val detail = firewallDetail(FirewallState.NOT_IN_FORCE, lockdownOn = false)!!
        listOf("paused", "temporarily", "will resume").forEach {
            assertTrue("must not soften with \"$it\": $detail", !detail.contains(it, true))
        }
        assertTrue(detail.contains("can reach the internet"))
    }

    @Test
    fun `consent needed is distinct from not working`() {
        // Different problem, different fix. Collapsing them would tell someone
        // their firewall had failed when it had simply never been allowed to
        // start.
        assertEquals(
            FirewallState.NEEDS_CONSENT,
            firewallState(ruleCount = 2, consentNeeded = true, vpnUp = false),
        )
        // Consent outranks a tunnel someone else is running.
        assertEquals(
            FirewallState.NEEDS_CONSENT,
            firewallState(ruleCount = 2, consentNeeded = true, vpnUp = true),
        )
    }

    @Test
    fun `the working state still names both costs`() {
        // Warning only once it has lapsed is telling someone after it
        // mattered. The reboot gap and "stops sending, not collecting" are
        // attached to the state where things are going well.
        val detail = firewallDetail(FirewallState.IN_FORCE, lockdownOn = false)!!
        assertTrue("must say it does not stop collection: $detail",
            detail.contains("collecting"))
        assertTrue("must say it lapses at restart: $detail",
            detail.contains("restart"))
        assertTrue("must point at the fix: $detail",
            detail.contains("Always-on"))
    }

    @Test
    fun `with always-on it stops promising a gap it no longer has`() {
        val detail = firewallDetail(FirewallState.IN_FORCE, lockdownOn = true)!!
        assertTrue(detail.contains("across restarts"))
        assertTrue("no stale warning: $detail", !detail.contains("Turn on"))
    }

    @Test
    fun `nothing blocked needs no explanation`() {
        assertNull(firewallDetail(FirewallState.NOTHING_BLOCKED, lockdownOn = false))
    }

    @Test
    fun `every state that matters explains itself`() {
        FirewallState.entries
            .filter { it != FirewallState.NOTHING_BLOCKED }
            .forEach { assertNotNull("${it.name} must say something", firewallDetail(it, false)) }
    }

    @Test
    fun `one app reads as one app`() {
        assertTrue(firewallHeadline(FirewallState.IN_FORCE, 1).contains("1 app "))
        assertTrue(firewallHeadline(FirewallState.IN_FORCE, 4).contains("4 apps"))
    }

    @Test
    fun `nothing here claims containment`() {
        // A firewall stops transmission and never stops collection. Words that
        // imply otherwise are the reason layer 1 exists for apps you distrust.
        val overclaims = listOf("safe", "secure", "protected", "isolated", "cannot access")
        FirewallState.entries.forEach { state ->
            val text = firewallHeadline(state, 2) + " " + firewallDetail(state, false).orEmpty()
            overclaims.forEach {
                assertTrue("${state.name} must not claim \"$it\": $text",
                    !text.contains(it, ignoreCase = true))
            }
        }
    }
}
