package com.bulwark.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher, driven hard and without a device.
 *
 * Every case here is either a trap found in the real 2026-09-14 snapshot or a
 * failure mode that would present as **"nothing found"** - which is the one
 * output of this feature that must never be wrong by accident.
 */
class MonitoringIndicatorsTest {

    private companion object {
        const val CERT_A = "31a6ececd97cf39bc4126b8745cd94a7c30bf81c"
        const val CERT_B = "b374a75f87f992a6f57cf99a24197abceb17a1e7"

        val SPY = MonitoringIndicator(
            name = "TheTruthSpy",
            kind = MonitoringKind.STALKERWARE,
            packages = setOf("com.systemservice", "com.mxspy"),
            certificates = setOf(CERT_A),
        )
        val FAMILY = MonitoringIndicator(
            name = "FindMyKids",
            kind = MonitoringKind.WATCHWARE,
            packages = setOf("org.findmykids.app"),
            certificates = emptySet(),
        )
    }

    private fun matcher(vararg indicators: MonitoringIndicator) =
        MonitoringIndicators(indicators.toList())

    private fun app(name: String, cert: String? = null) = InstalledApp(name, cert)

    // -- the two signals -----------------------------------------------------

    @Test
    fun `an app is recognised by its package name`() {
        val found = matcher(SPY).match(listOf(app("com.systemservice")))
        assertEquals(1, found.size)
        assertEquals(listOf("TheTruthSpy"), found.single().names)
        assertEquals(setOf(MatchSignal.PACKAGE_NAME), found.single().signals)
    }

    /**
     * The case the certificate exists for. A renamed build has a package name
     * in no list on earth, and is the same software signed by the same key.
     */
    @Test
    fun `a renamed app is still recognised by its signing certificate`() {
        val found = matcher(SPY).match(listOf(app("com.totally.innocent", CERT_A)))
        assertEquals(1, found.size)
        assertEquals(setOf(MatchSignal.SIGNING_CERTIFICATE), found.single().signals)
        assertEquals(listOf("TheTruthSpy"), found.single().names)
    }

    @Test
    fun `matching both signals reports both`() {
        val found = matcher(SPY).match(listOf(app("com.systemservice", CERT_A)))
        assertEquals(
            setOf(MatchSignal.PACKAGE_NAME, MatchSignal.SIGNING_CERTIFICATE),
            found.single().signals,
        )
    }

    /** An unread certificate must not break the name match. */
    @Test
    fun `a null certificate still matches on name`() {
        val found = matcher(SPY).match(listOf(app("com.mxspy", null)))
        assertEquals(1, found.size)
    }

    // -- case sensitivity, both directions -----------------------------------

    /**
     * The reflex that would break it. Android package names are case-sensitive
     * and the snapshot lists `app.EasyLogger` and `app.Easylogger` separately
     * because both exist. Lower-casing would match a package that is not there.
     */
    @Test
    fun `package names are matched case-sensitively`() {
        val cased = MonitoringIndicator(
            "EasyLogger", MonitoringKind.STALKERWARE,
            packages = setOf("app.EasyLogger"), certificates = emptySet(),
        )
        assertEquals(1, matcher(cased).match(listOf(app("app.EasyLogger"))).size)
        assertTrue(
            "a different case is a different package",
            matcher(cased).match(listOf(app("app.easylogger"))).isEmpty(),
        )
    }

    /**
     * And the opposite rule for certificates. Upstream publishes upper-case
     * hex; Bulwark hashes to lower-case. A case-sensitive comparison here
     * would match nothing at all, on every phone, while looking correct.
     */
    @Test
    fun `certificates match regardless of the case they arrive in`() {
        val shouting = matcher(SPY).match(listOf(app("x", CERT_A.uppercase())))
        assertEquals(1, shouting.size)
        val mixed = matcher(SPY).match(listOf(app("x", CERT_A.replaceRange(0, 4, "31A6"))))
        assertEquals(1, mixed.size)
    }

    // -- what must never match -----------------------------------------------

    /** `com.soh` is a real seven-character entry. Substring rules would be a disaster. */
    @Test
    fun `matching is never by substring or prefix`() {
        val short = MonitoringIndicator(
            "Short", MonitoringKind.STALKERWARE,
            packages = setOf("com.soh"), certificates = emptySet(),
        )
        val innocent = listOf(
            app("com.sohu.news"), app("com.soho.crm"),
            app("prefix.com.soh"), app("com.so"),
        )
        assertTrue(
            "no ordinary app may be accused by a substring rule",
            matcher(short).match(innocent).isEmpty(),
        )
        assertEquals(1, matcher(short).match(listOf(app("com.soh"))).size)
    }

