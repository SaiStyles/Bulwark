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
 * **Spike for A1.** Can Bulwark read an app's signing certificate?
 *
 * Package-name matching catches only the lazy case - monitoring software
 * renames itself routinely. The signing certificate is what survives a rename,
 * so a certificate hash is the signal an IOC list is actually worth carrying.
 *
 * Bulwark has never read one. Two things have to hold before A1 can rely on it:
 *
 * 1. The **privileged** PackageManager must return signing information.
 *    Bulwark declares no `QUERY_ALL_PACKAGES`, so its own `PackageManager`
 *    cannot see most packages at all - the same wall that made
 *    `PrivilegedPackages` necessary for enumeration.
 * 2. The flag argument changed to a `long` in later releases, exactly as
 *    `AppOpsWriter.uidOf` already has to handle, so both arities are tried.
 *
 * `conventions.md`: confirm the mechanism on hardware before writing feature
 * code. This is that confirmation and nothing else - it reads, hashes, and
 * asserts the shape. No IOC list, no matching, no screen.
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

        val byPackage = signingHashes()
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
            "com.android.settings had no signature; privileged read is not working",
            byPackage.containsKey("com.android.settings"),
        )

        byPackage.values.forEach { hash ->
            assertTrue("a SHA-256 is 64 hex characters, got '$hash'", hash.length == 64)
            assertTrue("must be hex: $hash", hash.all { it in "0123456789abcdef" })
        }

        // If the read returned something constant, every app would "match" any
        // IOC list - a false positive on every phone, which is worse than none.
        val distinct = byPackage.values.distinct()
        Log.i(TAG, "$TAG ${byPackage.size} packages, ${distinct.size} distinct certificates")
        assertTrue(
            "every package reported the same certificate, so this is not reading " +
                "per-app signing information",
            distinct.size > 1,
        )
    }

    /**
     * Every installed package mapped to the SHA-256 of its signing certificate.
     *
     * Built on the **same call `PrivilegedPackages` already proves**:
     * `getInstalledPackages` through the privileged binder, with the signature
     * flag added. Asking per package with `getPackageInfo` was the first
     * attempt and read nothing for any target - one round trip for the whole
     * phone is both what worked and what A1 actually needs.
     *
     * `signatures` rather than `signingInfo`: IOC lists publish the certificate
     * a sample was signed with, which is the flat value. Rotation history is a
     * refinement, and guessing wrong now would silently never match.
     */
    @Suppress("DEPRECATION")
    private fun signingHashes(): Map<String, String> {
        val service = PrivilegedBinder.packageManager()
        val slice = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PrivilegedBinder.callPackageManager(
                    service, "getInstalledPackages", GET_SIGNATURES, 0,
                )
            } else {
                PrivilegedBinder.callPackageManager(
                    service, "getInstalledPackages", GET_SIGNATURES.toInt(), 0,
                )
            }
            ) ?: error("getInstalledPackages returned null")

        val list = PrivilegedBinder.invokeHidden(slice.javaClass, slice, "getList") as? List<*>
            ?: error("ParceledListSlice.getList returned null")

        val digest = MessageDigest.getInstance("SHA-256")
        return list.filterIsInstance<PackageInfo>().mapNotNull { info ->
            val bytes = info.signatures?.firstOrNull()?.toByteArray() ?: return@mapNotNull null
            info.packageName to digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }.toMap()
    }

    private companion object {
        const val TAG = "BULWARK_SIG"

        /** `PackageManager.GET_SIGNATURES`. Deprecated for apps; still the flat value. */
        const val GET_SIGNATURES = 64L

        /** One Bulwark's own PackageManager cannot see, and two it can. */
        val TARGETS = listOf("com.android.settings", "com.android.chrome", "com.bulwark.app")
    }
}
