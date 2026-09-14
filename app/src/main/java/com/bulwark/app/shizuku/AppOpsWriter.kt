package com.bulwark.app.shizuku

import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Changing an app op at uid 2000. **The only writing path in this package.**
 *
 * Separate from [AppOpsAccess], which reads and says so in its own first line.
 * That claim is worth keeping true: a reader anyone can audit at a glance is
 * the thing that makes "Bulwark only looks" checkable, and a write bolted into
 * it would end that.
 *
 * Measured writable on the Agni 2, 2026-09-14 - see
 * `context/layers/02-permissions/app-ops.md`, which carries the evidence and
 * the two findings below.
 *
 * ## Two doors, and picking the wrong one writes nothing
 *
 * An op can be set per package ([setPackageMode]) or per uid ([setUidMode]),
 * and `checkOperation` resolves the **uid entry first**, falling through to the
 * package entry only when the uid one is `MODE_DEFAULT`. So writing the package
 * door on an op the phone holds at uid level stores something real and changes
 * nothing anyone can observe - indistinguishable from the platform refusing.
 * [doorFor] decides, and the policy layer refuses rather than guessing.
 *
 * ## Threading
 *
 * Blocking binder transactions. Never from the main thread.
 */
internal object AppOpsWriter {

    private const val APP_OPS_INTERFACE = "com.android.internal.app.IAppOpsService"
    private const val APP_OPS_STUB = "com.android.internal.app.IAppOpsService\$Stub"
    private const val APP_OPS_MANAGER = "android.app.AppOpsManager"

    /** `MODE_ALLOWED`, `MODE_IGNORED`, `MODE_DEFAULT` - the three we use. */
    const val MODE_ALLOWED = 0
    const val MODE_IGNORED = 1
    const val MODE_DEFAULT = 3

    /** Which entry actually governs an op for one app. */
    enum class Door {
        /** The op is held at uid level; only [setUidMode] moves it. */
        UID,

        /** No uid entry, so the package entry governs. */
        PACKAGE,
    }

    /**
     * Whether this device exposes an app-op API Bulwark recognises.
     *
     * False means no app-op change is offered at all, rather than one offered
     * and quietly broken - the same rule the permission layer follows.
     */
    val canChangeAppOps: Boolean
        get() = runCatching {
            Class.forName(APP_OPS_INTERFACE)
            Class.forName(APP_OPS_MANAGER)
            SystemServiceHelper.getSystemService("appops") != null
        }.getOrDefault(false)

    /**
     * Op name to this platform's int code.
     *
     * Never hardcoded. The ints have been reordered between releases, and for a
     * *write* a stale literal would silently change a different capability -
     * worse than failing, because it would be a confident wrong act on someone
     * else's phone.
     */
    fun opCode(opName: String): Int =
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_MANAGER), null, "strOpToOp", opName,
        ) as? Int ?: error("This phone does not know the app op $opName")

    /**
     * The uid of [packageName], asked of the privileged PackageManager.
     *
     * Bulwark declares no `QUERY_ALL_PACKAGES` and no `<queries>`, so its own
     * `PackageManager` cannot see most packages at all under targetSdk 37. Two
     * arities because the flags argument became a `long` in later releases.
     */
    fun uidOf(packageName: String, userId: Int = 0): Int {
        val pm = PrivilegedBinder.packageManager()
        val uid = runCatching {
            PrivilegedBinder.callPackageManager(pm, "getPackageUid", packageName, 0L, userId)
        }.getOrNull() ?: runCatching {
            PrivilegedBinder.callPackageManager(pm, "getPackageUid", packageName, 0, userId)
        }.getOrNull()
        return (uid as? Int)?.takeIf { it > 0 }
            ?: error("Could not read the uid of $packageName")
    }

    /**
     * Every package sharing [uid].
     *
     * The guard the whole feature rests on. `setUidMode` applies to **all** of
     * them, so a revoke aimed at one app can silently take the capability from
     * several - broader than anything the user chose, and unattributable
     * afterwards, which is what `safety-rules.md` rule 1 is about.
     *
     * Returns the single name when a uid is not shared, which is the ordinary
     * case. **Throws rather than returning empty** if the list cannot be read:
     * not knowing how many apps a write would hit is exactly when it must not
     * proceed.
     */
    @Suppress("UNCHECKED_CAST")
    fun packagesSharingUid(uid: Int): List<String> {
        val pm = PrivilegedBinder.packageManager()
        val names = PrivilegedBinder.callPackageManager(pm, "getPackagesForUid", uid)
            as? Array<String>
            ?: error("Could not read which packages share uid $uid")
        return names.toList()
    }

    /** The effective mode: the uid entry if there is one, else the package entry. */
    fun mode(code: Int, uid: Int, packageName: String): Int =
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service(), "checkOperation", code, uid, packageName,
        ) as? Int ?: error("checkOperation returned nothing for $packageName")

    /**
     * Which door governs this op for this app, read from the phone.
     *
     * A uid entry that is not `MODE_DEFAULT` wins, so that is [Door.UID].
     * Anything else falls through to the package entry.
     */
    fun doorFor(code: Int, uid: Int): Door =
        if (uidMode(code, uid) != MODE_DEFAULT) Door.UID else Door.PACKAGE

    /** The uid entry alone, ignoring any package entry. */
    fun uidMode(code: Int, uid: Int): Int =
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service(), "checkOperation", code, uid, null,
        ) as? Int ?: MODE_DEFAULT

    fun setPackageMode(code: Int, uid: Int, packageName: String, mode: Int) {
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service(), "setMode", code, uid, packageName, mode,
        )
    }

    fun setUidMode(code: Int, uid: Int, mode: Int) {
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service(), "setUidMode", code, uid, mode,
        )
    }

    /**
     * `IAppOpsService` as seen from uid 2000.
     *
     * Taken per call and never held. `security.md` FIXED-9: no bound service
     * outlives the screen that wanted it.
     */
    private fun service(): Any {
        val binder = SystemServiceHelper.getSystemService("appops")
            ?: error("App ops service is unavailable")
        return PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_STUB), null, "asInterface", ShizukuBinderWrapper(binder),
        ) ?: error("IAppOpsService.Stub.asInterface returned null")
    }
}