    @Test
    fun `an unrelated phone produces nothing`() {
        val clean = listOf(app("com.android.chrome", CERT_B.replace('b', 'c')), app("com.example"))
        assertTrue(matcher(SPY).match(clean).isEmpty())
    }

    @Test
    fun `an empty install list produces nothing and does not throw`() {
        assertTrue(matcher(SPY).match(emptyList()).isEmpty())
    }

    /** A list with no entries matches nothing rather than everything. */
    @Test
    fun `an empty indicator list matches nothing`() {
        assertTrue(MonitoringIndicators(emptyList()).match(listOf(app("com.systemservice"))).isEmpty())
    }

    // -- the ambiguity that actually exists in the data ----------------------

    /**
     * `org.findmykids.app` is claimed by both lists in the real snapshot, and
     * it is a genuine family-tracking app. Calling it stalkerware accuses an
     * ordinary product; calling it watchware could downplay a real one. The
     * matcher refuses to choose and says both.
     */
    @Test
    fun `an app claimed by both lists is reported as ambiguous, not as one of them`() {
        val alsoSpy = SPY.copy(packages = setOf("org.findmykids.app"))
        val found = matcher(alsoSpy, FAMILY).match(listOf(app("org.findmykids.app"))).single()

        assertTrue("the lists disagree and the finding must say so", found.ambiguous)
        assertEquals(
            setOf(MonitoringKind.STALKERWARE, MonitoringKind.WATCHWARE),
            found.kinds,
        )
        assertEquals(listOf("FindMyKids", "TheTruthSpy"), found.names)
    }

    @Test
    fun `a single-kind finding is not ambiguous`() {
        assertFalse(matcher(SPY).match(listOf(app("com.mxspy"))).single().ambiguous)
    }

    /** Five packages appear in more than one entry; each app gets one row. */
    @Test
    fun `an app matching several entries is one finding naming all of them`() {
        val other = SPY.copy(name = "Copy9", packages = setOf("com.systemservice"))
        val found = matcher(SPY, other).match(listOf(app("com.systemservice")))
        assertEquals("one installed app is one row", 1, found.size)
        assertEquals(listOf("Copy9", "TheTruthSpy"), found.single().names)
    }

    /** Matching the same entry by both signals must not name it twice. */
    @Test
    fun `an entry matched twice is named once`() {
        val found = matcher(SPY).match(listOf(app("com.systemservice", CERT_A)))
        assertEquals(listOf("TheTruthSpy"), found.single().names)
    }

    // -- stability -----------------------------------------------------------

    @Test
    fun `findings are ordered by package name so the screen does not reshuffle`() {
        val many = matcher(SPY, FAMILY).match(
            listOf(app("org.findmykids.app"), app("com.systemservice"), app("com.mxspy")),
        )
        assertEquals(
            listOf("com.mxspy", "com.systemservice", "org.findmykids.app"),
            many.map { it.packageName },
        )
    }

    /**
     * A phone with a great many apps must not be quadratic. Not a benchmark -
     * it fails by hanging, which is the failure worth catching.
     */
    @Test
    fun `a large phone is matched without scanning the list per app`() {
        val big = (1..5_000).map { app("com.example.app$it", "%040x".format(it)) }
        val found = matcher(SPY, FAMILY).match(big + app("com.mxspy"))
        assertEquals(1, found.size)
    }
}

/**
 * The words, held to the rules that make this feature safe to ship.
 *
 * Every assertion here is a sentence `threat-model.md` forbids, or one it
 * requires. The matcher being correct is worth nothing if the screen then says
 * the wrong thing about it.
 */
class MonitoringCopyTest {

    private companion object {
        val PLAIN = MonitoringFinding(
            packageName = "com.systemservice",
            names = listOf("TheTruthSpy"),
            kinds = setOf(MonitoringKind.STALKERWARE),
            signals = setOf(MatchSignal.PACKAGE_NAME),
        )
        val BY_CERT = PLAIN.copy(signals = setOf(MatchSignal.SIGNING_CERTIFICATE))
        val DISPUTED = PLAIN.copy(
            packageName = "org.findmykids.app",
            names = listOf("FindMyKids", "SomeTracker"),
            kinds = setOf(MonitoringKind.STALKERWARE, MonitoringKind.WATCHWARE),
        )
    }

    // -- what must never be said ---------------------------------------------

