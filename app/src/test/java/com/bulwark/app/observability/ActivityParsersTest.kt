package com.bulwark.app.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three parsers behind the Activity screen, against output copied from the
 * Agni 2 on 2026-09-13.
 *
 * One file rather than three: they are the same shape of problem - freeform
 * text with no contract - and the cases worth writing down are the same three
 * each time. What must never happen is a quietly shorter list.
 */
class ActivityParsersTest {

    // -- alarm ---------------------------------------------------------------

    private val alarmDump = """
          Alarm Stats:
          1000:com.pri.screenoff.killer +1s173ms running, 45 wakeups:
            +868ms 35 wakes 35 alarms, last -1h23m54s308ms:
              *walarm*:pri.intent.action.screen_off_three_min
          1000:android +13s250ms running, 211 wakeups:
            +8s525ms 0 wakes 805 alarms, last -45s789ms:
              *alarm*:TIME_TICK
          10115:com.google.android.gms +2s running, 1 wakeups:
    """.trimIndent().lines()

    @Test
    fun alarmReadsWhoWokeThePhoneAndHowOften() {
        val reading = AlarmWakeups.parse(alarmDump)

        assertEquals(3, reading.wakers.size)
        assertEquals(0, reading.unreadable)
        assertEquals(257, reading.total)
        assertEquals("1000", reading.wakers.first().uid)
        // Most wakeups first - the top of the list is the answer.
        assertEquals("android", reading.wakers.first().packageName)
        assertEquals(211, reading.wakers.first().wakeups)
    }

    /**
     * Found on the Agni 2 by reading the card, which said it could not read 13
     * lines - and those 13 were the interesting ones.
     *
     * App uids print in Android's shorthand, `u0a115`, not as a number. Only
     * system uids like `1000` are numeric, so a regex anchored on digits reads
     * the four system rows and silently loses Play Services at 453 wakeups,
     * SystemUI at 263, Instagram and WhatsApp.
     */
    @Test
    fun alarmReadsTheAppUidShorthandAndNotOnlyNumericUids() {
        val reading = AlarmWakeups.parse(
            listOf(
                "  u0a115:com.google.android.gms +13s156ms running, 453 wakeups:",
                "  u0a666:com.instagram.android +32s478ms running, 30 wakeups:",
                "  1000:android +13s250ms running, 211 wakeups:",
            ),
        )

        assertEquals(0, reading.unreadable)
        assertEquals(
            listOf("com.google.android.gms", "android", "com.instagram.android"),
            reading.wakers.map { it.packageName },
        )
        assertEquals("u0a115", reading.wakers.first().uid)
    }

    @Test
    fun alarmIgnoresTheAlarmTagsUnderneath() {
        val reading = AlarmWakeups.parse(alarmDump)

        // `*walarm*:…` lines are internal strings, never rows.
        assertTrue(reading.wakers.none { it.packageName.startsWith("*") })
    }

    @Test
    fun alarmCountsALineItCannotReadRatherThanDroppingIt() {
        val reading = AlarmWakeups.parse(alarmDump + listOf("something odd, lots of wakeups:"))

        assertEquals(3, reading.wakers.size)
        assertEquals(1, reading.unreadable)
        assertTrue(AlarmWakeups.unreadableNotice(reading)!!.contains("1 line"))
    }

    @Test
    fun alarmSaysNothingWokeThePhoneWhenNothingDid() {
        assertEquals(
            "Nothing has woken this phone since it last restarted.",
            AlarmWakeups.headline(AlarmWakeups.parse(emptyList())),
        )
    }

    @Test
    fun alarmHeadlineCountsAppsAndWakeups() {
        assertEquals(
            "3 apps have woken this phone 257 times since it last restarted.",
            AlarmWakeups.headline(AlarmWakeups.parse(alarmDump)),
        )
        assertEquals(
            "com.google.android.gms woke the phone once",
            AlarmWakeups.line(AlarmWakeups.parse(alarmDump).wakers.last()),
        )
    }

    // -- sensorservice -------------------------------------------------------

    private val sensorDump = """
        Sensor List:
        0x00000001) ACCELEROMETER             | MTK             | ver: 1 | type: android.sensor.accelerometer(1)
        0x0000000a) STEP_DETECTOR             | MTK             | ver: 1 | type: android.sensor.step_detector(18)
        0x0000000b) SIGNIFICANT_MOTION        | MTK             | ver: 1 | type: android.sensor.significant_motion(17)
        Previous Registrations:
        23:00:39 - 0x0000000a pid=13735 uid=10115 package=com.example.watcher
        23:00:37 + 0x0000000b pid=13735 uid=10115 package=com.example.watcher samplingPeriod=20000us batchingPeriod=0us
        23:00:37 + 0x0000000a pid=13735 uid=10115 package=com.example.watcher samplingPeriod=20000us batchingPeriod=0us
        22:21:31 + 0x00000001 pid= 8551 uid=10200 package=com.example.fitness samplingPeriod=20000us
    """.trimIndent().lines()

    @Test
    fun sensorsResolveHandlesToTheirNames() {
        val reading = SensorRegistrations.parse(sensorDump)

        assertEquals(
            setOf("SIGNIFICANT_MOTION", "STEP_DETECTOR", "ACCELEROMETER"),
            reading.registrations.map { it.sensor }.toSet(),
        )
    }

    /** `-` is the app letting go, which is not a use. */
    @Test
    fun sensorsIgnoreReleases() {
        val reading = SensorRegistrations.parse(sensorDump)

        assertEquals(3, reading.registrations.size)
        assertEquals(0, reading.unreadable)
    }

