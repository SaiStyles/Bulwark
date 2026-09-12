package com.bulwark.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log and the device, reconciled.
 *
 * Written because on 2026-09-12 the action log was destroyed on the test phone
 * - `connectedAndroidTest` uninstalls the app when it finishes - and the
 * packages it recorded stayed exactly as they were. A Changes screen derived
 * from the log alone reports such a phone as untouched.
 *
 * Pure, so every case here is checked without a device.
 */
class ReconcileTest {

    private fun off(name: String, at: Long = 1L) =
        Change(packageName = name, kind = ChangeKind.SWITCHED_OFF, atEpochMillis = at)

    @Test
    fun anUnreadableDeviceIsNotReportedAsACleanOne() {
        val logged = listOf(off("com.example.one"))

        val result = logged.reconciledWith(null)

        // The log's word, flagged as such. The screen says so out loud; what
        // it must never do is present this as the state of the phone.
        assertTrue("an unread device must be marked unknown", result.deviceUnknown)
        assertEquals(logged, result.changes)
        assertEquals(0, result.alreadyBack)
    }

    @Test
    fun aLoggedSwitchOffTheDeviceConfirmsIsKeptAndAttributed() {
        val result = listOf(off("com.example.one"))
            .reconciledWith(DeviceDisabled(setOf("com.example.one")))

        assertEquals(1, result.changes.size)
        assertEquals(Attribution.BULWARK, result.changes.single().attribution)
        assertFalse(result.deviceUnknown)
    }

    @Test
    fun aSwitchOffUndoneElsewhereIsDroppedRatherThanOfferedAgain() {
        // The row would read "Switch back on" for an app that is already on,
        // and pressing it performs a disable under a non-destructive label -
        // the defect hardware testing found on 2026-09-10.
        val result = listOf(off("com.example.one"), off("com.example.two"))
            .reconciledWith(DeviceDisabled(setOf("com.example.two")))

        assertEquals(listOf("com.example.two"), result.changes.map { it.packageName })
        assertEquals("the dropped one must be counted, not silently vanished", 1, result.alreadyBack)
    }

    @Test
    fun aDisabledPackageWithNoLogEntryStillAppears() {
        // The whole point. Bulwark may well have switched this off before its
        // log was lost, so the entry exists and the attribution does not claim
        // to know who did it.
        val result = emptyList<Change>()
            .reconciledWith(DeviceDisabled(setOf("com.example.orphan")))

        val change = result.changes.single()
        assertEquals("com.example.orphan", change.packageName)
        assertEquals(ChangeKind.SWITCHED_OFF, change.kind)
        assertEquals(Attribution.UNRECORDED, change.attribution)
    }

    @Test
    fun aPartialReadNeverRetiresAnUndo() {
        // complete=false means at least one package's state could not be read,
        // so absence from the set is not evidence of anything. Rule 6.
        val result = listOf(off("com.example.one"))
            .reconciledWith(DeviceDisabled(emptySet(), complete = false))

        assertEquals("a partial read must not drop a logged change", 1, result.changes.size)
        assertEquals(0, result.alreadyBack)
    }

    @Test
    fun otherKindsPassThroughUntouched() {
        // The device read answers for switch-offs only. Permissions and
        // firewall rules are different surfaces and must not be filtered by a
        // set that says nothing about them.
        val permission = Change(
            packageName = "com.example.one",
            kind = ChangeKind.PERMISSION_TAKEN,
            permission = "android.permission.CAMERA",
            atEpochMillis = 5L,
        )
        val blocked = Change(
            packageName = "com.example.two",
            kind = ChangeKind.INTERNET_BLOCKED,
            atEpochMillis = 4L,
        )

        val result = listOf(permission, blocked).reconciledWith(DeviceDisabled(emptySet()))

        assertEquals(listOf(permission, blocked), result.changes)
    }

    @Test
    fun theHeadlineSaysHowManyAreNotBulwarksOwn() {
        val changes = listOf(off("com.example.one"))
            .reconciledWith(DeviceDisabled(setOf("com.example.one", "com.example.orphan")))
            .changes

        val headline = changesHeadline(changes)

        assertTrue(
            "the headline must name the unrecorded count, not fold it in: $headline",
            headline.contains("1 is not in Bulwark's log"),
        )
    }

    @Test
    fun anUnrecordedRowSaysBulwarkCannotAccountForIt() {
        val change = emptyList<Change>()
            .reconciledWith(DeviceDisabled(setOf("com.example.orphan")))
            .changes.single()

        val text = change.describe()

        assertTrue("it must say Bulwark has no record: $text", text.contains("no record"))
        // Never an accusation. Both innocent explanations are named, and
        // neither is presented as the answer.
        assertTrue("it must offer the lost-log explanation: $text", text.contains("cleared"))
        assertFalse("it must not accuse: $text", text.contains("malware"))
    }

    @Test
    fun unrecordedEntriesSortAfterTheOnesWithAHistory() {
        // A logged change has a timestamp and is what the user most likely
        // came here for; an unrecorded one has none and cannot be ordered by
        // time at all, so it goes below rather than jumbling into the list.
        val result = listOf(off("com.example.logged", at = 99L))
            .reconciledWith(DeviceDisabled(setOf("com.example.logged", "com.example.orphan")))

        assertEquals(
            listOf("com.example.logged", "com.example.orphan"),
            result.changes.map { it.packageName },
        )
    }
}
