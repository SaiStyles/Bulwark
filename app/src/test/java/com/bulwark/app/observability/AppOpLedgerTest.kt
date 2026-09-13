package com.bulwark.app.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger parser, against output copied from the Agni 2 on 2026-09-13.
 *
 * The cases that matter are the ones a regex written from a single happy line
 * gets wrong: the `Reject:` variant with no space after the bracket, an access
 * that arrives before any package header, and the difference between "the app
 * was on screen" and "the app was not".
 */
class AppOpLedgerTest {

    /** Verbatim, including the indentation and the trailing space after `(allow):`. */
    private val realDump = """
        Current AppOps Service state:
          Uid 10142:
            state=cch
            appWidgetVisible=false
              RECORD_AUDIO: mode=foreground
            Package com.android.chrome:
              RECORD_AUDIO (allow):
                null=[
                  Access: [top-s] 2024-08-31 09:54:40.691 (-743d12h51m31s225ms) duration=+3m53s278ms
                  Access: [bg-s] 2024-08-31 09:58:33.969 (-743d12h47m37s947ms) duration=+5ms
                ]
            Package com.openai.chatgpt:
              RECORD_AUDIO (allow):
                null=[
                  Access: [top-s] 2026-09-11 15:31:52.475 (-2d7h14m19s441ms) duration=+2s282ms
                  Reject: [top-s]2026-06-11 14:03:37.200 (-94d8h42m34s716ms)
                  Access: [fgsvc-s] 2026-05-28 19:07:20.082 (-108d3h38m51s834ms) duration=+5s40ms
                  Access: [cch-s] 2026-09-13 21:36:06.258 (-1h8m30s83ms)
                ]
    """.trimIndent().lines()

