package com.bulwark.app.shizuku

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The plumbing every privileged call shares: reach a system service at uid
 * 2000, and call a method the platform would rather we did not.
 *
 * Extracted from `PrivilegedPackages` on 2026-09-10 when a second caller
 * appeared. It is deliberately mechanical - the same code, moved - because the
 * enumeration path is the one thing in this project verified end-to-end on
 * hardware, and there is no unit test that would catch breaking it.
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
 * [invokeHidden] sidesteps that by never creating a linked reference at all.
 * So nothing here uses a typed stub, and the stub dependency was dropped: an
 * unused dependency is attack surface for nothing
 * (`context/_shared/supply-chain.md`).
 *
 * ## Threading
 *
 * Every call is a blocking binder transaction to another process. Never call
 * one from the main thread.
 */
internal object PrivilegedBinder {

    const val PM_INTERFACE = "android.content.pm.IPackageManager"
    const val PM_STUB = "android.content.pm.IPackageManager\$Stub"

    /**
     * `IPackageManager` as seen from uid 2000.
     *
     * The [ShizukuBinderWrapper] is the entire privilege escalation: Shizuku
     * forwards each transaction with *its* uid instead of ours. Everything
     * else in this file is just reaching a method that is not public SDK.
     *
     * Taken per call and not held. `security.md` FIXED-9: there is no bound
     * service to outlive the screen that wanted it.
     */
    fun packageManager(): Any {
        val binder = SystemServiceHelper.getSystemService("package")
            ?: error("System package service is unavailable")
        return invokeHidden(
            Class.forName(PM_STUB), null, "asInterface", ShizukuBinderWrapper(binder),
        ) ?: error("IPackageManager.Stub.asInterface returned null")
    }

    /** Calls [name] on the `IPackageManager` interface class. */
    fun callPackageManager(service: Any, name: String, vararg args: Any?): Any? =
        invokeHidden(Class.forName(PM_INTERFACE), service, name, *args)

    /**
     * Calls a non-SDK method, using the bypass only where one is needed.
     *
     * **API 28+** - `HiddenApiBypass.invoke`. The non-SDK blocklist exists and
     * has to be routed around.
     *
     * **API 26-27** - plain reflection. The restriction was introduced in
     * Android 9, so before that there is nothing to bypass. Using the bypass
     * there would not merely be redundant: `HiddenApiBypass.invoke` is itself
     * `@RequiresApi(28)` and would crash. Lint caught exactly that, which is
     * the third real bug it has found in this codebase that reading missed.
     *
     * Methods are matched on name and arity. That is loose in general and
     * sufficient here: on API 26-27 the overloads that differ do so by type,
     * not count, and the pre-33 branch is the one being selected anyway.
     *
     * **Untested on API 26-27** - no such hardware to hand. Marked honestly
     * rather than assumed working.
     */
    fun invokeHidden(
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
