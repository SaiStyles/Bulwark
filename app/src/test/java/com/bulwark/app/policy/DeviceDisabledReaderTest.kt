package com.bulwark.app.policy

import com.bulwark.app.shizuku.PackageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which states count as "the user switched this off", and which must not.
 *
 * The distinction is the whole guard: `DISABLED_USER` (3) is what a person
 * chose and the only state Bulwark writes. `DISABLED` (2) is what the system
 * or an app set for itself, and offering to put that back would be Bulwark
 * making a change while calling it an undo.
 *
 * Measured on the Agni 2 on 2026-09-12, which is where these values come from:
 * of five packages the platform listed as disabled, none was at 3 - two sat at
 * 2 and the rest at 0 or 1. Reading the boolean alone would have offered all
 * five.
 */
class DeviceDisabledReaderTest {

    private fun reader(
        installed: List<Pair<String, Boolean>>,
        states: Map<String, Int> = emptyMap(),
        listingThrows: Boolean = false,
    ) = DeviceDisabledReader(
        listing = { if (listingThrows) error("no privilege") else installed },
        states = { name, _ ->
            states[name] ?: error("unreadable: $name")
        },
    )

    @Test
    fun onlyUserDisabledPackagesCount() {
        val result = reader(
            installed = listOf(
                "com.user.off" to false,
                "com.system.off" to false,
                "com.default.off" to false,
                "com.still.on" to true,
            ),
            states = mapOf(
                "com.user.off" to PackageState.DISABLED_USER,
                "com.system.off" to PackageState.DISABLED,
                "com.default.off" to PackageState.DEFAULT,
            ),
        ).read()!!

        assertEquals(setOf("com.user.off"), result.packages)
        assertTrue(result.complete)
    }

    @Test
    fun theExactStateIsReadOnlyForPackagesAlreadyKnownToBeOff() {
        // The enumeration's boolean is free; the precise read is a binder call
        // each. Paying it for all 370 would be the cost this prefilter exists
        // to avoid, so an enabled package must never be asked about.
        val asked = mutableListOf<String>()
        DeviceDisabledReader(
            listing = { listOf("com.on.one" to true, "com.on.two" to true, "com.off" to false) },
            states = { name, _ -> asked += name; PackageState.DISABLED_USER },
        ).read()

        assertEquals(listOf("com.off"), asked)
    }

    @Test
    fun aFailedEnumerationIsNullRatherThanEmpty() {
        // "We could not tell" and "nothing is switched off" are different
        // facts, and the screen says something different for each. Collapsing
        // them would render a privilege failure as reassurance.
        assertNull(reader(installed = emptyList(), listingThrows = true).read())
    }

    @Test
    fun oneUnreadablePackageMarksTheReadIncompleteWithoutSinkingIt() {
        val result = reader(
            installed = listOf("com.user.off" to false, "com.unreadable" to false),
            states = mapOf("com.user.off" to PackageState.DISABLED_USER),
        ).read()!!

        assertEquals(setOf("com.user.off"), result.packages)
        assertFalse("an unreadable package must mark the read partial", result.complete)
    }

    @Test
    fun anUnreadablePackageIsNotAssumedDisabled() {
        val result = reader(
            installed = listOf("com.unreadable" to false),
            states = emptyMap(),
        ).read()!!

        assertTrue("nothing may be claimed about it", result.packages.isEmpty())
        assertFalse(result.complete)
    }

    @Test
    fun aPhoneWithNothingSwitchedOffReadsCleanAndComplete() {
        val result = reader(installed = listOf("com.on" to true)).read()!!

        assertTrue(result.packages.isEmpty())
        assertTrue(result.complete)
    }
}
