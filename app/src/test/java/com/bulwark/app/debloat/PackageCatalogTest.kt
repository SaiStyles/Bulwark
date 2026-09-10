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
    fun `things that break the route back are still refused outright`() {
        val c = catalog()
        listOf("com.android.systemui", "com.android.phone", "com.google.android.networkstack")
            .forEach { name ->
                val r = c.build(listOf(name), setOf(name)).single()
                assertTrue("$name must be refused", r.options.isRefused)
                assertFalse(r.options.canDisable)
            }
    }

    @Test
    fun `every refusal explains itself`() {
        val r = catalog().build(listOf("com.android.phone"), setOf("com.android.phone")).single()
        assertTrue(r.options.refusal!!.isNotBlank())
    }

    @Test
    fun `the hard floor wins even when the database says Recommended`() {
        // Telephony breaks the route back: you cannot call for help about a
        // phone that cannot call. No database entry raises that floor.
        val c = catalog("com.mediatek.ims" to entry(RemovalRating.RECOMMENDED))
        val r = c.build(listOf("com.mediatek.ims"), setOf("com.mediatek.ims")).single()
        assertFalse(r.isOffered)
        assertTrue(r.options.isRefused)
    }

    @Test
    fun `undocumented packages are OFFERED, with an honest label`() {
        // We give options; we do not enforce. Refusing everything unknown was
        // paternalism dressed as safety - 43 of the test device's packages are
        // just Lava software nobody has audited, not landmines.
        val r = catalog().build(listOf("com.pri.applock"), setOf("com.pri.applock")).single()
        assertTrue("must be offered", r.options.canDisable)
        assertFalse("but not uninstallable", r.options.canUninstall)
        assertTrue(
            "must say nobody documented it: ${r.options.warning}",
            r.options.warning!!.contains("Nobody has documented"),
        )
    }

    @Test
    fun `a risky package can still be switched off, but never uninstalled`() {
        val c = catalog("com.android.mtp" to entry(RemovalRating.UNSAFE, "MTP Host"))
        val r = c.build(listOf("com.android.mtp"), setOf("com.android.mtp")).single()
        assertTrue(r.options.canDisable)
        assertFalse(r.options.canUninstall)
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
