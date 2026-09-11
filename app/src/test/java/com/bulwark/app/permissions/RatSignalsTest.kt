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

    @Test
    fun `a clean phone produces nothing`() {
        assertTrue(
            ratFindings(emptySet(), listOf(app("com.quiet")), shizukuRunning = false).isEmpty(),
        )
    }

    @Test
    fun `screen control alone is not a RAT signal`() {
        // Without the channel, this is just an accessibility tool - which the
        // audit already reports on its own terms. Flagging it here would put a
        // scare label on the app a disabled user depends on.
        val findings = ratFindings(
            emptySet(),
            listOf(app("com.some.reader", Access.ACCESSIBILITY)),
            shizukuRunning = false,
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `debugging plus screen control is the full shape`() {
        val finding = ratFindings(
            setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
            listOf(app("com.unknown.thing", Access.ACCESSIBILITY)),
            shizukuRunning = false,
        ).single()

        assertTrue(finding.headline.contains("control your screen"))
        assertTrue(
            "must name the app so the user can go look",
            finding.whatWouldWorry.contains("com.unknown.thing"),
        )
        assertTrue(
            "must describe the actual attack",
            finding.whatWouldWorry.contains("pairs with the phone"),
        )
    }

    @Test
    fun `the innocent explanation always comes first`() {
        // The property that makes this feature usable rather than frightening.
        // Every finding, in every combination, must lead with the likely cause.
        val combinations = listOf(
            Triple(setOf(DeviceSignal.WIRELESS_DEBUGGING_ON), emptyList<AppAccess>(), true),
            Triple(setOf(DeviceSignal.WIRELESS_DEBUGGING_ON), emptyList<AppAccess>(), false),
            Triple(
                setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
                listOf(app("com.x", Access.ACCESSIBILITY)),
                true,
            ),
            Triple(
                setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
                listOf(app("com.x", Access.ACCESSIBILITY)),
                false,
            ),
        )
        combinations.forEach { (signals, apps, shizuku) ->
            ratFindings(signals, apps, shizuku).forEach {
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

    @Test
    fun `when Shizuku is running, debugging is called expected`() {
        // Bulwark is the reason it is on. Saying anything else would be the app
        // alarming a user about its own footprint.
        val finding = ratFindings(
            setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
            emptyList(),
            shizukuRunning = true,
        ).single()
        assertTrue(finding.innocentFirst.contains("Expected"))
        assertTrue(finding.innocentFirst.contains("Shizuku"))
    }

    @Test
    fun `debugging alone still says to switch it off`() {
        val finding = ratFindings(
            setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
            emptyList(),
            shizukuRunning = false,
        ).single()
        assertTrue(finding.whatWouldWorry.contains("switching off"))
        assertTrue(
            "must say why this one is the milder case",
            finding.whatWouldWorry.contains("Nothing on this phone currently has"),
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
            ).size,
        )
    }

    @Test
    fun `several screen controllers are all named`() {
        val finding = ratFindings(
            setOf(DeviceSignal.WIRELESS_DEBUGGING_ON),
            listOf(
                app("com.first", Access.ACCESSIBILITY),
                app("com.second", Access.ACCESSIBILITY),
                app("com.unrelated", Access.USAGE_ACCESS),
            ),
            shizukuRunning = false,
        ).single()

        assertTrue(finding.whatWouldWorry.contains("com.first"))
        assertTrue(finding.whatWouldWorry.contains("com.second"))
        assertFalse(
            "an app without screen control is not part of this signal",
            finding.whatWouldWorry.contains("com.unrelated"),
        )
    }
}
