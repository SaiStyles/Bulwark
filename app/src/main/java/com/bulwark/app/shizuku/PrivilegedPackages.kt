package com.bulwark.app.shizuku

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Package enumeration at uid 2000, through Shizuku's binder.
 *
 * ## Why this exists
 *
 * Measured on the Agni 2, 2026-09-10: Bulwark's own `PackageManager` sees 176
 * packages; shell sees 370. All 194 missing are system packages - exactly what
 * debloat targets. Without this class layer 1 can see 70% of nothing.
 *
 * ## Why not a user service
 *
 * Because it does not work. `Shizuku.bindUserService` fails on MediaTek with an
 * NPE inside `LoadedApk.makeApplicationInner`, and MediaTek is this project's
 * target rather than an edge case. `ShizukuBinderWrapper` needs no separate
 * process, so none of that applies. See `context/_shared/app-architecture.md`.
 *
 * ## Why everything here is reflection
 *
 * `IPackageManager` is not public SDK and two independent mechanisms guard it.
 * The plumbing and the full explanation are in [PrivilegedBinder].
 *
 * ## Threading
 *
 * Every call is a blocking binder transaction to another process. Never call it
 * from the main thread.
 */
object PrivilegedPackages {

    /**
     * Packages uninstalled for the current user but still on the system
     * partition - the ones a previous debloat could restore. Equals
     * `pm list packages -u`.
     */
    private const val MATCH_UNINSTALLED = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()

    /**
     * `PackageManager.GET_SIGNATURES`. Deprecated for ordinary apps and still
     * the flag that fills the flat `signatures` array this reads.
     */
    private const val GET_SIGNATURES = PackageManager.GET_SIGNATURES.toLong()

    /**
     * Package names visible to uid 2000.
     *
     * @param userId Android user. 0 is primary; a device with a Private Space
     *   also has user 10, whose package set is different.
     * @throws Exception if the privileged call fails. Callers report it rather
     *   than returning an empty list - "zero packages" and "the call failed"
     *   must never look the same on screen.
     */
    fun list(includeUninstalled: Boolean = false, userId: Int = 0): List<String> =
        rawPackageInfos(includeUninstalled, userId).mapNotNull { info ->
            info.javaClass.getField("packageName").get(info) as? String
        }

    /**
     * SHA-1 of each installed package's signing certificate, lower-case hex.
     *
     * **A separate call, not a field on [Installed], and that is a measured
     * decision rather than a preference.** On stock Android 15, 2026-09-14:
     * asking `getInstalledPackages` for signatures costs **2 ms** because they
     * arrive in the same round trip, while hashing all 244 costs **41 ms** of
     * CPU. Every enumeration in Bulwark goes through [listDetailed] - the
     * package list, the audit, the firewall's system check - so a certificate
     * field there would charge all of them for work none of them uses. One
     * screen paying it once is a different thing from every screen paying it
     * always. Numbers in `context/devices/aosp-emulator.md`.
     *
     * **SHA-1 because that is what the indicator lists publish**, in 40-hex
     * form. Not a security choice: SHA-1 is weak against collisions and nothing
     * here rests on that. Computing SHA-256 instead - the reasonable modern
     * default, and the first thing this did - would compare two values that can
     * never be equal, matching nothing on every phone while every test stayed
     * green.
     *
     * `signatures` rather than `signingInfo`'s rotation history: the lists
     * publish the certificate a sample was signed with, which is the flat
     * value. Rotation is a refinement, and guessing wrong fails silently.
     *
     * A package whose signature cannot be read is **absent from the map**, not
     * present with a null - a caller must not be able to mistake "unreadable"
     * for "no certificate".
     *
     * @throws Exception if the privileged call fails, like every read here.
     */
    @Suppress("DEPRECATION")
    fun signingCertificates(userId: Int = 0): Map<String, String> {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val out = HashMap<String, String>()
        rawPackageInfos(includeUninstalled = false, userId = userId, extraFlags = GET_SIGNATURES)
            .forEach { info ->
                val packageInfo = info as? android.content.pm.PackageInfo ?: return@forEach
                val bytes = packageInfo.signatures?.firstOrNull()?.toByteArray() ?: return@forEach
                out[packageInfo.packageName] =
                    digest.digest(bytes).joinToString("") { "%02x".format(it) }
            }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun rawPackageInfos(
        includeUninstalled: Boolean,
        userId: Int,
        extraFlags: Long = 0L,
    ): List<Any> {
        val service = PrivilegedBinder.packageManager()
        val flags = (if (includeUninstalled) MATCH_UNINSTALLED else 0L) or extraFlags

        // getInstalledPackages takes long flags from API 33, int before it.
        // minSdk is 26, so both are live in our supported range.
        val slice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PrivilegedBinder.callPackageManager(service, "getInstalledPackages", flags, userId)
        } else {
            PrivilegedBinder.callPackageManager(
                service, "getInstalledPackages", flags.toInt(), userId,
            )
        } ?: error("getInstalledPackages returned null")

        val list = PrivilegedBinder.invokeHidden(slice.javaClass, slice, "getList") as? List<Any>
            ?: error("ParceledListSlice.getList returned null")

        return list
    }

