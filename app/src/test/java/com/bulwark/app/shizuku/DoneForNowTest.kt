package com.bulwark.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offer to close the door Bulwark opened.
 *
 * Two properties matter more than the wording: every item states what it
 * costs, and the item Bulwark cannot perform never reads as though it did.
 */
class DoneForNowTest {

    @Test
    fun `nothing running, nothing offered`() {
        // No cheerful all-clear. Bulwark praising Bulwark is a voice this
        // project already corrected once.
        val closeable = whatCanBeClosed(shizukuRunning = false, wirelessDebuggingOn = false)
        assertTrue(closeable.isEmpty())
        assertNull(doneForNowHeadline(closeable))
    }

    @Test
    fun `both open, both offered`() {
        val closeable = whatCanBeClosed(shizukuRunning = true, wirelessDebuggingOn = true)
        assertEquals(2, closeable.size)
        assertEquals(
            listOf(CloseAction.STOP_SHIZUKU, CloseAction.TURN_OFF_WIRELESS_DEBUGGING),
            closeable.map { it.action },
        )
    }

    @Test
    fun `debugging alone is still worth offering`() {
        // Measured 2026-09-12: switching wireless debugging off does not kill a
        // running server, so the two are independent and either can be the only
        // thing left open.
        val closeable = whatCanBeClosed(shizukuRunning = false, wirelessDebuggingOn = true)
        assertEquals(listOf(CloseAction.TURN_OFF_WIRELESS_DEBUGGING), closeable.map { it.action })
        assertTrue(doneForNowHeadline(closeable)!!.contains("Shizuku is already stopped"))
    }

    @Test
    fun `every offer states what it costs`() {
        // The lockdown lesson, enforced. Advice that hides its cost does not
        // survive being followed.
        for (shizuku in listOf(true, false)) {
            for (debugging in listOf(true, false)) {
                whatCanBeClosed(shizuku, debugging).forEach {
                    assertTrue("${it.action} must state a cost", it.cost.isNotBlank())
                    assertTrue("${it.action} must say what happens", it.what.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `the action Bulwark cannot perform says so, and its button does not claim it`() {
        // The one that would be dishonest to get wrong: a button reading "Turn
        // it off" that merely opens a screen is a claim the app did something
        // it did not.
        val signpost = whatCanBeClosed(shizukuRunning = false, wirelessDebuggingOn = true).single()

        assertTrue(
            "must say Bulwark cannot do it: ${signpost.what}",
            signpost.what.contains("cannot switch this one off for you"),
        )
        assertTrue(
            "must name the switch to look for",
            signpost.what.contains("Wireless debugging"),
        )
        assertFalse(
            "the label must not claim the action: ${signpost.label}",
            signpost.label.startsWith("Turn off") || signpost.label.startsWith("Disable"),
        )
    }

    @Test
    fun `stopping Shizuku is described as reversible, and not as deleting anything`() {
        val stop = whatCanBeClosed(shizukuRunning = true, wirelessDebuggingOn = false).single()
        assertTrue(stop.what.contains("pairing itself is untouched"))
        assertTrue(stop.what.contains("not deleting"))
        assertTrue("the undo must be named", stop.cost.contains("start Shizuku again"))
    }

    @Test
    fun `stopping Shizuku says it affects every app, not just Bulwark`() {
        // The design note missed this and the first draft of the copy repeated
        // the miss: Shizuku.exit() stops the server for the whole phone. Anyone
        // running another Shizuku app loses it too, and a button that reads as
        // if it only touched Bulwark would be understating what it does.
        val stop = whatCanBeClosed(shizukuRunning = true, wirelessDebuggingOn = false).single()
        assertTrue(
            "must say it is phone-wide: ${stop.what}",
            stop.what.contains("every app on this phone"),
        )
        assertTrue(stop.what.contains("not only Bulwark"))
    }

    @Test
    fun `the bootstrap is stated, because Bulwark cannot undo that half either`() {
        // Starting Shizuku needs the access Shizuku provides. Someone who turns
        // wireless debugging off has to turn it back on by hand, and a flow
        // that hides that does not survive being followed.
        val signpost = whatCanBeClosed(false, wirelessDebuggingOn = true).single()
        assertTrue(signpost.cost.contains("by hand"))
        assertTrue(signpost.cost.contains("Bulwark cannot do that part"))
    }

    @Test
    fun `no self-congratulation anywhere`() {
        // "We are closing the door for your safety" is Bulwark praising
        // Bulwark. State what happens and what it costs.
        val praise = listOf("for your safety", "we keep you", "protected", "secure", "safely")
        for (shizuku in listOf(true, false)) {
            for (debugging in listOf(true, false)) {
                val closeable = whatCanBeClosed(shizuku, debugging)
                val text = closeable.joinToString(" ") { it.label + " " + it.what + " " + it.cost } +
                    " " + doneForNowHeadline(closeable).orEmpty()
                praise.forEach {
                    assertFalse("must not congratulate itself with \"$it\": $text",
                        text.contains(it, ignoreCase = true))
                }
            }
        }
    }

    @Test
    fun `the headline never calls the current state unsafe`() {
        // Leaving both on is reasonable for someone who uses Bulwark daily.
        // This is an offer, not a scolding.
        val text = doneForNowHeadline(whatCanBeClosed(true, true))!!
        listOf("danger", "at risk", "unsafe", "vulnerable", "exposed").forEach {
            assertFalse("must not scold: $text", text.contains(it, ignoreCase = true))
        }
    }
}