    @Test
    fun readsTheAccessRecordsThisPhonePrinted() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)

        assertEquals(5, reading.uses.size)
        assertEquals(0, reading.unreadable)
    }

    /**
     * `Reject:` prints **without** a space after the bracket where `Access:`
     * has one. It is also not an access - the platform refused the app - so
     * counting it would report something the app was stopped from doing.
     */
    @Test
    fun aRejectIsNotCountedAsAUse() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)

        assertTrue(reading.uses.none { it.at.startsWith("2026-06-11") })
        // And it is not noise either: it never lands in the unreadable count.
        assertEquals(0, reading.unreadable)
    }

    /**
     * The distinction the whole screen rests on. `bg` and `cch` are the
     * platform's own words for "this app was not what you were looking at".
     */
    @Test
    fun onlyBackgroundAndCachedCountAsOutOfSight() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)

        assertEquals(
            listOf("2024-08-31 09:58:33.969", "2026-09-13 21:36:06.258"),
            reading.whileNotLooking.map { it.at }.sorted(),
        )
        // top, fgsvc and the second top were all on screen.
        assertEquals(3, reading.uses.size - reading.whileNotLooking.size)
    }

    @Test
    fun everyUseIsAttributedToThePackageHeaderAboveIt() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)

        assertEquals(
            setOf("com.android.chrome", "com.openai.chatgpt"),
            reading.uses.map { it.packageName }.toSet(),
        )
        assertEquals(
            listOf("com.openai.chatgpt"),
            reading.whileNotLooking
                .filter { it.at.startsWith("2026-09-13") }
                .map { it.packageName },
        )
    }

    /** An access with nothing to attribute it to is counted, never guessed. */
    @Test
    fun anAccessBeforeAnyPackageHeaderIsUnreadableRatherThanMisattributed() {
        val reading = AppOpLedger.parse(
            listOf("  Access: [bg-s] 2026-09-13 21:36:06.258 (-1h)"),
            AppOpLedger.Op.CAMERA,
        )

        assertTrue(reading.uses.isEmpty())
        assertEquals(1, reading.unreadable)
        assertTrue(reading.readNothing)
    }

    @Test
    fun aMalformedAccessLineIsCountedNotDropped() {
        val reading = AppOpLedger.parse(
            listOf(
                "Package com.example.app:",
                "Access: [bg-s] yesterday afternoon",
            ),
            AppOpLedger.Op.CAMERA,
        )

        assertEquals(0, reading.uses.size)
        assertEquals(1, reading.unreadable)
        assertTrue(AppOpLedger.unreadableNotice(listOf(reading))!!.contains("1 record"))
    }

    @Test
    fun anUnrecognisedStateIsNotTreatedAsOutOfSight() {
        val reading = AppOpLedger.parse(
            listOf(
                "Package com.example.app:",
                "Access: [wat-s] 2026-09-13 21:36:06.258 (-1h)",
            ),
            AppOpLedger.Op.CAMERA,
        )

        assertEquals(AppOpLedger.AppState.UNRECOGNISED, reading.uses.single().state)
        assertTrue(reading.whileNotLooking.isEmpty())
    }

    @Test
    fun summariesRollUpPerAppPerOpMostRecentFirst() {
        val mic = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)
        val camera = AppOpLedger.parse(
            listOf(
                "Package com.example.watcher:",
                "Access: [bg-s] 2026-09-12 08:00:00.000 (-1d)",
                "Access: [bg-s] 2026-09-10 08:00:00.000 (-3d)",
            ),
            AppOpLedger.Op.CAMERA,
        )

        val summaries = AppOpLedger.summarise(listOf(mic, camera))

        assertEquals(3, summaries.size)
        // Most recent first: chatgpt's cached mic access on the 13th.
        assertEquals("com.openai.chatgpt", summaries.first().packageName)
        assertEquals("2026-09-13 21:36:06.258", summaries.first().mostRecent)

        val watcher = summaries.single { it.packageName == "com.example.watcher" }
        assertEquals(2, watcher.times)
        assertEquals("2026-09-12 08:00:00.000", watcher.mostRecent)
        assertEquals(
            "com.example.watcher used the camera 2 times, most recently 2026-09-12 08:00",
            AppOpLedger.line(watcher),
        )
    }

    /**
     * Milliseconds order the records and mean nothing to a reader, so the
     * model keeps them and the sentence does not.
     */
    @Test
    fun theSentenceDropsMillisecondsButTheRecordKeepsThem() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)
        val summary = AppOpLedger.summarise(listOf(reading))
            .single { it.packageName == "com.openai.chatgpt" }

        assertEquals("2026-09-13 21:36:06.258", summary.mostRecent)
        assertTrue(AppOpLedger.line(summary).endsWith("most recently 2026-09-13 21:36"))
    }

    /** An unexpected shape is passed through, never mangled into a wrong time. */
    @Test
    fun anUnfamiliarTimestampIsLeftAlone() {
        assertEquals("yesterday", AppOpLedger.forReading("yesterday"))
        assertEquals("2026-09-13T21:36:06Z", AppOpLedger.forReading("2026-09-13T21:36:06Z"))
    }

    @Test
    fun oneUseReadsAsOnceRatherThanOneTimes() {
        val reading = AppOpLedger.parse(
            listOf(
                "Package com.example.app:",
                "Access: [bg-s] 2026-09-13 21:36:06.258 (-1h)",
            ),
            AppOpLedger.Op.PRECISE_LOCATION,
        )
        val summary = AppOpLedger.summarise(listOf(reading)).single()

        assertTrue(AppOpLedger.line(summary).contains("used your precise location once,"))
    }

    /**
     * An empty result is good news and has to read as good news - not as a
     * card that failed to load.
     */
    @Test
    fun nothingOutOfSightIsSaidPlainly() {
        assertEquals(
            "No app used the microphone, camera or your precise location " +
                "while it was out of sight.",
            AppOpLedger.headline(emptyList()),
        )
        assertNull(AppOpLedger.detail(emptyList()))
    }

    @Test
    fun theHeadlineCountsAppsNotRecords() {
        val reading = AppOpLedger.parse(
            listOf(
                "Package com.example.app:",
                "Access: [bg-s] 2026-09-13 21:36:06.258 (-1h)",
                "Access: [cch-s] 2026-09-12 21:36:06.258 (-1d)",
            ),
            AppOpLedger.Op.CAMERA,
        )

        // Two records, one app.
        assertEquals(
            "1 app used the microphone, camera or your precise location " +
                "while they were not in front of you.",
            AppOpLedger.headline(AppOpLedger.summarise(listOf(reading))),
        )
    }

    /** The card must never read as an accusation. */
    @Test
    fun theDetailRefusesToAccuse() {
        val reading = AppOpLedger.parse(realDump, AppOpLedger.Op.MICROPHONE)
        val detail = AppOpLedger.detail(AppOpLedger.summarise(listOf(reading)))!!

        assertTrue(detail.contains("not by itself wrong"))
        assertTrue(detail.contains("leaves the judgement to you"))
    }
}
