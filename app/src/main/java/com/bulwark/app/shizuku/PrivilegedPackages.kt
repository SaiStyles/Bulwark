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
    fun list(includeUninstalled: Boolean = false, userId: Int = 0): List<String> =
        rawPackageInfos(includeUninstalled, userId).mapNotNull { info ->
            info.javaClass.getField("packageName").get(info) as? String
        }

    @Suppress("UNCHECKED_CAST")
    private fun rawPackageInfos(includeUninstalled: Boolean, userId: Int): List<Any> {
        val binder = SystemServiceHelper.getSystemService("package")
            ?: error("System package service is unavailable")

        val stubClass = Class.forName(PM_STUB)
        val pmClass = Class.forName(PM_INTERFACE)

        // Shizuku forwards each transaction with ITS uid instead of ours.
        // This wrapper is the entire privilege escalation; everything else here
        // is just reaching a method the platform would rather we did not.
        val service = invokeHidden(stubClass, null, "asInterface", ShizukuBinderWrapper(binder))
            ?: error("IPackageManager.Stub.asInterface returned null")

        val flags = if (includeUninstalled) MATCH_UNINSTALLED else 0L

        // getInstalledPackages takes long flags from API 33, int before it.
        // minSdk is 26, so both are live in our supported range.
        val slice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            invokeHidden(pmClass, service, "getInstalledPackages", flags, userId)
        } else {
            invokeHidden(pmClass, service, "getInstalledPackages", flags.toInt(), userId)
        } ?: error("getInstalledPackages returned null")

        val list = invokeHidden(slice.javaClass, slice, "getList") as? List<Any>
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
     * Calls a non-SDK method, using the bypass only where one is needed.
     *
     * **API 28+** — `HiddenApiBypass.invoke`. The non-SDK blocklist exists and
     * has to be routed around.
     *
     * **API 26–27** — plain reflection. The restriction was introduced in
     * Android 9, so before that there is nothing to bypass. Using the bypass
     * there would not merely be redundant: `HiddenApiBypass.invoke` is itself
     * `@RequiresApi(28)` and would crash. Lint caught exactly that, which is
     * the third real bug it has found in this codebase that reading missed.
     *
     * Methods are matched on name and arity. That is loose in general and
     * sufficient here: on API 26–27 only the `int`-flags overload of
     * `getInstalledPackages` exists, and the other two calls take no
     * ambiguity.
     *
     * **Untested on API 26–27** — no such hardware to hand. Marked honestly
     * rather than assumed working.
     */
    private fun invokeHidden(
        clazz: Class<*>,
        receiver: Any?,
        name: String,
        vararg args: Any?,
    ): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return HiddenApiBypass.invoke(clazz, receiver, name, *args)
        }
        val method = clazz.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: error("No $name/${args.size} on ${clazz.name}")
        method.isAccessible = true
        return method.invoke(receiver, *args)
    }
}
