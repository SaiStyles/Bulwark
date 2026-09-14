package com.bulwark.app.shizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.security.MessageDigest

/**
 * **What the certificate read costs**, measured before deciding where it lives.
 *
 * A1 needs a signing certificate for every installed package. The call it needs
 * is the one `PrivilegedPackages.listDetailed` already makes, with one extra
 * flag - so the choice is whether `Installed` gains a certificate field, or
 * whether the read stays a separate function only A1 pays for.
 *
 * That choice turns on a number, not a preference. Every enumeration in Bulwark
 * goes through `listDetailed` - the package list, the audit, the firewall's
 * system check - so a field there is a cost the whole app pays on every screen.
 * If the extra work is tens of milliseconds the argument for separating it
 * evaporates and one call is simpler; if it is hundreds, it must not be in the
 * common path.
 *
 * `observability.md`: measure the read before promising a screen on top of it.
 * `jobscheduler` was cut on exactly this kind of number.
 *
 * This asserts almost nothing on purpose. It is an instrument; the numbers go
 * in `devices/aosp-emulator.md` and the decision follows them.
 */
@RunWith(AndroidJUnit4::class)
class SigningCertCostProbe {

    @Test
    fun whatAddingSignaturesToTheEnumerationCosts() {
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access.",
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
        )

        // Warm up: first call pays class loading and the hidden-API bypass, and
        // timing that would measure the harness rather than the work.
        enumerate(withSignatures = false)
        enumerate(withSignatures = true)

        val plain = ArrayList<Long>(RUNS)
        val withCerts = ArrayList<Long>(RUNS)
        val hashing = ArrayList<Long>(RUNS)
        var packages = 0
        var certificates = 0

        repeat(RUNS) {
            var infos: List<PackageInfo>
            plain += measure { infos = enumerate(withSignatures = false); packages = infos.size }
            withCerts += measure { infos = enumerate(withSignatures = true) }

            val loaded = enumerate(withSignatures = true)
            var hashed = 0
            hashing += measure {
                val digest = MessageDigest.getInstance("SHA-1")
                loaded.forEach { info ->
                    val bytes = info.signatures?.firstOrNull()?.toByteArray() ?: return@forEach
                    digest.digest(bytes).joinToString("") { b -> "%02x".format(b) }
                    hashed++
                }
            }
            certificates = hashed
        }

        Log.i(TAG, "$TAG packages=$packages certificates=$certificates runs=$RUNS")
        report("enumerate, no signatures", plain)
        report("enumerate, with signatures", withCerts)
        report("SHA-1 over all certificates", hashing)
        Log.i(
            TAG,
            "$TAG extra cost of signatures = ${withCerts.median() - plain.median()}ms median, " +
                "plus ${hashing.median()}ms hashing",
        )

        assertTrue("nothing was enumerated, so these numbers mean nothing", packages > 0)
        assertTrue("no certificates were read", certificates > 0)
    }

    @Suppress("DEPRECATION")
    private fun enumerate(withSignatures: Boolean): List<PackageInfo> {
        val service = PrivilegedBinder.packageManager()
        val flags = if (withSignatures) GET_SIGNATURES else 0L
        val slice = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PrivilegedBinder.callPackageManager(service, "getInstalledPackages", flags, 0)
            } else {
                PrivilegedBinder.callPackageManager(service, "getInstalledPackages", flags.toInt(), 0)
            }
            ) ?: error("getInstalledPackages returned null")
        val list = PrivilegedBinder.invokeHidden(slice.javaClass, slice, "getList") as? List<*>
            ?: error("getList returned null")
        return list.filterIsInstance<PackageInfo>()
    }

    private inline fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private fun report(what: String, times: List<Long>) {
        Log.i(TAG, "$TAG $what: median=${times.median()}ms min=${times.min()}ms max=${times.max()}ms")
    }

    private companion object {
        const val TAG = "BULWARK_COST"
        const val GET_SIGNATURES = 64L
        const val RUNS = 7
    }
}
