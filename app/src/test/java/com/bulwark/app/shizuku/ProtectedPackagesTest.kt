package com.bulwark.app.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The never-remove list from `context/_shared/safety-rules.md`, asserted.
 *
 * A guardrail nobody has watched refuse anything is not a guardrail. These
 * tests are the watching - and the false-positive tests below are the half
 * that was missing, which is how the fragment list came to lock Nova Launcher.
 */
class ProtectedPackagesTest {

    // Fragment matching applies to system packages only, so most of the
    // hard-floor cases below are asked as system packages.
    private fun protectedSystem(name: String) = ProtectedPackages.isProtected(name, true)

    private fun protectedUser(name: String) = ProtectedPackages.isProtected(name, false)

    @Test
    fun `protects the calling stack`() {
        listOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.ims",
            "com.mediatek.ims",
            "com.android.carrierconfig",
            "com.lava.dialer",
            "com.android.emergency",
            "com.qualcomm.qti.telephonyservice",
            "com.android.providers.telephony",
        ).forEach {
            assertTrue("must protect $it", protectedSystem(it))
        }
    }

    @Test
    fun `messaging is warned about, not refused`() {
        // Changed 2026-09-10. Messaging used to sit on the hard floor. It does
        // not belong there: losing your SMS app is bad and recoverable, unlike
        // a phone that cannot dial emergency services. Refusing both alike was
        // paternalism dressed as safety, in an app whose whole argument is that
        // people should control their own devices.
        listOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
        ).forEach {
            assertFalse("$it must not be hard-refused", protectedSystem(it))
            val caution = ProtectedPackages.cautionFor(it, true)
            assertNotNull("$it must still warn", caution)
            assertTrue(
                "warning must name the real cost: $caution",
                caution!!.contains("two-factor", ignoreCase = true),
            )
        }
    }

    @Test
    fun `emergency alerts stay on the hard floor`() {
        // Cell broadcast carries evacuation orders and earthquake warnings.
        // Not "route back" - life safety. Android already lets people switch
        // off alert categories in Settings, reversibly; deleting the receiver
        // is not that, and the downside is missing an evacuation order.
        listOf(
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
        ).forEach {
            assertTrue("$it must be refused outright", protectedSystem(it))
        }
    }

    @Test
    fun `the hard floor is route-back plus life safety, and nothing else`() {
        listOf(
            "com.android.phone",          // cannot call for help about no calls
            "com.android.systemui",       // no UI left to fix it with
            "com.android.settings",
            "com.google.android.networkstack",  // bootloop class
        ).forEach {
            assertTrue("$it must be refused outright", protectedSystem(it))
            assertNull(
                "refused things do not also warn",
                ProtectedPackages.cautionFor(it, true),
            )
        }
    }

    @Test
    fun `protects anything the phone needs to stay usable`() {
        listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher3",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.setupwizard",
            "com.android.keyguard",
        ).forEach {
            assertTrue("must protect $it", protectedSystem(it))
        }
    }

    @Test
    fun `protects Shizuku and Bulwark itself, which are not system packages`() {
        // Both are ordinary user-installed apps, so they are protected by
        // exact name rather than by the system-only fragment list. If either
        // ever moved into the fragment list it would silently stop being
        // protected on every device, which is why this asks with isSystem
        // false rather than true.
        assertTrue(protectedUser("moe.shizuku.privileged.api"))
        assertTrue(protectedUser("com.bulwark.app"))
    }

    @Test
    fun `protects OEM-renamed telephony, which exact-match lists miss`() {
        // The whole reason matching is substring-based. The test device is a
        // small-OEM MediaTek phone whose stack is not named like AOSP.
        listOf(
            "com.lava.carrier.setup",
            "com.mediatek.telephony.ext",
            "com.transsion.phonemanager",
            "vendor.qti.imsrcsservice",
        ).forEach {
            assertTrue("must protect $it", protectedSystem(it))
        }
    }

    @Test
    fun `protects the bootloop and framework-module cases`() {
        // Every one of these is marked Unsafe by the Universal Debloater
        // Alliance and was allowed by our list until 2026-09-10. Found by
        // cross-referencing their database against the real test device -
        // not by reading our own code.
        listOf(
            "com.google.android.overlay.modules.modulemetadata.forframework",
            "com.google.android.networkstack",
            "com.google.android.ext.shared",
            "com.google.android.overlay.modules.ext.services",
            "com.android.devicelockcontroller",
            "com.mediatek.frameworkresoverlay",
            "com.mediatek.FrameworkResOverlayExt",
            "com.android.wifi.resources.overlay",
            "com.google.android.connectivity.resources",
            "com.mediatek",
        ).forEach {
            assertTrue("must protect $it", protectedSystem(it))
        }
    }

    @Test
    fun `mediatek is matched exactly, not as a prefix`() {
        // "mediatek" as a fragment would protect every MediaTek package,
        // including the many that are safely removable. An over-broad rule
        // that defeats the feature is its own kind of failure.
        assertTrue(protectedSystem("com.mediatek"))
        assertFalse(protectedSystem("com.mediatek.duraspeed"))
        assertFalse(protectedSystem("com.mediatek.camera"))
    }

    // -----------------------------------------------------------------------
    // False positives. Added 2026-09-10, after the fragment list was found
    // refusing apps their owners had installed themselves.
    // -----------------------------------------------------------------------

    @Test
    fun `never locks an app the user installed themselves`() {
        // Every one of these came back LOCKED - "on Bulwark's permanent
        // never-remove list" - for an app someone chose to install. The
        // fragment list describes shapes of *system* component, and a
        // user-installed package is never one, so it does not apply to them.
        //
        // This is the paternalism the 2026-09-10 narrowing removed, arriving
        // back by accident through the matching rule instead of by intent.
        // Nothing watched it fail, because no test had ever fed the guard a
        // name anyone would actually install.
        listOf(
            "com.teslacoilsw.launcher",          // Nova Launcher    <- "launcher"
            "com.microsoft.launcher",            //                  <- "launcher"
            "com.simplemobiletools.gallery.pro", //                  <- "sim"
            "com.simplenote.android",            //                  <- "sim"
            "com.simplecityapps.shuttle",        //                  <- "sim"
            "com.radio.fmradio",                 //                  <- "radio"
            "com.claims.app",                    //                  <- "ims"
            "com.simplemobiletools.dialer",      // <- "dialer", and still theirs
            "org.telegram.messenger",
            "com.whatsapp",
            "com.spotify.music",
        ).forEach {
            assertFalse("must not lock a user-installed $it", protectedUser(it))
        }
    }

    @Test
    fun `known substring collisions do not lock system OEM apps either`() {
        // Preinstalled FM radio and "simple"-branded utilities are ordinary
        // bloat, and removing them is the entire point of this app. Recorded
        // as named exceptions rather than pretending substring matching is
        // exact - see ProtectedPackages.FRAGMENT_FALSE_FRIENDS.
        listOf(
            "com.mediatek.fmradio",
            "com.caf.fmradio",
            "com.android.fmradio",
            "com.oem.simplenote",
            "com.lava.simpleweather",
        ).forEach {
            assertFalse("must stay removable: $it", protectedSystem(it))
        }
    }

    @Test
    fun `the exact list applies to user apps, the fragment list does not`() {
        // Both halves of the rule, side by side, so a later change to one
        // cannot quietly assume the other.
        assertTrue("exact names are unconditional", protectedUser("com.android.shell"))
        assertFalse("fragments need a system package", protectedUser("com.example.dialer"))
        assertTrue("and they apply when there is one", protectedSystem("com.example.dialer"))
    }

    @Test
    fun `empty or blank input fails closed, whatever the flag says`() {
        // Cannot reason about it, so refuse. safety-rules.md rule 6.
        assertTrue(ProtectedPackages.isProtected("", false))
        assertTrue(ProtectedPackages.isProtected("   ", false))
        assertTrue(ProtectedPackages.isProtected("", true))
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(protectedSystem("COM.ANDROID.PHONE"))
        assertTrue(protectedSystem("Com.Android.SystemUI"))
    }

    @Test
    fun `still allows ordinary removable bloatware`() {
        // Over-broad matching is the intended bias, but it must not be so
        // broad that the app cannot do its job at all. These arrive
        // preinstalled, so they are asked as system packages.
        listOf(
            "com.facebook.katana",
            "com.example.game",
            "com.netflix.mediaclient",
            "com.oem.weatherwidget",
            "com.thirdparty.shoppingapp",
        ).forEach {
            assertFalse("should be removable: $it", protectedSystem(it))
        }
    }

    @Test
    fun `gives a reason for refusing, and none when allowing`() {
        assertNotNull(ProtectedPackages.reasonFor("com.android.phone", true))
        assertNotNull(ProtectedPackages.reasonFor("com.android.systemui", true))
        assertNull(ProtectedPackages.reasonFor("com.example.game", true))
        assertNull(ProtectedPackages.reasonFor("com.teslacoilsw.launcher", false))
    }

    @Test
    fun `reasons name the actual consequence, not a generic refusal`() {
        // A user told "no" without a reason will find a tool that says yes.
        val callingReason = ProtectedPackages.reasonFor("com.android.phone", true).orEmpty()
        assertTrue(
            "should mention calls: $callingReason",
            callingReason.contains("call", ignoreCase = true),
        )
    }
}
