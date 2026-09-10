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
    fun `protects the messaging stack`() {
        listOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.cellbroadcastreceiver",
            "com.android.messaging",
        ).forEach {
            assertTrue("must protect $it", ProtectedPackages.isProtected(it))
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
