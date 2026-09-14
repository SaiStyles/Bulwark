package com.bulwark.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled list itself, and the failure that must never look like success.
 *
 * `MonitoringIndicatorsTest` drives the matching rules without a device. What
 * needs one is everything about the **asset**: that it ships, that it parses,
 * that it holds what the copy claims it holds, and - the one that matters most
 * - that an unreadable asset comes back as `null` rather than as an empty list.
 *
 * That last case survived a deliberate mutation of the loader on 2026-09-14
 * because no unit test could reach it: a silent
 * `.getOrDefault(MonitoringIndicators(emptyList()))` passed the whole suite
 * while turning "Bulwark could not check" into "nothing found". This file is
 * the check that mutation should have failed.
 */
@RunWith(AndroidJUnit4::class)
class MonitoringIndicatorsOnDevice {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The instrumentation APK has its own assets and **does not** carry the
     * app's, so opening the list through it fails exactly the way a corrupt or
     * missing asset would. A real failure, not a mock of one.
     */
    private val contextWithoutTheAsset =
        InstrumentationRegistry.getInstrumentation().context

    @Test
    fun theBundledListShipsAndParses() {
        val loaded = MonitoringIndicators.load(appContext)
        assertNotNull("the asset did not parse, so nothing can be detected", loaded)
        assertEquals(
            "the bundled list no longer holds what the copy claims - regenerate " +
                "with tools/build-stalkerware-iocs.py and update the constants",
            MonitoringIndicators.MATCHABLE_ENTRIES,
            loaded!!.size,
        )
    }

    /**
     * **The silent failure.** An unreadable list must be `null`, never empty,
     * because the screen renders those two as different sentences and only one
     * of them is safe to be wrong about.
     */
    @Test
    fun anUnreadableListIsNullAndNeverAnEmptyOne() {
        // The loader caches, and other tests here populate it, so the cache is
        // cleared first - otherwise this would assert against a good load and
        // pass no matter what the failure path does.
        val cache = MonitoringIndicators::class.java
            .getDeclaredField("cached")
            .apply { isAccessible = true }
        val saved = cache.get(null)
        try {
            cache.set(null, null)
            val loaded = MonitoringIndicators.load(contextWithoutTheAsset)
            assertNull(
                "an unreadable list came back as ${loaded?.size} entries instead " +
                    "of null - an empty list renders as \"nothing found\", which " +
                    "is the one sentence this feature must never say by accident",
                loaded,
            )
        } finally {
            cache.set(null, saved)
        }
    }

    /**
     * Real data, not a fixture: the ambiguity is a property of the snapshot and
     * would be invisible in a hand-made list.
     */
    @Test
    fun theRealListStillDisagreesWithItselfAboutFindMyKids() {
        val loaded = MonitoringIndicators.load(appContext)!!
        val found = loaded.match(listOf(InstalledApp("org.findmykids.app", null)))

        assertEquals(1, found.size)
        assertTrue(
            "org.findmykids.app is claimed by both lists in this snapshot; if " +
                "that has stopped being true the copy about ambiguity needs a " +
                "second look rather than deleting",
            found.single().ambiguous,
        )
    }

    /** A known entry matches by name, through the real asset. */
    @Test
    fun aKnownPackageIsRecognisedThroughTheRealAsset() {
        val loaded = MonitoringIndicators.load(appContext)!!
        val found = loaded.match(listOf(InstalledApp("com.systemservice", null)))
        assertEquals(1, found.size)
        assertTrue(found.single().names.isNotEmpty())
    }

    /**
     * And an ordinary phone produces nothing. Run against everything actually
     * installed here, which is the closest thing to a false-positive test that
     * exists without a sample: any hit on this emulator is a hit on a stock
     * Google image, and would be a bug rather than a discovery.
     */
    @Test
    fun nothingOnAStockImageIsMistakenForMonitoringSoftware() {
        val loaded = MonitoringIndicators.load(appContext)!!
        val installed = appContext.packageManager
            .getInstalledPackages(0)
            .map { InstalledApp(it.packageName, null) }

        val found = loaded.match(installed)
        assertTrue(
            "a stock image matched $found - either the list has a false positive " +
                "or this device is not what it claims to be",
            found.isEmpty(),
        )
    }
}
