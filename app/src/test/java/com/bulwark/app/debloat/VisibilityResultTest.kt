package com.bulwark.app.debloat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe runs once, on hardware, and a decision gets made from what it
 * prints — whether Bulwark ever declares `QUERY_ALL_PACKAGES`.
 *
 * If the verdict logic is wrong, the number looks authoritative and the
 * conclusion is wrong with it. That is worth testing before the run, not
 * after.
 */
class VisibilityResultTest {

    private fun result(
        app: Set<String>,
        privileged: Set<String>,
        withUninstalled: Set<String> = privileged,
    ) = VisibilityResult(app, privileged, withUninstalled)

    @Test
    fun `privileged path seeing more means filtering is bypassed`() {
        val r = result(
            app = setOf("com.a", "com.b"),
            privileged = setOf("com.a", "com.b", "com.hidden", "com.oem.thing"),
        )
        assertTrue(r.filteringIsBypassed)
        assertEquals(listOf("com.hidden", "com.oem.thing"), r.hiddenFromApp)
        assertTrue(r.verdict.contains("does NOT reach"))
        assertTrue(r.verdict.contains("does not need QUERY_ALL_PACKAGES"))
    }

    @Test
    fun `identical counts are inconclusive, not a pass`() {
        // The dangerous failure mode: reading "no difference" as "we are fine".
        // It could equally mean filtering applies to both paths.
        val same = setOf("com.a", "com.b")
        val r = result(app = same, privileged = same)
        assertFalse(r.filteringIsBypassed)
        assertTrue(r.verdict.contains("Inconclusive"))
        assertTrue(
            "must warn against the wrong conclusion: ${r.verdict}",
            r.verdict.contains("do not conclude", ignoreCase = true),
        )
    }

    @Test
    fun `an empty privileged result is reported as probe failure, not as a finding`() {
        // Zero packages from a shell call means the call broke. Reporting that
        // as "no difference" would be a false negative dressed as data.
        val r = result(app = setOf("com.a"), privileged = emptySet())
        assertTrue(r.verdict.contains("probe failed", ignoreCase = true))
        assertFalse(r.filteringIsBypassed)
    }

    @Test
    fun `packages removed for this user are identified separately`() {
        // These are the debloat undo surface: still on /system, restorable
        // with pm install-existing.
        val r = result(
            app = setOf("com.a"),
            privileged = setOf("com.a"),
            withUninstalled = setOf("com.a", "com.removed.earlier"),
        )
        assertEquals(listOf("com.removed.earlier"), r.removedForThisUser)
    }

    @Test
    fun `hidden list is sorted so two runs can be compared`() {
        val r = result(
            app = emptySet(),
            privileged = setOf("com.z", "com.a", "com.m"),
        )
        assertEquals(listOf("com.a", "com.m", "com.z"), r.hiddenFromApp)
    }

    @Test
    fun `app seeing something privileged does not is not counted as hidden`() {
        // Should not happen, but the arithmetic must not go negative or
        // silently invent a bypass.
        val r = result(
            app = setOf("com.a", "com.onlyapp"),
            privileged = setOf("com.a"),
        )
        assertTrue(r.hiddenFromApp.isEmpty())
        assertFalse(r.filteringIsBypassed)
    }
}
