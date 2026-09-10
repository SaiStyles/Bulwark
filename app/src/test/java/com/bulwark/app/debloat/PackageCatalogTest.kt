package com.bulwark.app.debloat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-guard rule, asserted.
 *
 * This is the logic that decides whether a stranger is shown a button that
 * removes part of their phone. It is worth more tests than it has code.
 */
class PackageCatalogTest {

    private fun entry(
        rating: RemovalRating,
        description: String? = "does a thing",
        neededBy: List<String> = emptyList(),
    ) = UadEntry(rating, "Oem", description, neededBy)

    private fun catalog(vararg pairs: Pair<String, UadEntry>) =
        PackageCatalog(UadDatabase.of(pairs.toMap()))

    @Test
    fun `offers a package both guards allow`() {
        val c = catalog("com.oem.weather" to entry(RemovalRating.RECOMMENDED))
        val result = c.build(listOf("com.oem.weather"), setOf("com.oem.weather")).single()
        assertTrue(result.isOffered)
        assertEquals(RemovalRating.RECOMMENDED, result.rating)
    }

    @Test
    fun `our guard wins even when the database says Recommended`() {
        // The floor is ours. No database entry can raise it - that is the
        // whole point of having a structural list underneath.
        val c = catalog("com.mediatek.ims" to entry(RemovalRating.RECOMMENDED))
        val result = c.build(listOf("com.mediatek.ims"), setOf("com.mediatek.ims")).single()
        assertFalse(result.isOffered)
        assertTrue(result.verdict is Verdict.Protected)
    }

    @Test
    fun `database Unsafe blocks a package our guard allows`() {
        // This is the case that justifies having a second guard at all.
        //
        // com.android.mtp is one of 11 packages on the real test device that
        // our structural list permits and the community database marks Unsafe.
        // It is not protected by shape - nothing in the name says "system
        // critical" - only by somebody having written down what it does.
        // A category list cannot hold that kind of knowledge, which is exactly
        // why it is not the only guard.
        val c = catalog("com.android.mtp" to entry(RemovalRating.UNSAFE, "MTP Host"))
        val result = c.build(listOf("com.android.mtp"), setOf("com.android.mtp")).single()

        assertFalse(result.isOffered)
        assertTrue(result.verdict is Verdict.TooRisky)
        assertTrue((result.verdict as Verdict.TooRisky).reason.contains("MTP"))
    }

    @Test
    fun `guard one now covers the bootloop cases it used to miss`() {
        // Cross-referencing against the real device on 2026-09-10 found 23
        // Unsafe packages our list allowed. The structural ones were added, so
        // these are now refused by guard one and never reach guard two.
        val c = catalog()
        listOf(
            "com.google.android.networkstack",
            "com.google.android.overlay.modules.modulemetadata.forframework",
            "com.mediatek",
        ).forEach { name ->
            val r = c.build(listOf(name), setOf(name)).single()
            assertTrue("$name must be protected", r.verdict is Verdict.Protected)
        }
    }

    @Test
    fun `a package nobody has documented is NOT offered`() {
        // 57 of the test device's 274 system packages land here. Silence is
        // not evidence of safety - safety-rules.md rule 1.
        val result = catalog().build(listOf("com.lava.mystery"), setOf("com.lava.mystery")).single()
        assertFalse(result.isOffered)
        assertEquals(Verdict.Unknown, result.verdict)
    }

    @Test
    fun `every refusal carries a reason`() {
        val c = catalog(
            "com.android.phone" to entry(RemovalRating.RECOMMENDED),
            "com.bad.thing" to entry(RemovalRating.UNSAFE, "bootloops the device"),
        )
        val results = c.build(
            listOf("com.android.phone", "com.bad.thing"),
            setOf("com.android.phone", "com.bad.thing"),
        )
        results.forEach { r ->
            val reason = when (val v = r.verdict) {
                is Verdict.Protected -> v.reason
                is Verdict.TooRisky -> v.reason
                else -> ""
            }
            assertTrue("refusal needs a reason: ${r.packageName}", reason.isNotBlank())
        }
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
    fun `offered packages sort first so the useful list is at the top`() {
        val c = catalog(
            "com.zzz.removable" to entry(RemovalRating.RECOMMENDED),
            "com.aaa.risky" to entry(RemovalRating.UNSAFE),
        )
        val names = c.build(
            listOf("com.aaa.risky", "com.zzz.removable"),
            setOf("com.aaa.risky", "com.zzz.removable"),
        ).map { it.packageName }
        assertEquals(listOf("com.zzz.removable", "com.aaa.risky"), names)
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
        assertEquals(s.total, s.offered + s.protected + s.tooRisky + s.unknown)
    }
}
