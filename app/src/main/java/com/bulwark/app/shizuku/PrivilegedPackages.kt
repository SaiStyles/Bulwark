package com.bulwark.app.shizuku

import android.content.pm.PackageManager
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

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
 * `IPackageManager` is not public SDK, and two separate mechanisms guard it -
 * a distinction that cost a failed run on hardware to learn:
 *
 * - **Compile time.** Hidden-API stubs (`dev.rikka.hidden:stub`) or the Refine
 *   plugin give you a typed reference so the code compiles.
 * - **Runtime.** The platform's non-SDK blocklist rejects the call regardless.
 *
 * Solving the first does nothing about the second. Worse, solving the first
 * *causes* the second to bite harder: a typed call is a **linked** dex
 * reference, and the runtime refuses it outright -
 *
 *     hiddenapi: IPackageManager->getInstalledPackages(JI) api=blocked,
 *     TargetSdkVersion=37, using linking: denied
 *
 * `HiddenApiBypass.invoke` sidesteps that by never creating a linked reference
 * at all. So this file uses no typed stub and the stub dependency was dropped:
 * an unused dependency is attack surface for nothing
 * (`context/_shared/supply-chain.md`).
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

    private const val PM_INTERFACE = "android.content.pm.IPackageManager"
    private const val PM_STUB = "android.content.pm.IPackageManager\$Stub"

    /**
     * Package names visible to uid 2000.
     *
     * @param userId Android user. 0 is primary; a device with a Private Space
     *   also has user 10, whose package set is different.
     * @throws Exception if the privileged call fails. Callers report it rather
     *   than returning an empty list - "zero packages" and "the call failed"
     *   must never look the same on screen.
     */
    @Suppress("UNCHECKED_CAST")
    fun list(includeUninstalled: Boolean = false, userId: Int = 0): List<String> {
        val binder = SystemServiceHelper.getSystemService("package")
            ?: error("System package service is unavailable")

        val stubClass = Class.forName(PM_STUB)
        val pmClass = Class.forName(PM_INTERFACE)

        // Shizuku forwards each transaction with ITS uid instead of ours.
        // This wrapper is the entire privilege escalation; everything else here
        // is just reaching a method the platform would rather we did not.
        val service = HiddenApiBypass.invoke(
            stubClass, null, "asInterface", ShizukuBinderWrapper(binder),
        ) ?: error("IPackageManager.Stub.asInterface returned null")

        val flags = if (includeUninstalled) MATCH_UNINSTALLED else 0L

        // getInstalledPackages takes long flags from API 33, int before it.
        // minSdk is 26, so both are live in our supported range.
        val slice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HiddenApiBypass.invoke(pmClass, service, "getInstalledPackages", flags, userId)
        } else {
            HiddenApiBypass.invoke(pmClass, service, "getInstalledPackages", flags.toInt(), userId)
        } ?: error("getInstalledPackages returned null")

        val list = HiddenApiBypass.invoke(slice.javaClass, slice, "getList") as? List<Any>
            ?: error("ParceledListSlice.getList returned null")

        return list.mapNotNull { info ->
            info.javaClass.getField("packageName").get(info) as? String
        }
    }
}
