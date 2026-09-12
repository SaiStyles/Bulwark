package com.bulwark.app.debloat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offer-do-not-enforce rule, asserted.
 *
 * This decides what a stranger is shown about their own phone. Two properties
 * matter and both are tested: nothing that breaks the route back is ever
 * offered, and nothing else is silently withheld.
 */
class PackageCatalogTest {

    @Test
    fun `offers a documented safe package both ways`() {
        val c = catalog("com.oem.weather" to entry(RemovalRating.RECOMMENDED))
        val r = c.build(listOf("com.oem.weather"), setOf("com.oem.weather")).single()
        assertTrue(r.options.canDisable)
        assertTrue(r.options.canUninstall)
    }

    @Test
    fun `the packages that used to be refused are now offered`() {
        // Retired 2026-09-12. These were the hard floor; the phone now names
        // the critical ones itself and a ceremony stands in front of them,
        // rather than Bulwark deciding on the owner's behalf.
        val c = catalog()
        listOf("com.android.systemui", "com.android.phone", "com.google.android.networkstack")
            .forEach { name ->
                val r = c.build(listOf(name), setOf(name)).single()
                assertFalse("$name must no longer be refused", r.options.isRefused)
                assertTrue(r.options.canDisable)
            }
    }

    @Test
    fun `the one refusal explains itself`() {
        val r = catalog().build(listOf("com.bulwark.app"), emptySet()).single()
        assertTrue(r.options.refusal!!.isNotBlank())
        assertTrue(r.options.refusal!!.contains("cannot remove itself"))
    }

    @Test
    fun `the database no longer decides what may be uninstalled`() {
        // The rating used to gate the escalation - Recommended and Advanced
        // only. That was a stranger's verdict deciding what the owner of the
        // phone was allowed to want, and it went with the badges.
        val c = catalog("com.mediatek.ims" to entry(RemovalRating.RECOMMENDED))
        val r = c.build(listOf("com.mediatek.ims"), setOf("com.mediatek.ims")).single()
        assertTrue(r.isOffered)
        assertTrue(r.options.canUninstall)
        assertFalse(r.options.isRefused)
    }

    @Test
    fun `undocumented packages are OFFERED, with an honest label`() {
        // We give options; we do not enforce. Refusing everything unknown was
        // paternalism dressed as safety - 43 of the test device's packages are
        // just Lava software nobody has audited, not landmines.
        val r = catalog().build(listOf("com.pri.applock"), setOf("com.pri.applock")).single()
        assertTrue("must be offered", r.options.canDisable)
        assertTrue("and removable too, since 2026-09-12", r.options.canUninstall)
        assertTrue(
            "must say nobody documented it: ${r.options.warning}",
            r.options.warning!!.contains("Nobody has documented"),
        )
    }

    @Test
    fun `a risky package is described, not withheld`() {
        // The description is what Bulwark stands behind and it still appears.
        // The rating no longer decides anything - it is not shown at all.
        val c = catalog("com.android.mtp" to entry(RemovalRating.UNSAFE, "MTP Host"))
        val r = c.build(listOf("com.android.mtp"), setOf("com.android.mtp")).single()
        assertTrue(r.options.canDisable)
        assertTrue(r.options.canUninstall)
        assertTrue(r.options.warning!!.contains("MTP"))
    }

    @Test
    fun `sensitive but recoverable things warn instead of refusing`() {
        // Losing your SMS app is bad. It is not the same as a phone that
        // cannot dial emergency services, and treating them alike was wrong.
        val r = catalog().build(listOf("com.android.mms"), setOf("com.android.mms")).single()
        assertTrue("messaging must stay offerable", r.options.canDisable)
        assertTrue(r.options.warning!!.contains("two-factor", ignoreCase = true))
    }

    @Test
    fun `a user-installed app is never locked by the structural list`() {
        // Nova Launcher matches the "launcher" fragment, which exists to catch
        // OEM-renamed *system* launchers. Applied to an app someone installed
        // themselves it produced "on Bulwark's permanent never-remove list"
        // for a third-party app - the paternalism this project removed on
        // 2026-09-10, arriving back through the matching rule.
        val name = "com.teslacoilsw.launcher"
        val r = catalog().build(listOf(name), systemPackages = emptySet()).single()
        assertFalse("must not be refused", r.options.isRefused)
        assertTrue("must be offerable", r.options.canDisable)
    }

    @Test
    fun `dependency warnings only mention apps that are actually installed`() {
        // A warning about an app the user does not have is noise, and noise
        // trains people to dismiss warnings that matter.
        val c = catalog(
            "com.oem.engine" to entry(
                RemovalRating.ADVANCED,
                neededBy = listOf("com.oem.camera", "com.oem.notinstalled"),
            ),
        )
        val result = c.build(
            listOf("com.oem.engine", "com.oem.camera"),
            setOf("com.oem.engine", "com.oem.camera"),
        ).first { it.packageName == "com.oem.engine" }
        assertEquals(listOf("com.oem.camera"), result.neededByInstalled)
    }

    @Test
    fun `a disabled package is reported as not enabled`() {
        // Drives which single action the row offers. Getting this wrong shows
        // "Switch off" on something already off.
        val c = catalog("com.oem.bloat" to entry(RemovalRating.RECOMMENDED))
        val r = c.build(
            listOf("com.oem.bloat"),
            systemPackages = setOf("com.oem.bloat"),
            disabledPackages = setOf("com.oem.bloat"),
        ).single()
        assertFalse(r.isEnabled)
    }

    @Test
    fun `summary counts every package exactly once`() {
        val c = catalog(
            "com.a" to entry(RemovalRating.RECOMMENDED),
            "com.b" to entry(RemovalRating.UNSAFE),
            "com.android.phone" to entry(RemovalRating.ADVANCED),
        )
        val entries = c.build(
            listOf("com.a", "com.b", "com.android.phone", "com.unknown.thing"),
            emptySet(),
        )
        val s = c.summarise(entries)
        assertEquals(4, s.total)
        // Every package is either offered or refused, never both and never
        // neither. If this drifts, the summary is lying to the user about
        // their own phone.
        assertEquals(s.total, s.offered + s.refused)
    }

    private fun entry(
        rating: RemovalRating,
        description: String? = "does a thing",
        neededBy: List<String> = emptyList(),
    ) = UadEntry(rating, "Oem", description, neededBy)

    private fun catalog(vararg pairs: Pair<String, UadEntry>) =
        PackageCatalog(UadDatabase.of(pairs.toMap()))
}