    @Test
    fun sensorsRollUpPerAppPerSensorMostRecentFirst() {
        val summaries = SensorRegistrations.summarise(SensorRegistrations.parse(sensorDump))

        assertEquals(3, summaries.size)
        assertEquals("com.example.watcher", summaries.first().packageName)
        assertEquals(
            "com.example.fitness read the accelerometer once, most recently at 22:21:31",
            SensorRegistrations.line(summaries.last()),
        )
    }

    /** A handle with no name is reported as the handle, never dropped. */
    @Test
    fun anUnnamedSensorIsStillReported() {
        val reading = SensorRegistrations.parse(
            listOf(
                "Previous Registrations:",
                "10:00:00 + 0x000000ff pid=1 uid=10001 package=com.example.app",
            ),
        )

        assertEquals("0x000000ff", reading.registrations.single().sensor)
    }

    @Test
    fun sensorsExplainWhatARegistrationIsAndAdmitTheMissingDate() {
        val summaries = SensorRegistrations.summarise(SensorRegistrations.parse(sensorDump))
        val detail = SensorRegistrations.detail(summaries)!!

        assertTrue(detail.contains("not a permission"))
        assertTrue(detail.contains("clock times without a date"))
    }

    // -- location ------------------------------------------------------------

    private val locationDump = """
          Location Providers:
            passive provider:
              service: registered
              listeners:
                10115/com.google.android.gms[fused_location_provider]/1D38F30C Request[@+24855d3h BALANCED, minUpdateInterval=0, hiddenFromAppOps, WorkSource{10115 com.google.android.gms}]
                10115/com.google.android.gms[fused_location_provider]/16A0EE4D Request[@0 BALANCED, hiddenFromAppOps, WorkSource{10115 com.google.android.gms}]
                1000/android[SensorNotificationService]/E2A532A2 Request[PASSIVE, minUpdateInterval=+30m0s0ms, WorkSource{1000 android}]
              last location=Location[fused 10.1,76.4]
              enabled=true
            network provider:
              listeners:
                10102/com.google.android.as/D718A796 (COARSE) Request[@+6h0m0s0ms LOW_POWER, minUpdateDistance=100000.0, WorkSource{10102 com.google.android.as}]
              enabled=true
            gps provider:
              listeners:
                10300/com.example.maps/AABBCCDD Request[@0 HIGH_ACCURACY, WorkSource{10300 com.example.maps}]
              enabled=true
    """.trimIndent().lines()

    @Test
    fun locationReadsStandingRequestsAndTheirPrecision() {
        val reading = LocationRequests.parse(locationDump)

        assertEquals(0, reading.unreadable)
        assertEquals(
            listOf("com.example.maps", "com.google.android.gms", "com.google.android.as", "android"),
            reading.requests.map { it.packageName },
        )
        assertEquals(LocationRequests.Precision.HIGH, reading.requests.first().precision)
    }

    /**
     * The same bug the Doze card shipped with: gms registers twice under one
     * provider, and counting both would overstate what is happening.
     */
    @Test
    fun anAppRegisteredTwiceIsOneAppKeepingItsMostPreciseRequest() {
        val reading = LocationRequests.parse(locationDump)

        assertEquals(1, reading.requests.count { it.packageName == "com.google.android.gms" })
        assertEquals(
            LocationRequests.Precision.BALANCED,
            reading.requests.single { it.packageName == "com.google.android.gms" }.precision,
        )
    }

    /** A passive listener asks for nothing, so it is not counted as asking. */
    @Test
    fun passiveListenersAreCountedSeparately() {
        val reading = LocationRequests.parse(locationDump)

        assertEquals(3, reading.active.size)
        assertEquals(
            "3 apps are asking this phone where it is.",
            LocationRequests.headline(reading),
        )
        assertTrue(
            LocationRequests.detail(reading)!!
                .contains("A further 1 listens in on other apps' requests"),
        )
    }

    @Test
    fun locationStopsReadingAtTheEndOfAListenerBlock() {
        val reading = LocationRequests.parse(locationDump)

        // `last location=…` and `enabled=true` are not listeners and must not
        // be counted as unreadable ones either.
        assertEquals(0, reading.unreadable)
    }

    @Test
    fun aListenerItCannotReadIsCountedNotDropped() {
        val reading = LocationRequests.parse(
            listOf(
                "listeners:",
                "99999/ Request[BALANCED]",
            ),
        )

        assertEquals(0, reading.requests.size)
        assertEquals(1, reading.unreadable)
        assertTrue(LocationRequests.unreadableNotice(reading)!!.contains("1 request"))
    }

    @Test
    fun anUnknownPrecisionIsNotQuietlyDowngraded() {
        val reading = LocationRequests.parse(
            listOf(
                "listeners:",
                "10400/com.example.odd/ABCD1234 Request[@0 ULTRA_MODE, WorkSource{10400}]",
            ),
        )

        assertEquals(
            LocationRequests.Precision.UNRECOGNISED,
            reading.requests.single().precision,
        )
        // And it still counts as asking, because it is.
        assertEquals(1, reading.active.size)
    }

    @Test
    fun nothingAskingReadsAsGoodNews() {
        val reading = LocationRequests.parse(emptyList())

        assertEquals(
            "No app has a standing request for your location.",
            LocationRequests.headline(reading),
        )
        assertNull(LocationRequests.detail(reading))
    }
}
