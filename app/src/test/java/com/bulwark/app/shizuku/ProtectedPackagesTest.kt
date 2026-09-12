package com.bulwark.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one refusal left, and the warnings that replaced the rest.
 *
 * **Rewritten 2026-09-12.** The suite this replaces asserted a blocklist of
 * name fragments and exact names - that telephony was refused, that OEM-renamed
 * packages were caught by substring, that `com.mediatek` matched exactly and
 * not as a prefix. Every one of those tests passed, and the thing they were
 * guarding was retired for two reasons the tests could not see: it protected
 * `com.mediatek.ims` only because that vendor used the word "ims", and it would
 * have mislabelled `com.google.android.ims` as critical telephony when this
 * phone says the IMS provider is MediaTek's.
 *
 * What names critical packages now is `CriticalRoles`, which asks the device.
 * What stands in front of them is a ceremony, not a wall.
 *
 * Deleted rather than kept passing against a narrower list, because a test
 * suite that still describes a retired policy is the most convincing kind of
 * stale documentation - it runs green.
 */
class ProtectedPackagesTest {

    @Test
    fun `Bulwark refuses to remove Bulwark, and that is the whole list`() {
        assertTrue(ProtectedPackages.isProtected("com.bulwark.app", isSystem = false))

        // Everything the old list refused is now the owner's to decide on.
        listOf(
            "com.android.phone",
            "com.mediatek.ims",
            "com.android.systemui",
            "com.android.settings",
            "com.android.cellbroadcastreceiver",
            "moe.shizuku.privileged.api",
        ).forEach {
            assertFalse("$it must no longer be refused", ProtectedPackages.isProtected(it, true))
        }
    }

    @Test
    fun `the refusal is technical, and says so`() {
        val reason = ProtectedPackages.reasonFor("com.bulwark.app", isSystem = false)

        assertNotNull(reason)
        // Not a judgement about danger - safety-rules gave those up. The
        // action cannot be completed, because it kills the process running it.
        assertTrue(reason!!, reason.contains("cannot remove itself"))
        assertTrue(reason, reason.contains("record that undoes"))
    }

    @Test
    fun `nothing else gets a refusal reason`() {
        assertNull(ProtectedPackages.reasonFor("com.android.phone", isSystem = true))
        assertNull(ProtectedPackages.reasonFor("com.example.bloat", isSystem = true))
    }

    @Test
    fun `empty or blank input fails closed, whatever the flag says`() {
        // Cannot reason about it, so refuse. Kept from the old suite because
        // it guards the parser rather than the policy.
        listOf(true, false).forEach { system ->
            assertTrue(ProtectedPackages.isProtected("", system))
            assertTrue(ProtectedPackages.isProtected("   ", system))
        }
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(ProtectedPackages.isProtected("COM.BULWARK.APP", isSystem = false))
        assertTrue(ProtectedPackages.isProtected("  com.Bulwark.App  ", isSystem = false))
    }

    @Test
    fun `messaging is warned about, not refused`() {
        val caution = ProtectedPackages.cautionFor("com.android.messaging", isSystem = true)

        assertNotNull("it must still say something", caution)
        assertTrue(caution!!, caution.contains("two-factor"))
        assertFalse(ProtectedPackages.isProtected("com.android.messaging", isSystem = true))
    }

    @Test
    fun `security and radio components are warned about too`() {
        listOf("com.android.keychain" to "security", "com.android.nfc" to "radio")
            .forEach { (name, expected) ->
                val caution = ProtectedPackages.cautionFor(name, isSystem = true)
                assertNotNull("$name must be warned about", caution)
                assertTrue("$name: $caution", caution!!.lowercase().contains(expected))
            }
    }

    @Test
    fun `ordinary bloatware is neither refused nor warned about`() {
        listOf("com.pri.storemode", "com.facebook.katana", "com.instagram.android")
            .forEach {
                assertFalse(it, ProtectedPackages.isProtected(it, isSystem = true))
                assertNull(it, ProtectedPackages.cautionFor(it, isSystem = true))
            }
    }

    @Test
    fun `a warning is never returned for the one refusal`() {
        // Already refused; a caution underneath it would be a second voice
        // about a decision that has already been made.
        assertEquals(null, ProtectedPackages.cautionFor("com.bulwark.app", isSystem = false))
    }
}
