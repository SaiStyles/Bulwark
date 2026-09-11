package com.bulwark.app.firewall

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the tunnel actually come up, and does it come down again.
 *
 * The second test in this project that **changes a real phone**, and gated the
 * same way as the first: it skips unless someone asks for it by name with
 * `-e bulwark.destructive true`. `connectedAndroidTest` would otherwise cut an
 * app off the network every time anyone ran the suite, and `safety-rules.md` is
 * explicit that a test runner is not a human who chose the package.
 *
 * ## Why this cannot be driven from a shell
 *
 * `NetworkBlockService` is declared with `android:permission="BIND_VPN_SERVICE"`,
 * so only the system may start it - no other app can operate Bulwark's
 * firewall, which is the point. Instrumentation runs in the target app's own
 * process, so it can do what the UI does.
 *
 * What it deliberately does **not** cover is the authentication prompt in front
 * of the real button. That needs a fingerprint, which a test does not have.
 *
 * ## The target
 *
 * `com.jio.myjio`, approved by SAI and recorded in
 * `context/devices/lava-agni-2.md`. A constant, not a parameter, so this cannot
 * be pointed at an app nobody agreed to.
 */
@RunWith(AndroidJUnit4::class)
class TunnelOnHardware {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireDeliberateRun() {
        assumeTrue(
            "SKIPPED: this cuts a real app off the network. Run it deliberately " +
                "with -e bulwark.destructive true.",
            InstrumentationRegistry.getArguments().getString("bulwark.destructive") == "true",
        )
    }

    @Test
    fun theTunnelComesUpForOneAppAndGoesDownAgain() {
        requireDeliberateRun()
        assumeTrue(
            "SKIPPED: Android has not been given VPN consent for Bulwark yet. " +
                "That is a user tap by design.",
            !Firewall.needsConsent(context),
        )

        try {
            Firewall.apply(context, setOf(TARGET))
            assertTrue(
                "the tunnel should be up within $WAIT_MS ms",
                waitFor(expected = true),
            )
        } finally {
            // Always put the phone back, even if the assertion above failed.
            Firewall.apply(context, emptySet())
        }

        // waitFor returns whether the state was REACHED, so this is assertTrue
        // even though the state being waited for is "down". Written as
        // assertFalse first time and the test failed against working code -
        // the messenger again.
        assertTrue(
            "an empty rule set must release the VPN slot rather than hold it",
            waitFor(expected = false),
        )
    }

    /**
     * Waits for the tunnel to reach [expected], because starting a service is
     * asynchronous and asserting immediately would test the messenger.
     */
    private fun waitFor(expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (NetworkBlockService.isTunnelUp == expected) return true
            Thread.sleep(POLL_MS)
        }
        return NetworkBlockService.isTunnelUp == expected
    }

    private companion object {
        /** Approved by SAI, 2026-09-11. Not a parameter, on purpose. */
        const val TARGET = "com.jio.myjio"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
