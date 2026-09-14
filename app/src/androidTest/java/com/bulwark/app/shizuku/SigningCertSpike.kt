package com.bulwark.app.shizuku

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/**
 * [PrivilegedPackages.signingCertificates] against a real device.
 *
 * Package-name matching catches only the lazy case - monitoring software
 * renames itself routinely - so the signing certificate is the signal an
 * indicator list is actually worth carrying. Bulwark had never read one.
 *
 * **This began as a spike with its own copy of the read**, which is the right
 * way to answer "can this work at all" and the wrong thing to keep: two
 * implementations of the same privileged call drift, and the one under test
 * stops being the one that ships. It now drives the production function, so a
 * regression there fails here.
 *
 * ## What had to hold
 *
 * The **privileged** PackageManager has to return signing information. Bulwark
 * declares no `QUERY_ALL_PACKAGES`, so its own `PackageManager` cannot see most
 * packages at all - the wall that made this class necessary for enumeration.
 *
 * ## Checked against a second route
 *
 * On 2026-09-14 this produced `6046aafebcd3782ef7a1186007b84b1af6ff2d90` for
 * `com.bulwark.app`, and `keytool -list -v` on the debug keystore independently
 * reported `60:46:AA:FE:BC:D3:78:2E:F7:A1:18:60:07:B8:4B:1A:F6:FF:2D:90`. Two
 * ways of asking, one answer. That value is machine-specific - a CI box has a
 * different debug key - so it is recorded here rather than asserted.
 */
@RunWith(AndroidJUnit4::class)
class SigningCertSpike {

    @Test
    fun aSigningCertificateCanBeReadAndHashed() {
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access.",
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
        )

        val byPackage = PrivilegedPackages.signingCertificates()
        Log.i(TAG, "$TAG read ${byPackage.size} signing certificates")
        TARGETS.forEach { Log.i(TAG, "$TAG $it -> ${byPackage[it] ?: "could not read"}") }

        assertTrue(
            "no signing certificate could be read at all - the certificate half " +
                "of A1 cannot be built on this",
            byPackage.isNotEmpty(),
        )

        // One Bulwark's own PackageManager cannot see, which is the whole point
        // of going through the privileged one.
        assertTrue(
            "com.android.settings had no signature; the privileged read is not working",
            byPackage.containsKey("com.android.settings"),
        )

        byPackage.forEach { (pkg, hash) ->
            assertTrue("$pkg: a SHA-1 is 40 hex characters, got '$hash'", hash.length == 40)
            assertTrue("$pkg: must be lower-case hex, got '$hash'", hash.all { it in "0123456789abcdef" })
        }

        // If the read returned something constant, every app would match any
        // indicator list - a false positive on every phone, which is worse than
        // no feature at all.
        val distinct = byPackage.values.distinct()
        Log.i(TAG, "$TAG ${byPackage.size} packages, ${distinct.size} distinct certificates")
        assertTrue(
            "every package reported the same certificate, so this is not reading " +
                "per-app signing information",
            distinct.size > 1,
        )
    }

    /**
     * A package that cannot be read is absent, never present-and-empty.
     *
     * A caller must not be able to mistake "unreadable" for "no certificate":
     * the first is a gap in what Bulwark knows and the second would be a claim
     * about the app.
     */
    @Test
    fun unreadableCertificatesAreAbsentRatherThanBlank() {
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access.",
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
        )

        val byPackage = PrivilegedPackages.signingCertificates()
        assertTrue(
            "a blank or null value would read as \"this app has no certificate\"",
            byPackage.values.none { it.isBlank() },
        )
        assertTrue("keys must be package names", byPackage.keys.none { it.isBlank() })
    }

    private companion object {
        const val TAG = "BULWARK_SIG"

        /** One Bulwark's own PackageManager cannot see, and two it can. */
        val TARGETS = listOf("com.android.settings", "com.android.chrome", "com.bulwark.app")
    }
}