    /**
     * Whether `ApplicationInfo.flags` describe a package that shipped with the
     * phone.
     *
     * **One home for this rule.** It was written out three times with the
     * constants inlined as `1` and `128`, which made a policy decision look
     * like arithmetic and meant changing it required finding all three.
     *
     * `FLAG_UPDATED_SYSTEM_APP` is the half people forget: a system app that
     * took a Play Store update is still a system app, and treating it as
     * user-installed would quietly widen what Bulwark offers to remove.
     */
    fun isSystemFlags(flags: Int): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** One installed package, with the little we need to classify it. */
    data class Installed(
        val packageName: String,
        val isSystem: Boolean,
        /**
         * Whether the package is currently switched on.
         *
         * Read so the UI can offer *one honest action* - "switch off" or
         * "switch back on" - rather than a blind "undo". Hardware testing on
         * 2026-09-10 showed why that matters: an Undo button that undoes the
         * previous undo performs a **disable** under a non-destructive label.
         */
        val isEnabled: Boolean,
    )

    /**
     * Like [list], but also reports whether each package is a system package.
     *
     * Reads `PackageInfo.applicationInfo.flags` directly - both are ordinary
     * public types once the privileged call has handed them back, so no bypass
     * is involved past the enumeration itself.
     */
    @Suppress("UNCHECKED_CAST")
    fun listDetailed(includeUninstalled: Boolean = false, userId: Int = 0): List<Installed> {
        val infos = rawPackageInfos(includeUninstalled, userId)
        return infos.mapNotNull { info ->
            val name = info.javaClass.getField("packageName").get(info) as? String
                ?: return@mapNotNull null
            val appInfo = info.javaClass.getField("applicationInfo").get(info)
            val flags = appInfo?.let {
                it.javaClass.getField("flags").getInt(it)
            } ?: 0
            val isSystem = isSystemFlags(flags)
            // ApplicationInfo.enabled is public SDK and already in hand from
            // the privileged enumeration, so this costs no extra binder call.
            val isEnabled = appInfo?.let {
                it.javaClass.getField("enabled").getBoolean(it)
            } ?: true
            Installed(name, isSystem, isEnabled)
        }
    }

    /**
     * Whether one package is a system package, asked of the platform.
     *
     * This is the authoritative answer, and it exists so that
     * [CommandSafety.requireMutable] can establish system-ness for itself
     * instead of trusting a flag handed down from the UI. The flag decides
     * whether [ProtectedPackages]' structural fragment list applies, so a
     * caller able to supply it is a caller able to unlock every OEM-renamed
     * telephony package by passing `false`.
     *
     * **Fails closed.** A package the platform cannot find, or a call that
     * comes back malformed, returns `true`: unknown means protected
     * (`safety-rules.md` rule 6). Refusing to act on a package that is not
     * installed costs nothing.
     *
     * Blocking binder call. Never on the main thread.
     */
    fun isSystemPackage(packageName: String, userId: Int = 0): Boolean = try {
        val service = PrivilegedBinder.packageManager()
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PrivilegedBinder.callPackageManager(
                service, "getApplicationInfo", packageName, 0L, userId,
            )
        } else {
            PrivilegedBinder.callPackageManager(
                service, "getApplicationInfo", packageName, 0, userId,
            )
        } ?: error("getApplicationInfo returned null for $packageName")

        isSystemFlags(appInfo.javaClass.getField("flags").getInt(appInfo))
    } catch (_: Throwable) {
        true
    }

}
