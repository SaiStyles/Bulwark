package com.bulwark.app.shizuku

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

    @Suppress("UNCHECKED_CAST")
    private fun rawPackageInfos(includeUninstalled: Boolean, userId: Int): List<Any> {
        val service = PrivilegedBinder.packageManager()
        val flags = if (includeUninstalled) MATCH_UNINSTALLED else 0L

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

    /** One installed package, with the little we need to classify it. */
    data class Installed(val packageName: String, val isSystem: Boolean)

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
            // FLAG_SYSTEM (1) or FLAG_UPDATED_SYSTEM_APP (128). The second
            // matters: a system app that received a Play update is still a
            // system app, and treating it as user-installed would quietly
            // widen what we offer to remove.
            val isSystem = (flags and 1) != 0 || (flags and 128) != 0
            Installed(name, isSystem)
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

        val flags = appInfo.javaClass.getField("flags").getInt(appInfo)
        (flags and 1) != 0 || (flags and 128) != 0
    } catch (_: Throwable) {
        true
    }

}
