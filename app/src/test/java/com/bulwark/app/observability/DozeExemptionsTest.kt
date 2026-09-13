package com.bulwark.app.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against the shape the Agni 2 actually printed and against the
 * shapes some other phone will.
 *
 * `dumpsys` has no contract, so the cases that matter most here are the
 * malformed ones: what this must never do is quietly return a shorter list.
 */
class DozeExemptionsTest {

    /** Rows copied from the Agni 2, 2026-09-13. */
    private val realRows = listOf(
        "system-excidle,com.android.providers.calendar,10059",
        "system-excidle,com.android.vending,10114",
        "system,com.google.android.gms,10115",
        "user,com.whatsapp,10231",
    )

    @Test
    fun readsTheRowsThisPhonePrinted() {
        val reading = DozeExemptions.parse(realRows)

        assertEquals(4, reading.exemptions.size)
        assertEquals(0, reading.unreadable)
        assertEquals(
            listOf(
                "com.android.providers.calendar",
                "com.android.vending",
                "com.google.android.gms",
                "com.whatsapp",
            ),
            reading.exemptions.map { it.packageName },
        )
    }

    @Test
    fun systemAndSystemExcidleBothCountAsHavingComeWithThePhone() {
        val reading = DozeExemptions.parse(realRows)

        val preinstalled = reading.exemptions
            .filter { it.source == DozeExemptions.Source.PREINSTALLED }
            .map { it.packageName }

        assertEquals(
            listOf("com.android.providers.calendar", "com.android.vending", "com.google.android.gms"),
            preinstalled,
        )
        assertEquals(listOf("com.whatsapp"), reading.addedLater.map { it.packageName })
    }

    /**
     * The one that matters. A row this build does not understand must be
     * counted, not dropped - a short list presented as complete is a false
     * all-clear about someone's phone.
     */
    @Test
    fun rowsItCannotReadAreCountedRatherThanDropped() {
        val reading = DozeExemptions.parse(
            realRows + listOf(
                "this is not a row at all",
                "user,com.example.app",          // missing uid
                "user,,10231",                   // missing package
                "user,com.example.two,not-a-uid",
            ),
        )

        assertEquals(4, reading.exemptions.size)
        assertEquals(4, reading.unreadable)
        assertTrue(DozeExemptions.unreadableNotice(reading)!!.contains("4 lines"))
    }

    @Test
    fun anUnrecognisedSourceIsNotGuessedAt() {
        val reading = DozeExemptions.parse(listOf("vendor-magic,com.oem.thing,10400"))

        assertEquals(1, reading.exemptions.size)
        assertEquals(DozeExemptions.Source.UNRECOGNISED, reading.exemptions.single().source)
        // And it is not counted among the ones somebody added later, because
        // we do not know that.
        assertTrue(reading.addedLater.isEmpty())
    }

    @Test
    fun blankLinesAreNotParseFailures() {
        val reading = DozeExemptions.parse(realRows + listOf("", "   "))

        assertEquals(4, reading.exemptions.size)
        assertEquals(0, reading.unreadable)
        assertNull(DozeExemptions.unreadableNotice(reading))
    }

    @Test
    fun readingNothingIsDistinctFromAnEmptyList() {
        val nothingRead = DozeExemptions.parse(listOf("garbage", "more garbage"))
        val genuinelyEmpty = DozeExemptions.parse(emptyList())

        assertTrue(nothingRead.readNothing)
        assertTrue(!genuinelyEmpty.readNothing)
        assertEquals(
            "No app on this phone is exempt from sleeping.",
            DozeExemptions.headline(genuinelyEmpty),
        )
    }

    @Test
    fun theHeadlineCountsAndDoesNotAccuse() {
        val one = DozeExemptions.parse(listOf("user,com.whatsapp,10231"))
        assertEquals(
            "1 app is allowed to keep working while your phone sleeps.",
            DozeExemptions.headline(one),
        )
        assertEquals(
            "4 apps are allowed to keep working while your phone sleeps.",
            DozeExemptions.headline(DozeExemptions.parse(realRows)),
        )
    }

    /**
     * Bulwark reads this list and cannot edit it. The card has to say so:
     * offering a fix the app does not have is the failure `safety-rules.md`
     * calls worse than none.
     */
    @Test
    fun theDetailNeverImpliesBulwarkCanRemoveAnExemption() {
        val detail = DozeExemptions.detail(DozeExemptions.parse(realRows))!!

        assertTrue(detail.contains("cannot take an app off the list"))
        assertTrue(detail.contains("One of them was added after the phone shipped"))
    }

    /**
     * Found on the Agni 2, 2026-09-13, by looking at the card: half the list
     * was doubled.
     *
     * The platform keeps **two** allowlists - `system` and `system-excidle` -
     * and a package can sit on both, so it prints twice. Counting the rows
     * therefore over-reports how many apps are exempt, which is a wrong number
     * about someone's phone on a screen whose whole job is being right.
     */
    @Test
    fun anAppOnBothSystemListsIsOneAppNotTwo() {
        val reading = DozeExemptions.parse(
            listOf(
                "system-excidle,com.google.android.gms,10115",
                "system,com.google.android.gms,10115",
                "system-excidle,com.android.vending,10114",
            ),
        )

        assertEquals(2, reading.exemptions.size)
        assertEquals(
            listOf("com.android.vending", "com.google.android.gms"),
            reading.exemptions.map { it.packageName },
        )
        assertEquals(
            "2 apps are allowed to keep working while your phone sleeps.",
            DozeExemptions.headline(reading),
        )
    }

    /**
     * Same package on a system list and the user list. Both facts are true;
     * the one worth surfacing is that somebody added it after the phone
     * shipped, because that is the half a person might not know about.
     */
    @Test
    fun anAppOnBothASystemListAndTheUserListCountsAsAddedLater() {
        val reading = DozeExemptions.parse(
            listOf(
                "system,com.whatsapp,10231",
                "user,com.whatsapp,10231",
            ),
        )

        assertEquals(1, reading.exemptions.size)
        assertEquals(listOf("com.whatsapp"), reading.addedLater.map { it.packageName })
    }

    /** Two apps that merely share a name prefix are still two apps. */
    @Test
    fun differentPackagesAreNeverCollapsed() {
        val reading = DozeExemptions.parse(
            listOf(
                "system,com.example.one,10001",
                "system,com.example.two,10002",
            ),
        )

        assertEquals(2, reading.exemptions.size)
    }

    @Test
    fun thereIsNothingToAddWhenTheListIsEmpty() {
        assertNull(DozeExemptions.detail(DozeExemptions.parse(emptyList())))
    }
}
