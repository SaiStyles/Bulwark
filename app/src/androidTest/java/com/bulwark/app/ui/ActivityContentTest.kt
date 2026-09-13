package com.bulwark.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bulwark.app.observability.AlarmWakeups
import com.bulwark.app.observability.AppOpLedger
import com.bulwark.app.observability.DozeExemptions
import com.bulwark.app.observability.LocationRequests
import com.bulwark.app.observability.SensorRegistrations
import com.bulwark.app.ui.theme.BulwarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Activity screen's detector.
 *
 * Five reads land at five different moments, so this screen has more chances
 * than any other to show a card with nothing in it. Every assertion below
 * names a card's **content**, never its title - a title above an empty body is
 * the failure being guarded against, and asserting the title would pass
 * straight through it.
 */
@RunWith(AndroidJUnit4::class)
class ActivityContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyCardShowsItsContentWhenEveryReadHasLanded() {
        render(loaded())

        compose.onNodeWithText("What your apps did").assertIsDisplayed()

        scrollTo("used the microphone once")
        compose.onNodeWithText("used the microphone once", substring = true).assertIsDisplayed()

        scrollTo("asking for the most precise location")
        compose.onNodeWithText("asking for the most precise location", substring = true)
            .assertIsDisplayed()

        scrollTo("read the accelerometer")
        compose.onNodeWithText("read the accelerometer", substring = true).assertIsDisplayed()

        scrollTo("woke the phone 45 times")
        compose.onNodeWithText("com.example.waker woke the phone 45 times").assertIsDisplayed()

        scrollTo("com.example.sleepless")
        compose.onNodeWithText("com.example.sleepless").assertIsDisplayed()
    }

    /**
     * Without Shizuku every card says why, rather than showing an empty list.
     * Five silent cards would read as five all-clears.
     */
    @Test
    fun withoutShizukuEveryCardSaysWhyItIsEmpty() {
        render(
            loaded().copy(
                shizukuReady = false,
                ledger = null, location = null, sensors = null, wakeups = null, doze = null,
            ),
        )

        compose.onNodeWithText("What your apps did").assertIsDisplayed()

        scrollTo("never shown to you")
        compose.onNodeWithText("never shown to you", substring = true).assertIsDisplayed()

        scrollTo("standing request for your location")
        compose.onNodeWithText("standing request for your location", substring = true)
            .assertIsDisplayed()

        scrollTo("asked the sensors for data")
        compose.onNodeWithText("asked the sensors for data", substring = true).assertIsDisplayed()

        scrollTo("waking the phone")
        compose.onNodeWithText("waking the phone", substring = true).assertIsDisplayed()
    }

    /** A read that failed is not a phone with nothing to report. */
    @Test
    fun aFailedReadSaysSoRatherThanReadingAsAnAllClear() {
        render(
            loaded().copy(
                wakeups = null,
                wakeupsCouldNotTell = "Bulwark reached this phone's alarm record but could not read it.",
            ),
        )

        scrollTo("could not read it")
        compose.onNodeWithText("could not read it", substring = true).assertIsDisplayed()
    }

    /** Before a read lands the slot is held, so nothing shifts under the reader. */
    @Test
    fun aCardStillLoadingSaysSoInsteadOfBeingAbsent() {
        render(loaded().copy(sensors = null, sensorsCouldNotTell = null))

        scrollTo("What read this phone's sensors")
        compose.onNodeWithText("What read this phone's sensors").assertIsDisplayed()
        compose.onNodeWithText("Checking…").assertIsDisplayed()
    }

    /** Empty is good news and has to read as good news. */
    @Test
    fun anEmptyResultReadsAsGoodNewsNotAsAFailure() {
        render(loaded().copy(wakeups = AlarmWakeups.parse(emptyList())))

        scrollTo("Nothing has woken this phone")
        compose.onNodeWithText(
            "Nothing has woken this phone since it last restarted.",
        ).assertIsDisplayed()
    }

    /** None of these cards may read as an accusation. */
    @Test
    fun theScreenReportsAndDoesNotAccuse() {
        render(loaded())

        scrollTo("leaves the judgement to you")
        compose.onNodeWithText("leaves the judgement to you", substring = true)
            .assertIsDisplayed()

        scrollTo("Sensors are not a permission")
        compose.onNodeWithText("Sensors are not a permission", substring = true)
            .assertIsDisplayed()
    }

    /**
     * A long list is cut for readability and the remainder is **stated**. A
     * list silently trimmed is the false all-clear this screen exists against.
     */
    @Test
    fun aLongListSaysHowManyRowsItDidNotShow() {
        val many = (1..20).map { "u0a$it:com.example.app$it +1s running, $it wakeups:" }
        render(loaded().copy(wakeups = AlarmWakeups.parse(many)))

        scrollTo("and 8 more")
        compose.onNodeWithText("and 8 more").assertIsDisplayed()
    }

    // -- helpers ------------------------------------------------------------

    private fun render(readings: ActivityReadings) {
        compose.setContent { BulwarkTheme { ActivityContent(readings = readings) } }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    private fun loaded() = ActivityReadings(
        ledger = AppOpLedger.summarise(
            listOf(
                AppOpLedger.parse(
                    listOf(
                        "Package com.example.tracker:",
                        "Access: [bg-s] 2026-09-13 21:36:06.258 (-1h)",
                    ),
                    AppOpLedger.Op.MICROPHONE,
                ),
            ),
        ),
        ledgerCouldNotTell = null,
        doze = DozeExemptions.parse(listOf("user,com.example.sleepless,10231")),
        dozeCouldNotTell = null,
        wakeups = AlarmWakeups.parse(
            listOf("1000:com.example.waker +1s173ms running, 45 wakeups:"),
        ),
        wakeupsCouldNotTell = null,
        sensors = SensorRegistrations.summarise(
            SensorRegistrations.parse(
                listOf(
                    "0x00000001) ACCELEROMETER | MTK | ver: 1",
                    "Previous Registrations:",
                    "22:21:31 + 0x00000001 pid= 8551 uid=10200 package=com.example.fitness",
                ),
            ),
        ),
        sensorsCouldNotTell = null,
        location = LocationRequests.parse(
            listOf(
                "listeners:",
                "10300/com.example.maps/AABBCCDD Request[@0 HIGH_ACCURACY, WorkSource{10300}]",
            ),
        ),
        locationCouldNotTell = null,
        shizukuReady = true,
    )
}
