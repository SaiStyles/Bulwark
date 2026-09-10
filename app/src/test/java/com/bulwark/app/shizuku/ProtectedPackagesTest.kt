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
 * tests are the watching.
 */
class ProtectedPackagesTest {

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
            assertTrue("must protect $it", ProtectedPackages.isProtected(it))
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
            assertFalse("$it must not be hard-refused", ProtectedPackages.isProtected(it))
            val caution = ProtectedPackages.cautionFor(it)
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
            assertTrue("$it must be refused outright", ProtectedPackages.isProtected(it))
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
            assertTrue("$it must be refused outright", ProtectedPackages.isProtected(it))
            assertNull("refused things do not also warn", ProtectedPackages.cautionFor(it))
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
            assertTrue("must protect $it", ProtectedPackages.isProtected(it))
        }
    }

    @Test
    fun `protects Shizuku and Bulwark itself`() {
        // Removing Shizuku mid-operation strands the user with no undo path.
        assertTrue(ProtectedPackages.isProtected("moe.shizuku.privileged.api"))
        // An app that can uninstall itself can destroy its own undo log.
        assertTrue(ProtectedPackages.isProtected("com.bulwark.app"))
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
            assertTrue("must protect $it", ProtectedPackages.isProtected(it))
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
            assertTrue("must protect $it", ProtectedPackages.isProtected(it))
        }
    }

    @Test
    fun `mediatek is matched exactly, not as a prefix`() {
        // "mediatek" as a fragment would protect every MediaTek package,
        // including the many that are safely removable. An over-broad rule
        // that defeats the feature is its own kind of failure.
        assertTrue(ProtectedPackages.isProtected("com.mediatek"))
        assertFalse(ProtectedPackages.isProtected("com.mediatek.duraspeed"))
        assertFalse(ProtectedPackages.isProtected("com.mediatek.camera"))
    }

    @Test
    fun `empty or blank input fails closed`() {
        // Cannot reason about it, so refuse. safety-rules.md rule 6.
        assertTrue(ProtectedPackages.isProtected(""))
        assertTrue(ProtectedPackages.isProtected("   "))
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(ProtectedPackages.isProtected("COM.ANDROID.PHONE"))
        assertTrue(ProtectedPackages.isProtected("Com.Android.SystemUI"))
    }

    @Test
    fun `still allows ordinary removable bloatware`() {
        // Over-broad matching is the intended bias, but it must not be so
        // broad that the app cannot do its job at all.
        listOf(
            "com.facebook.katana",
            "com.example.game",
            "com.netflix.mediaclient",
            "com.oem.weatherwidget",
            "com.thirdparty.shoppingapp",
        ).forEach {
            assertFalse("should be removable: $it", ProtectedPackages.isProtected(it))
        }
    }

    @Test
    fun `gives a reason for refusing, and none when allowing`() {
        assertNotNull(ProtectedPackages.reasonFor("com.android.phone"))
        assertNotNull(ProtectedPackages.reasonFor("com.android.systemui"))
        assertNull(ProtectedPackages.reasonFor("com.example.game"))
    }

    @Test
    fun `reasons name the actual consequence, not a generic refusal`() {
        // A user told "no" without a reason will find a tool that says yes.
        val callingReason = ProtectedPackages.reasonFor("com.android.phone").orEmpty()
        assertTrue(
            "should mention calls: $callingReason",
            callingReason.contains("call", ignoreCase = true),
        )
    }
}
