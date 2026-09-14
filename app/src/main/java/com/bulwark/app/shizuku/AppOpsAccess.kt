package com.bulwark.app.shizuku

import android.app.AppOpsManager
import com.bulwark.app.permissions.Access
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The four special accesses that live in AppOps, read at uid 2000.
 *
 * Overlay, usage access, all-files and install-unknown-apps are not
 * permissions in the ordinary sense - they are **app ops**, a parallel system
 * underneath runtime permissions with no user-facing list. `IAppOpsService`
 * is the only way to ask who holds them, and it is not public SDK.
 *
 * ## One call, not four
 *
 * `getPackagesForOps(int[] ops)` returns every package holding any of the
 * requested ops, each with its uid and op entries. Four questions, one binder
 * transaction.
 *
 * ## Op codes, and why they are not hardcoded
 *
 * The method takes `int` op codes; the stable public names are strings
 * (`OPSTR_*`). The int values have been reordered between Android releases, so
 * hardcoding them would work on one version and silently read the *wrong ops*
 * on another - which is worse than failing, because it would report confident
 * nonsense about someone's phone.
 *
 * `AppOpsManager.strOpToOp(String)` does the conversion and is non-SDK, so it
 * goes through the same bypass as everything else here. An op the platform
 * does not know is skipped rather than guessed at.
 *
 * Passing `null` for all ops was considered and rejected: it returns every op
 * for every package, which on a 370-package device risks the 1 MB binder
 * transaction limit. Named ops keep the response small and the intent legible.
 *
 * ## The bypass has to cover what comes *back*, not just what you call
 *
 * The first hardware run failed here, and the call itself was fine.
 * `getPackagesForOps` returned its list; reading `OpEntry.getOp()` off that
 * list was denied:
 *
 *     hiddenapi: AppOpsManager$OpEntry;->getOp()I (api=max-target-r)
 *     using reflection: denied
 *
 * `max-target-r` means blocked above targetSdk 30, and Bulwark targets 37. The
 * bypass had been applied to the methods *invoked* and plain reflection used
 * for the accessors on the returned objects - which are just as non-SDK. A
 * privileged call is not one method; it is every method along the chain,
 * including the ones on the value it hands back.
 *
 * `getOpStr()` is used in preference to `getOp()` where the platform has it:
 * it returns the op name, so the response needs no int mapping at all and the
 * int codes are confined to the request.
 *
 * ## The mode that counts is the effective one
 *
 * `getPackagesForOps` reports each op's **package** entry. An op can also be
 * held against the **uid**, and the uid entry wins - so the package entry is
 * only half the answer. Reading it alone made this card keep listing an app
 * whose access Bulwark had just taken away through the uid door, on
 * 2026-09-14: the revoke worked and the screen said otherwise, which is the
 * worst of both.
 *
 * So every candidate is confirmed with `checkOperation`, which resolves both
 * entries the way the platform does. A few extra binder calls for a handful of
 * apps, in exchange for the card and the phone agreeing.
 *
 * **Entirely read-only.** Nothing here changes an op - `AppOpsWriter.mode` is
 * borrowed for its read, not for its writes.
 */
internal object AppOpsAccess {

    private const val APP_OPS_INTERFACE = "com.android.internal.app.IAppOpsService"
    private const val APP_OPS_STUB = "com.android.internal.app.IAppOpsService\$Stub"

    /**
     * The ops we ask about, and what each one means to us.
     *
     * Two are public constants. The other two have no public `OPSTR_` and are
     * written as the platform's own op strings - the same values
     * `AppOpsManager` uses internally and `cmd appops` accepts. They are
     * literals rather than reflected constants because the *string* is the
     * stable part; it is the integer codes that get reordered between
     * releases, which is why [holders] converts at runtime rather than
     * hardcoding numbers.
     *
     * An op the platform does not recognise is skipped, never guessed.
     */
    private val WANTED: Map<String, Access> =
        Access.entries.mapNotNull { access -> access.opName?.let { it to access } }.toMap()

    /** `MODE_ALLOWED`. Anything else is not a grant. */
    private const val MODE_ALLOWED = AppOpsManager.MODE_ALLOWED

    /**
     * Package name to the accesses it currently holds.
     *
     * @throws Exception if the privileged call fails. The caller reports that
     *   rather than returning an empty map - "nothing holds these" and "we
     *   could not ask" must never look the same on screen.
     */
    @Suppress("UNCHECKED_CAST")
    fun holders(): Map<String, Set<Access>> {
        val opsClass = Class.forName("android.app.AppOpsManager")
        val byCode: Map<Int, Access> = WANTED.mapNotNull { (name, access) ->
            val code = runCatching {
                PrivilegedBinder.invokeHidden(opsClass, null, "strOpToOp", name) as? Int
            }.getOrNull()
            // An op this platform does not know is skipped, never guessed.
            code?.let { it to access }
        }.toMap()
        if (byCode.isEmpty()) error("No known app ops to query on this platform")

        val binder = SystemServiceHelper.getSystemService("appops")
            ?: error("App ops service is unavailable")
        val service = PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_STUB), null, "asInterface", ShizukuBinderWrapper(binder),
        ) ?: error("IAppOpsService.Stub.asInterface returned null")

        val packageOps = PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service,
            "getPackagesForOps", byCode.keys.toIntArray(),
        ) as? List<Any> ?: return emptyMap()

        val codeFor: Map<Access, Int> = byCode.entries.associate { it.value to it.key }

        val result = mutableMapOf<String, MutableSet<Access>>()
        packageOps.forEach { entry ->
            val pkg = PrivilegedBinder.invokeHidden(
                entry.javaClass, entry, "getPackageName",
            ) as? String ?: return@forEach
            val uid = PrivilegedBinder.invokeHidden(entry.javaClass, entry, "getUid") as? Int
            val ops = PrivilegedBinder.invokeHidden(
                entry.javaClass, entry, "getOps",
            ) as? List<Any> ?: return@forEach

            ops.forEach { op ->
                // getOpStr() keeps the response in op *names*, so nothing here
                // depends on the int codes that get reordered between
                // releases. getOp() is the fallback for platforms without it.
                val access = runCatching {
                    PrivilegedBinder.invokeHidden(op.javaClass, op, "getOpStr") as? String
                }.getOrNull()?.let { WANTED[it] }
                    ?: runCatching {
                        PrivilegedBinder.invokeHidden(op.javaClass, op, "getOp") as? Int
                    }.getOrNull()?.let { byCode[it] }
                    ?: return@forEach

                // The effective mode, not the package entry this row carries.
                // Falls back to the package entry only when the uid is unknown,
                // which is a worse answer and is why it is the fallback.
                val code = codeFor[access]
                val effective = if (uid != null && code != null) {
                    runCatching { AppOpsWriter.mode(code, uid, pkg) }.getOrNull()
                } else {
                    null
                }
                val mode = effective
                    ?: PrivilegedBinder.invokeHidden(op.javaClass, op, "getMode") as? Int
                    ?: return@forEach
                if (mode != MODE_ALLOWED) return@forEach

                result.getOrPut(pkg) { mutableSetOf() } += access
            }
        }
        return result.mapValues { it.value.toSet() }
    }
}