    /** A match is not a verdict, and the copy may not promote it to one. */
    @Test
    fun `the copy never asserts that an app is spying`() {
        val text = monitoringHeadline(listOf(PLAIN)) + " " + monitoringDetail(listOf(PLAIN))
        listOf("is spying", "is watching you", "is stalkerware", "someone is reading")
            .forEach { assertTrue("must not claim \"$it\": $text", !text.contains(it, true)) }
        assertTrue("must say it is a match, not a verdict", text.contains("not a verdict"))
    }

    /** Removal is never the obvious next step, and never the first word. */
    @Test
    fun `the safety note warns before it mentions removing anything`() {
        val note = MONITORING_SAFETY_NOTE
        assertTrue(note.contains("can tell whoever installed it"))
        assertTrue("the danger must be named", note.contains("dangerous"))
        assertTrue("the Coalition is cited, not paraphrased", note.contains("Coalition Against Stalkerware"))
        assertTrue("the address is text so nothing opens a browser", note.contains("stopstalkerware.org"))
    }

    /**
     * It must cover both routes without assuming either. A match cannot say who
     * installed it, and SAI's majority case is remote compromise rather than a
     * partner.
     */
    @Test
    fun `the safety note does not assume an abusive partner`() {
        assertTrue(
            "must acknowledge it may have arrived another way",
            MONITORING_SAFETY_NOTE.contains("arrived some other way"),
        )
    }

    // -- the empty case, which is the dangerous one ---------------------------

    @Test
    fun `nothing matched is never dressed up as being safe`() {
        val text = monitoringHeadline(emptyList()) + " " + monitoringFooter()
        listOf("you are safe", "your phone is clean", "nothing is watching", "no monitoring software is")
            .forEach { assertTrue("must not reassure with \"$it\": $text", !text.contains(it, true)) }
        assertNull("nothing to qualify when nothing matched", monitoringDetail(emptyList()))
    }

    /** And it redirects to what actually catches an uncatalogued tool. */
    @Test
    fun `the footer names its own limits and points at the behavioural evidence`() {
        val footer = monitoringFooter()
        assertTrue("must admit a rename defeats it", footer.contains("renamed and re-signed"))
        assertTrue("must admit an uncatalogued tool defeats it", footer.contains("catalogued"))
        assertTrue("must point somewhere useful", footer.contains("rest of this screen"))
    }

    /**
     * **Counts only what is matchable.** The snapshot has 174 entries and 16
     * publish neither a package name nor a certificate; claiming 174 would be
     * the overclaim, and a footnote about 16 is precision nobody reads.
     */
    @Test
    fun `the footer claims only the entries that can actually be matched`() {
        val footer = monitoringFooter()
        assertTrue("must claim the matchable count", footer.contains("158"))
        assertTrue("must not claim the full upstream count", !footer.contains("174"))
        assertTrue("a bundled list must show its age", footer.contains(MonitoringIndicators.SNAPSHOT))
    }

    /** Could-not-check and nothing-found are different sentences. */
    @Test
    fun `an unreadable list says so and says it is not the same as finding nothing`() {
        assertTrue(MONITORING_UNREADABLE.contains("has not checked"))
        assertTrue(MONITORING_UNREADABLE.contains("not the same as finding nothing"))
    }

    // -- rows ----------------------------------------------------------------

    @Test
    fun `a certificate-only match explains why the rename did not help`() {
        assertTrue(BY_CERT.recognisedBy().contains("renaming it did not hide it"))
        assertTrue(PLAIN.recognisedBy().contains("package name"))
    }

    @Test
    fun `an ambiguous listing is explained, not resolved`() {
        val note = DISPUTED.ambiguityNote()!!
        assertTrue("both readings must survive", note.contains("family-tracking"))
        assertTrue(note.contains("Both can be true"))
        assertTrue("the question belongs to the person", note.contains("whether you chose it"))
    }

    @Test
    fun `an unambiguous finding gets no ambiguity note`() {
        assertNull(PLAIN.ambiguityNote())
    }

    /**
     * Whole sentences, not prefixes.
     *
     * The first version of this asserted `startsWith("1 app")`, which passed
     * while the screen said "1 app on this phone **match** a known monitoring
     * tool". A prefix assertion cannot see the verb, and nobody reads a prefix.
     */
    @Test
    fun `one and many are both said in plain English`() {
        assertEquals(
            "1 app on this phone matches a known monitoring tool.",
            monitoringHeadline(listOf(PLAIN)),
        )
        assertEquals(
            "2 apps on this phone match a known monitoring tool.",
            monitoringHeadline(listOf(PLAIN, DISPUTED)),
        )
    }
}
