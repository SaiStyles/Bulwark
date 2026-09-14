package com.bulwark.app.security

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bulwark.app.shizuku.PrivilegedPackages
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/**
 * **What the real pipeline finds on the device it is run on.** An instrument,
 * not a verdict.
 *
 * `MonitoringIndicatorsOnDevice` has a false-positive check, and it is weaker
 * than it looks: it enumerates through Bulwark's **own** `PackageManager`,
 * which has no `QUERY_ALL_PACKAGES` and therefore cannot see most of the phone.
 * On the Agni 2 that is 176 packages of 368. A clean result from it says much
 * less than it appears to.
 *
 * This runs what the Audit screen runs - privileged enumeration, privileged
 * certificate read, the real bundled list - and says what came back.
 *
 * **It does not assert that nothing matched**, and must never be changed to. A
 * phone that genuinely carries monitoring software would then fail its owner's
 * test suite, which is both useless and backwards. What it asserts is that the
 * pipeline actually ran: a silent empty enumeration would otherwise read as
 * good news, which is this feature's one forbidden sentence.
 */
@RunWith(AndroidJUnit4::class)
class MonitoringOnThisPhoneProbe {

    @Test
    fun whatTheRealPipelineFindsOnThisPhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "SKIPPED: Shizuku is not running, so the privileged reads are unavailable.",
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
        )

        val list = MonitoringIndicators.load(context)
        assertTrue("the indicator list did not load, so nothing was checked", list != null)

        val certificates = PrivilegedPackages.signingCertificates()
        val installed = PrivilegedPackages.listDetailed().map {
            InstalledApp(it.packageName, certificates[it.packageName])
        }

        // The comparison that makes the number mean something: what Bulwark's
        // own PackageManager can see, against what the privileged one can.
        val unprivileged = context.packageManager.getInstalledPackages(0).size
        Log.i(TAG, "$TAG privileged=${installed.size} unprivileged=$unprivileged")
        Log.i(TAG, "$TAG certificates read=${certificates.size} list entries=${list!!.size}")

        assertTrue("nothing was enumerated, so a clean result would mean nothing", installed.size > 50)
        assertTrue(
            "the privileged enumeration saw no more than the ordinary one, so " +
                "this is not testing what it claims to test",
            installed.size >= unprivileged,
        )

        val found = list.match(installed)
        if (found.isEmpty()) {
            Log.i(TAG, "$TAG NO MATCHES across ${installed.size} packages")
        } else {
            Log.w(TAG, "$TAG ${found.size} MATCH(ES) - each one needs looking at by hand:")
            found.forEach {
                Log.w(
                    TAG,
                    "$TAG   ${it.packageName} listed as ${it.names} kinds=${it.kinds} " +
                        "signals=${it.signals} ambiguous=${it.ambiguous}",
                )
            }
        }
    }

    private companion object { const val TAG = "BULWARK_SCAN" }
}
