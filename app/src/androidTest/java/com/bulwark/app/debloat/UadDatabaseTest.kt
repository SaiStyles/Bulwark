package com.bulwark.app.debloat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith

/**
 * The bundled package database, parsed for real.
 *
 * `readableDescription` had unit tests; the parser that produces the strings it
 * formats had none, so a malformed or truncated asset would have surfaced as a
 * blank screen on someone's phone rather than a red build.
 *
 * Instrumented because the asset only exists inside the APK. That is also the
 * point: this reads the exact bytes that ship.
 */
@RunWith(AndroidJUnit4::class)
class UadDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Package names read straight from the asset.
     *
     * `UadDatabase` exposes `size`, `get` and `rating` and keeps its map
     * private, which is right - a test is not a reason to widen production
     * visibility. So aggregate checks read the key list here and ask the
     * database about each one through its real API.
     */
    private fun packageNames(): List<String> {
        val json = context.assets.open("uad-packages.json").use {
            it.readBytes().decodeToString()
        }
        val root = JSONObject(json)
        return root.keys().asSequence().toList()
    }

    @Test
    fun theBundledAssetParses() {
        val db = UadDatabase.load(context)
        // The count is evidence of a complete parse, not decoration: a
        // truncated asset would still parse and simply hold fewer entries.
        assertEquals(5372, db.size)
    }

    @Test
    fun aKnownEntryHasEverythingTheUiNeeds() {
        // com.google.android.as.oss is the entry that taught this project to
        // show whole descriptions: line one sounds removable, line three says
        // it is a dependency of System Intelligence.
        val entry = UadDatabase.load(context)["com.google.android.as.oss"]
        assertNotNull("the asset must still contain this", entry)
        assertEquals(RemovalRating.RECOMMENDED, entry!!.rating)
        assertTrue(
            "the description must still carry what breaks",
            entry.description!!.contains("dependency of System Intelligence"),
        )
        assertTrue(
            "and readableDescription must keep it",
            entry.description!!.readableDescription().contains("live caption"),
        )
    }

    @Test
    fun everyRatingValueInTheAssetIsRecognised() {
        // An unrecognised rating parses to UNKNOWN, which is offered for
        // disable. If upstream renamed a rating, thousands of packages would
        // silently become "nobody has documented this" and nothing would fail.
        val db = UadDatabase.load(context)
        val names = packageNames()
        val unknown = names.count { db.rating(it) == RemovalRating.UNKNOWN }
        assertTrue(
            "too many UNKNOWN ratings ($unknown of ${names.size}) - a rating " +
                "name upstream probably changed",
            unknown < names.size / 10,
        )
    }

    @Test
    fun unsafeEntriesExistAndCarryTheirReason() {
        // The two-guard design depends on these: our category list allowed 23
        // packages UAD calls Unsafe until that was measured.
        val db = UadDatabase.load(context)
        val unsafe = packageNames().mapNotNull { db[it] }
            .filter { it.rating == RemovalRating.UNSAFE }
        assertTrue("the asset must still mark things unsafe", unsafe.isNotEmpty())
        assertTrue(
            "an unsafe entry with no description would warn with nothing to say",
            unsafe.count { !it.description.isNullOrBlank() } > unsafe.size / 2,
        )
    }

    @Test
    fun theDatabaseIsParsedOnceAndCached() {
        // It is ~1 MB of JSON and used to be re-parsed on every refresh. The
        // KDoc told callers to hold the result and the call site did not, so
        // the holding now happens here.
        assertSame(UadDatabase.load(context), UadDatabase.load(context))
    }

    @Test
    fun anUnknownPackageIsUnknownRatherThanAnError() {
        val db = UadDatabase.load(context)
        assertEquals(RemovalRating.UNKNOWN, db.rating("com.does.not.exist.anywhere"))
    }
}
