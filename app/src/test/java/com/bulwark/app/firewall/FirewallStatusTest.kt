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
        val detail = firewallDetail(FirewallState.NOT_IN_FORCE, alwaysOn = AlwaysOn.OFF, lockdown = false)!!
        listOf("paused", "temporarily", "will resume").forEach {
            assertTrue("must not soften with \"$it\": $detail", !detail.contains(it, true))
        }
        assertTrue(detail.contains("can reach the internet"))
    }

    /**
     * Reporting the lapse without saying what ends it leaves someone stuck.
     *
     * Opening Bulwark re-applies every rule it holds, by itself - `MainActivity`
     * does it on launch whenever at least one rule exists. This is the only
     * screen that reports the lapse, so it is the only place that sentence can
     * usefully go, and it was missing until 2026-09-14.
     */
    @Test
    fun `a lapsed firewall says what puts the blocks back`() {
        val detail = firewallDetail(FirewallState.NOT_IN_FORCE, alwaysOn = AlwaysOn.OFF, lockdown = false)!!
        assertTrue(
            "must say opening Bulwark restores the blocks: $detail",
            detail.contains("Opening Bulwark", true),
        )
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
        val detail = firewallDetail(FirewallState.IN_FORCE, alwaysOn = AlwaysOn.OFF, lockdown = false)!!
        assertTrue("must say it does not stop collection: $detail",
            detail.contains("collecting"))
        assertTrue("must say it lapses at restart: $detail",
            detail.contains("restart"))
        assertTrue("must point at the fix: $detail",
            detail.contains("Always-on"))
    }

    @Test
    fun `with always-on it stops promising a gap it no longer has`() {
        val detail = firewallDetail(FirewallState.IN_FORCE, alwaysOn = AlwaysOn.ON, lockdown = false)!!
        assertTrue(detail.contains("across restarts"))
        assertTrue("no stale warning: $detail", !detail.contains("closes that gap"))
    }

    @Test
    fun `nothing blocked needs no explanation`() {
        assertNull(firewallDetail(FirewallState.NOTHING_BLOCKED, alwaysOn = AlwaysOn.OFF, lockdown = false))
    }

    @Test
    fun `every state that matters explains itself`() {
        FirewallState.entries
            .filter { it != FirewallState.NOTHING_BLOCKED }
            .forEach { assertNotNull("${it.name} must say something", firewallDetail(it, alwaysOn = AlwaysOn.OFF, lockdown = false)) }
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
            val text = firewallHeadline(state, 2) + " " + firewallDetail(state, alwaysOn = AlwaysOn.OFF, lockdown = false).orEmpty()
            overclaims.forEach {
                assertTrue("${state.name} must not claim \"$it\": $text",
                    !text.contains(it, ignoreCase = true))
            }
        }
    }

    @Test
    fun `cannot-tell is never dressed up as off`() {
        // Measured on hardware 2026-09-12: on Android 12+ `always_on_vpn_app`
        // is @hide and unreadable, so CANNOT_TELL is the only answer we get
        // there. Collapsing it into OFF told anyone who HAD closed the reboot
        // gap that their protection lapsed anyway.
        val cannot = firewallDetail(FirewallState.IN_FORCE, AlwaysOn.CANNOT_TELL, false)!!
        val off = firewallDetail(FirewallState.IN_FORCE, AlwaysOn.OFF, false)!!
        assertTrue("the two must not read identically", cannot != off)

        assertTrue(
            "must say Bulwark cannot check it: $cannot",
            cannot.contains("does not let Bulwark check"),
        )
        assertTrue(
            "must make the lapse conditional, not flat: $cannot",
            cannot.contains("Unless you have turned on Always-on VPN"),
        )
    }

    @Test
    fun `cannot-tell still carries the lockdown instruction`() {
        // The one piece of advice that exists because Bulwark broke a phone.
        // It must not fall out of the branch people actually see.
        val cannot = firewallDetail(FirewallState.IN_FORCE, AlwaysOn.CANNOT_TELL, false)!!
        assertTrue(cannot.contains("Block connections without VPN"))
        assertTrue(cannot.contains("OFF"))
    }

    @Test
    fun `every always-on answer explains itself in the working state`() {
        AlwaysOn.entries.forEach {
            assertNotNull(
                "${it.name} must say something",
                firewallDetail(FirewallState.IN_FORCE, it, lockdown = false),
            )
        }
    }

    @Test
    fun `lockdown is warned about in every state, because it cuts the phone off`() {
        // Learned by doing it to a real phone on 2026-09-11. Bulwark
        // recommended "Block connections without VPN" to close the reboot gap.
        // Lockdown denies every app the VPN does not carry, and this tunnel
        // carries only the blocked ones - so the blocked app was blocked and
        // every other app on the device lost the network.
        FirewallState.entries.forEach { state ->
            val detail = firewallDetail(state, alwaysOn = AlwaysOn.OFF, lockdown = true)
            assertEquals(
                "${state.name} must warn about lockdown before anything else",
                LOCKDOWN_WARNING, detail,
            )
        }
    }

    @Test
    fun `the warning says what to do and does not condemn always-on with it`() {
        // Always-on alone is good and worth having; lockdown is the harmful
        // half. Telling someone to turn both off would cost them the only
        // thing that closes the reboot gap.
        assertTrue(LOCKDOWN_WARNING.contains("Turn it off"))
        assertTrue(LOCKDOWN_WARNING.contains("Always-on VPN on its own is fine"))
    }

    @Test
    fun `nothing recommends lockdown in any state`() {
        // The bug was advice, not code: the app told someone to switch this on.
        FirewallState.entries.forEach { state ->
            AlwaysOn.entries.forEach { alwaysOn ->
                val text = firewallDetail(state, alwaysOn, lockdown = false).orEmpty()
                assertTrue(
                    "${state.name} must not recommend lockdown: $text",
                    !text.contains("Block connections without VPN in Settings to"),
                )
                if (text.contains("Block connections")) {
                    assertTrue(
                        "if it mentions lockdown it must say OFF: $text",
                        text.contains("OFF"),
                    )
                }
            }
        }
    }
}
