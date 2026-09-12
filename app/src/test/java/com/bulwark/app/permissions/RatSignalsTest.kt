package com.bulwark.app.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection that does not cry wolf at its own instructions.
 *
 * Bulwark's onboarding switches on wireless debugging, which is also the
 * strongest signal of a Shizuku-architecture RAT. An app that flagged its own
 * setup as an attack would be wrong *and* untrustworthy - and the second is
 * worse, because a user who learns to dismiss this screen will dismiss the one
 * that mattered.
 *
 * So every test here checks the same two things: that the ordinary explanation
 * is offered first, and that the alarming reading is still stated plainly.
 */
class RatSignalsTest {

    private fun app(name: String, vararg accesses: Access) =
        AppAccess(name, accesses.toSet(), isSystem = false)

    private val debugging = setOf(DeviceSignal.WIRELESS_DEBUGGING_ON)

    @Test
    fun `a clean phone produces nothing`() {
        assertTrue(
            ratFindings(
                emptySet(), listOf(app("com.quiet")),
                shizukuRunning = false, overlayKnown = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `screen control alone is not a RAT signal`() {
        // Without the channel, this is just an accessibility tool - which the
        // audit already reports on its own terms. Flagging it here would put a
        // scare label on the app a disabled user depends on.
        val findings = ratFindings(
            emptySet(),
            listOf(app("com.some.reader", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)),
            shizukuRunning = false,
            overlayKnown = true,
        )
        assertTrue("no debugging channel, no finding", findings.isEmpty())
    }

    // ---- the gate, added 2026-09-12 ------------------------------------

    @Test
    fun `debugging plus screen control alone is NOT the loud finding`() {
        // The regression this gate exists to stop. Every Bulwark user has
        // wireless debugging on - we asked them to - and password managers,
        // screen readers and automation tools hold Accessibility. Firing the
        // attack wording here means firing it permanently, for most users,
        // which teaches them to ignore the one screen that has to be trusted.
        val finding = ratFindings(
            debugging,
            listOf(app("com.my.passwords", Access.ACCESSIBILITY)),
            shizukuRunning = true,
            overlayKnown = true,
        ).single()

        assertFalse(
            "must not call the ordinary case an attack: ${finding.whatWouldWorry}",
            finding.whatWouldWorry.contains("full shape of a known attack"),
        )
        assertTrue(
            "must say the third piece is missing: ${finding.whatWouldWorry}",
            finding.whatWouldWorry.contains("nothing here can do that"),
        )
        assertTrue(
            "must still leave the door open to a real concern",
            finding.whatWouldWorry.contains("com.my.passwords"),
        )
    }

    @Test
    fun `all three together is the loud finding`() {
        val finding = ratFindings(
            debugging,
            listOf(app("com.unknown.thing", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)),
            shizukuRunning = false,
            overlayKnown = true,
        ).single()

        assertTrue(finding.headline.contains("draw over it"))
        assertTrue(
            "must name the app so the user can go look",
            finding.whatWouldWorry.contains("com.unknown.thing"),
        )
        assertTrue(
            "must describe the actual attack",
            finding.whatWouldWorry.contains("pairs with the phone"),
        )
        assertTrue(
            "must say why the overlay is the deciding piece",
            finding.whatWouldWorry.contains("makes it invisible"),
        )
    }

    @Test
    fun `the loud finding names only the app that can do both`() {
        // Naming every Accessibility holder would drag the user's screen
        // reader into an attack sentence it has nothing to do with.
        val finding = ratFindings(
            debugging,
            listOf(
                app("com.innocent.reader", Access.ACCESSIBILITY),
                app("com.both", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
            ),
            shizukuRunning = false,
            overlayKnown = true,
        ).single()

        assertTrue(finding.whatWouldWorry.contains("com.both"))
        assertFalse(
            "the innocent one must not appear in the attack sentence",
            finding.whatWouldWorry.contains("com.innocent.reader"),
        )
    }

    @Test
    fun `unknown overlay is never reported as absent`() {
        // Overlay comes from app-ops, which needs Shizuku. With Shizuku down
        // the gate cannot be evaluated, and a gate that reads unknown as
        // "nothing there" goes quiet exactly when privilege is gone. That is
        // the failure this codebase keeps finding: a claim with no source,
        // wearing the face of a clean result.
        val finding = ratFindings(
            debugging,
            listOf(app("com.unknown.thing", Access.ACCESSIBILITY)),
            shizukuRunning = false,
            overlayKnown = false,
        ).single()

        assertTrue(
            "must say the check did not finish: ${finding.whatWouldWorry}",
            finding.whatWouldWorry.contains("could not finish this check"),
        )
        assertTrue(
            "must distinguish this from a clean result",
            finding.whatWouldWorry.contains("not the same as finding nothing"),
        )
        assertFalse(
            "must not claim the third piece is missing when it was never read",
            finding.whatWouldWorry.contains("nothing here can do that"),
        )
        assertTrue(
            "must say how to complete it",
            finding.whatWouldWorry.contains("Start Shizuku"),
        )
    }

    // ---- properties that hold in every state ---------------------------

    @Test
    fun `the innocent explanation always comes first`() {
        // The property that makes this feature usable rather than frightening.
        // Every finding, in every combination, must lead with the likely cause.
        val appSets = listOf(
            emptyList(),
            listOf(app("com.x", Access.ACCESSIBILITY)),
            listOf(app("com.x", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)),
        )
        for (apps in appSets) {
            for (shizuku in listOf(true, false)) {
                for (overlay in listOf(true, false)) {
                    ratFindings(debugging, apps, shizuku, overlay).forEach {
                        assertTrue(
                            "every finding needs an ordinary explanation: ${it.headline}",
                            it.innocentFirst.isNotBlank(),
                        )
                        assertFalse(
                            "must not accuse: ${it.innocentFirst}",
                            it.innocentFirst.contains("malware") ||
                                it.innocentFirst.contains("attack"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `never more than one finding`() {
        // The screen shows these as cards. Two at once turns a considered
        // statement into a wall of warnings.
        val appSets = listOf(
            emptyList(),
            listOf(app("com.x", Access.ACCESSIBILITY)),
            listOf(
                app("com.x", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
                app("com.y", Access.ACCESSIBILITY),
            ),
        )
        for (apps in appSets) {
            for (overlay in listOf(true, false)) {
                assertTrue(ratFindings(debugging, apps, false, overlay).size <= 1)
            }
        }
    }

    @Test
    fun `when Shizuku is running, debugging is called expected`() {
        // Bulwark is the reason it is on. Saying anything else would be the app
        // alarming a user about its own footprint.
        val finding = ratFindings(
            debugging, emptyList(), shizukuRunning = true, overlayKnown = true,
        ).single()
        assertTrue(finding.innocentFirst.contains("Expected"))
        assertTrue(finding.innocentFirst.contains("Shizuku"))
    }

    @Test
    fun `advice to switch debugging off states what it costs`() {
        // We proved Shizuku self-starts after a reboot BECAUSE wireless
        // debugging survives. Telling someone to switch it off without saying
        // that is advice with an unstated cost, and the honesty rules forbid
        // exactly that - the user is the one who gets to weigh it.
        val finding = ratFindings(
            debugging, emptyList(), shizukuRunning = false, overlayKnown = true,
        ).single()

        assertTrue("must give the safer option", finding.whatWouldWorry.contains("Switching it off"))
        assertTrue(
            "must name the cost: ${finding.whatWouldWorry}",
            finding.whatWouldWorry.contains("restart itself after a reboot"),
        )
        assertTrue(
            "must leave the choice with the user",
            finding.whatWouldWorry.contains("Your call"),
        )
        assertTrue(
            "must say why this one is the milder case",
            finding.whatWouldWorry.contains("Nothing here currently has"),
        )
    }

    @Test
    fun `developer options alone is not a finding`() {
        // A prerequisite, not a risk. Flagging it would fire on a large share
        // of enthusiast phones and teach people to ignore the screen.
        assertEquals(
            0,
            ratFindings(
                setOf(DeviceSignal.DEVELOPER_OPTIONS_ON),
                listOf(app("com.x", Access.ACCESSIBILITY)),
                shizukuRunning = false,
                overlayKnown = true,
            ).size,
        )
    }

    // ---- not saying the same thing twice ------------------------------

    @Test
    fun `the loud finding suppresses the per-app repeat of the same pairing`() {
        // Both halves of A2 flag Accessibility+overlay. On one screen, one
        // after the other, it reads as the app repeating itself - and
        // COMBINATIONS is kept short precisely because a list that repeats
        // trains people to skip it.
        val both = app("com.both", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
        val findings = ratFindings(debugging, listOf(both), false, overlayKnown = true)

        assertTrue("precondition: the loud finding fired", findings.single().apps.contains("com.both"))
        assertTrue(
            "the pairing already explained above must not be printed again",
            combinationsToShow(both, findings).none {
                it.accesses == setOf(Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
            },
        )
    }

    @Test
    fun `a pairing nobody explained is still printed`() {
        // Only the exact pairing already stated, and only for the app named.
        // An app that also reads notifications still gets that said.
        val both = app(
            "com.both",
            Access.ACCESSIBILITY, Access.DRAW_OVER_APPS, Access.NOTIFICATION_LISTENER,
        )
        val findings = ratFindings(debugging, listOf(both), false, overlayKnown = true)
        val shown = combinationsToShow(both, findings)

        assertTrue(
            "notification+screen control was never mentioned above",
            shown.any { it.accesses == setOf(Access.NOTIFICATION_LISTENER, Access.ACCESSIBILITY) },
        )
    }

    @Test
    fun `another app keeps its own combination`() {
        // Suppression is per app. A second app holding the same pairing was
        // not named above and must still be explained.
        val named = app("com.named", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
        val other = app("com.other", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
        val findings = listOf(
            RatFinding(
                "h", "i", "w",
                apps = listOf("com.named"),
                explains = setOf(Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
            ),
        )
        assertTrue(combinationsToShow(named, findings).isEmpty())
        assertTrue(combinationsToShow(other, findings).isNotEmpty())
    }

    @Test
    fun `with no findings nothing is suppressed`() {
        val both = app("com.both", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
        assertEquals(both.combinations().size, combinationsToShow(both, emptyList()).size)
    }

    @Test
    fun `several apps that can hide are all named`() {
        val finding = ratFindings(
            debugging,
            listOf(
                app("com.one", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
                app("com.two", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
            ),
            shizukuRunning = false,
            overlayKnown = true,
        ).single()
        assertTrue(finding.whatWouldWorry.contains("com.one"))
        assertTrue(finding.whatWouldWorry.contains("com.two"))
    }
}
