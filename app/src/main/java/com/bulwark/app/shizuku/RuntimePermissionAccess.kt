package com.bulwark.app.shizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.markImplied
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Reading and changing one app's runtime permissions, at uid 2000.
 *
 * This is `pm list permissions`, `pm revoke` and `pm grant` done through a
 * binder rather than by spawning a shell - same operation, no command line for
 * a package name to be interpreted by (`CommandSafety`).
 *
 * **Nothing here is a policy decision.** It does not consult the never-remove
 * list, does not ask for authentication and does not write the log. Those sit
 * above it in `policy/`, exactly as they do for [PackageState].
 *
 * ## The signature problem, and why this discovers instead of assuming
 *
 * The permission calls have moved twice. Up to Android 10 they were on
 * `IPackageManager`; Android 11 moved them to `IPermissionManager`; and the
 * newer releases added a virtual-device parameter, so the same method name
 * takes a different number of strings depending on the phone. Worse,
 * `getPermissionFlags` takes **(permission, package)** on the old interface and
 * **(package, permission)** on the new one - two `String`s either way, so a
 * wrong guess type-checks, runs, and answers a question about a package that
 * does not exist.
 *
 * ## Enumeration was the obvious answer and it kills the process
 *
 * The first version read the parameter types off the device and matched them
 * against a table. On the Agni 2 that **crashed with SIGSEGV inside
 * `Unsafe_getObject`** - `HiddenApiBypass.getDeclaredMethods` walking an
 * interface that plain reflection reports as having *zero* declared methods.
 * A native crash cannot be caught, so `runCatching` around it bought nothing
 * and the app would have died on the screen that called it.
 *
 * So the shape is established by **calling**, not by looking. Each candidate
 * in [SHAPES] is tried against `getPermissionFlags` until one resolves;
 * `NoSuchMethodException` means try the next. A device where none resolve gets
 * **no revoke offered at all** - guardrail 1 in
 * `context/layers/02-permissions.md`: an action whose undo cannot be resolved
 * is not reversible, and shipping it as one would be the false sense of
 * protection `safety-rules.md` calls worse than none.
 *
 * Probing uses the **read** call and never a write. `getPermissionFlags`
 * changes nothing, so trying four argument lists costs nothing; rule 6 forbids
 * feeling our way through destructive calls.
 *
 * It also probes on the **raw binder, unprivileged**. An unprivileged call
 * that reaches the permission check throws `SecurityException`, which proves
 * the method resolved just as well as a success does - so the shape is known
 * even with Shizuku dead, and the screen can say honestly what it will be able
 * to do before anyone is asked to set anything up.
 *
 * ## Threading
 *
 * Every call is a blocking binder transaction to another process. Never call
 * one from the main thread.
 */
internal object RuntimePermissionAccess {

    private const val PERMISSION_INTERFACE = "android.permission.IPermissionManager"
    private const val PERMISSION_STUB = "android.permission.IPermissionManager\$Stub"

    /** The name `PermissionManagerService` registers with, not `"permission"`. */
    private const val PERMISSION_SERVICE = "permissionmgr"

    /**
     * The argument shapes the permission calls come in, newest first.
     *
     * **Observed on the Agni 2, Android 15, 2026-09-11** for
     * [DEVICE_AWARE_PERMISSION_MANAGER] - each one confirmed by calling it
     * unprivileged and reading which way it failed. The other two come from the
     * platform's history and are **unconfirmed**: no device here runs them.
     * They are tried after the observed one, and a wrong guess costs a
     * `NoSuchMethodException` rather than a wrong answer.
     *
     * The detail a table written from documentation got wrong: on Android 15
     * `revokeRuntimePermission` takes its **reason after the user id**, not
     * before it. A rule like "the int is last" builds
     * `(pkg, perm, deviceId, reason, userId)`, which does not resolve here at
     * all - and on some other build might resolve and mean something else.
     */
    enum class Flavour {
        /**
         * `IPermissionManager` with a virtual-device parameter.
         *
         *     getPermissionFlags(pkg, perm, deviceId, userId)
         *     grantRuntimePermission(pkg, perm, deviceId, userId)
         *     revokeRuntimePermission(pkg, perm, deviceId, userId, reason)
         */
        DEVICE_AWARE_PERMISSION_MANAGER,

        /**
         * `IPermissionManager`, no device parameter. Android 11 to 13.
         * **Unconfirmed on hardware.**
         *
         *     getPermissionFlags(pkg, perm, userId)
         *     grantRuntimePermission(pkg, perm, userId)
         *     revokeRuntimePermission(pkg, perm, userId, reason)
         */
        PERMISSION_MANAGER,

        /**
         * `IPackageManager`, the pre-Android-11 home. **Unconfirmed.**
         *
         * **`getPermissionFlags` here takes (permission, package)** - the
         * reverse of every other call in this file. Getting it backwards reads
         * the flags of a package named `android.permission.CAMERA`, which does
         * not exist, which reads as "no flags set", which reads as "safe to
         * offer". A silent wrong answer in the direction of acting.
         *
         *     getPermissionFlags(perm, pkg, userId)
         *     grantRuntimePermission(pkg, perm, userId)
         *     revokeRuntimePermission(pkg, perm, userId)
         */
        LEGACY_PACKAGE_MANAGER,
        ;

        fun flagsArgs(packageName: String, permission: String, userId: Int): Array<Any?> =
            when (this) {
                DEVICE_AWARE_PERMISSION_MANAGER ->
                    arrayOf(packageName, permission, deviceId(), userId)
                PERMISSION_MANAGER -> arrayOf(packageName, permission, userId)
                LEGACY_PACKAGE_MANAGER -> arrayOf(permission, packageName, userId)
            }

        fun grantArgs(packageName: String, permission: String, userId: Int): Array<Any?> =
            when (this) {
                DEVICE_AWARE_PERMISSION_MANAGER ->
                    arrayOf(packageName, permission, deviceId(), userId)
                else -> arrayOf(packageName, permission, userId)
            }

        fun revokeArgs(packageName: String, permission: String, userId: Int): Array<Any?> =
            when (this) {
                DEVICE_AWARE_PERMISSION_MANAGER ->
                    arrayOf(packageName, permission, deviceId(), userId, REASON)
                PERMISSION_MANAGER -> arrayOf(packageName, permission, userId, REASON)
                LEGACY_PACKAGE_MANAGER -> arrayOf(packageName, permission, userId)
            }
    }

    /** The resolved API, or null when this device has none we recognise. */
    @Volatile
    private var resolved: Flavour? = null

    @Volatile
    private var resolutionAttempted = false

    /**
     * The flavour this device has, resolved once by trying the read call.
     *
     * Cached because the answer cannot change while the process lives - it is a
     * property of the platform image. The cached value is a plain enum, not a
     * binder, so this does not reintroduce the held-service problem
     * `security.md` FIXED-9 removed.
     */
    fun flavour(): Flavour? {
        if (resolutionAttempted) return resolved
        synchronized(this) {
            if (resolutionAttempted) return resolved
            resolved = runCatching { resolve() }.getOrNull()
            resolutionAttempted = true
        }
        return resolved
    }

    /**
     * Whether Bulwark may offer permission changes on this device.
     *
     * Read this **before drawing a revoke control**, not after pressing it.
     * Needs no Shizuku - the probe runs against the raw binder, and a refusal
     * proves as much as a success - so the screen can say honestly what it will
     * be able to do before anyone is asked to set anything up.
     */
    val canChangePermissions: Boolean get() = flavour() != null

    /**
     * Tries each shape's **read** call until one resolves.
     *
     * Probes with Bulwark's own package and a permission it declares, so the
     * question put to the platform is about us and nobody else.
     *
     * `NoSuchMethodException` means this shape is not what the device has, so
     * move on. **Anything else means it resolved** - including the
     * `SecurityException` an unprivileged probe earns, which is the expected
     * outcome when Shizuku is not running and is just as good an answer.
     */
    private fun resolve(): Flavour? {
        Flavour.entries.forEach { flavour ->
            val service = runCatching { probeService(flavour) }.getOrNull() ?: return@forEach
            val clazz = runCatching { interfaceClass(flavour) }.getOrNull() ?: return@forEach
            val outcome = runCatching {
                PrivilegedBinder.invokeHidden(
                    clazz, service, "getPermissionFlags",
                    *flavour.flagsArgs(OUR_PACKAGE, PROBE_PERMISSION, 0),
                )
            }
            if (outcome.isSuccess) return flavour
            val cause = generateSequence(outcome.exceptionOrNull()) { it.cause }.last()
            val unresolved = cause is NoSuchMethodException || cause is NoSuchMethodError
            if (!unresolved) return flavour
        }
        // Nothing recognised. Refuse rather than approximate.
        return null
    }

    /**
     * A service object for probing: the **raw** binder, deliberately.
     *
     * Shizuku may not be running when the screen first asks whether permission
     * changes are possible, and the answer must not depend on that. An
     * unprivileged probe is refused by the system *after* the method resolves,
     * which is all resolution needs to know.
     */
    private fun probeService(flavour: Flavour): Any? {
        val name = if (flavour == Flavour.LEGACY_PACKAGE_MANAGER) "package" else PERMISSION_SERVICE
        val binder = SystemServiceHelper.getSystemService(name) ?: return null
        val stub =
            if (flavour == Flavour.LEGACY_PACKAGE_MANAGER) PrivilegedBinder.PM_STUB
            else PERMISSION_STUB
        return PrivilegedBinder.invokeHidden(Class.forName(stub), null, "asInterface", binder)
    }

    private fun interfaceClass(flavour: Flavour): Class<*> = when (flavour) {
        Flavour.LEGACY_PACKAGE_MANAGER -> Class.forName(PrivilegedBinder.PM_INTERFACE)
        else -> Class.forName(PERMISSION_INTERFACE)
    }

    /** The privileged service, through Shizuku. Real calls, never probes. */
    private fun service(flavour: Flavour): Any = when (flavour) {
        Flavour.LEGACY_PACKAGE_MANAGER -> PrivilegedBinder.packageManager()
        else -> {
            val binder = SystemServiceHelper.getSystemService(PERMISSION_SERVICE)
                ?: error("Permission service is unavailable")
            PrivilegedBinder.invokeHidden(
                Class.forName(PERMISSION_STUB), null, "asInterface", ShizukuBinderWrapper(binder),
            ) ?: error("IPermissionManager.Stub.asInterface returned null")
        }
    }

    /**
     * The virtual device to act on: this one.
     *
     * Read from the platform rather than written as a literal, because it is a
     * constant the platform defines and `conventions.md` forbids inlining
     * those. Null when it cannot be read - which the probe showed the call
     * still accepts.
     */
    private fun deviceId(): String? = runCatching {
        Class.forName("android.companion.virtual.VirtualDeviceManager")
            .getField("PERSISTENT_DEVICE_ID_DEFAULT")
            .get(null) as? String
    }.getOrNull()

    /** Our own package, and a permission it declares. Probes ask about us. */
    private const val OUR_PACKAGE = "com.bulwark.app"
    private const val PROBE_PERMISSION = "android.permission.USE_BIOMETRIC"

    /**
     * `getPermissionFlags`, interpreted by `PermissionFlags`.
     *
     * @throws Exception if the call fails or no flavour resolved. A failure is
     *   never read as "no flags": flags decide whether a revoke is offered, and
     *   defaulting to zero on an error means offering exactly the permissions
     *   we could not check.
     */
    fun flags(packageName: String, permission: String, userId: Int = 0): Int {
        val flavour = flavour() ?: error("This device has no permission API Bulwark recognises")
        return PrivilegedBinder.invokeHidden(
            interfaceClass(flavour), service(flavour), "getPermissionFlags",
            *flavour.flagsArgs(packageName, permission, userId),
        ) as? Int ?: error("getPermissionFlags returned no flags for $permission on $packageName")
    }

    /**
     * Takes [permission] away from [packageName].
     *
     * **Undo:** [grant] with the same arguments. Proven possible at uid 2000 on
     * the Agni 2, 2026-09-11 - `dumpsys package com.android.shell` shows
     * `GRANT_RUNTIME_PERMISSIONS: granted=true`, and a grant/revoke round trip
     * on `com.android.egg` confirmed it through the shell.
     *
     * Returning without throwing does **not** mean it worked: a `SYSTEM_FIXED`
     * permission accepts this call and ignores it. The caller reads the state
     * back - `policy/PermissionActions` does, and it is the only caller.
     */
    fun revoke(packageName: String, permission: String, userId: Int = 0) {
        val flavour = flavour() ?: error("This device has no permission API Bulwark recognises")
        PrivilegedBinder.invokeHidden(
            interfaceClass(flavour), service(flavour), "revokeRuntimePermission",
            *flavour.revokeArgs(packageName, permission, userId),
        )
    }

    /** Gives [permission] back. The undo for [revoke]. */
    fun grant(packageName: String, permission: String, userId: Int = 0) {
        val flavour = flavour() ?: error("This device has no permission API Bulwark recognises")
        PrivilegedBinder.invokeHidden(
            interfaceClass(flavour), service(flavour), "grantRuntimePermission",
            *flavour.grantArgs(packageName, permission, userId),
        )
    }

    /** Recorded by the platform as why the permission changed. */
    private const val REASON = "Changed by Bulwark at the user's request"

    /**
     * Everything [packageName] asks for, whether it holds it, and how fixed it
     * is.
     *
     * @param ownPackageManager the app's **own** `PackageManager`, used only to
     *   ask what kind of permission each name is. That is metadata about the
     *   permission, not about the package, so it needs no privilege - and it
     *   keeps a public SDK call public instead of reflecting a third signature.
     * @param withFlags false to skip the per-permission flag read. One binder
     *   call per permission is nothing for one app's detail screen and is a
     *   great deal across 370 of them, so the audit view asks for the cheap
     *   answer and pays for flags only where it is about to offer something.
     *
     * @throws Exception if the privileged read fails. Never an empty list on
     *   error: "asks for nothing" and "we could not ask" are different facts.
     */
    fun holdings(
        packageName: String,
        ownPackageManager: PackageManager,
        userId: Int = 0,
        withFlags: Boolean = true,
    ): List<PermissionHolding> {
        val info = packageInfo(packageName, userId)
        val names = info.requestedPermissions ?: return emptyList()
        val granted = info.requestedPermissionsFlags

        return names.mapIndexed { index, permission ->
            val isGranted = granted != null && index < granted.size &&
                (granted[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            PermissionHolding(
                packageName = packageName,
                permission = permission,
                isGranted = isGranted,
                isRuntime = isRuntime(permission, ownPackageManager),
                // Flags only matter for something we might offer to change, and
                // they cost a binder call each.
                flags = if (withFlags && isGranted) flags(packageName, permission, userId) else 0,
            )
        }
    }

    /**
     * Every granted runtime permission across [packageNames], **without flags**.
     *
     * The device-wide sweep behind the "who can hear me" view. Flags are
     * skipped on purpose: they cost one binder call each, and asking for them
     * here would mean a few thousand calls to draw a list where most rows will
     * never be acted on. [refineWithFlags] pays for them on the handful of rows
     * a user actually opens.
     *
     * A package that cannot be read is **skipped, not guessed at**. One app
     * refusing to answer must not take down the audit for the other 369, and it
     * must not appear as an app holding nothing - which would be a false
     * statement about someone's phone rather than a gap.
     *
     * @param installed the packages to sweep, with the system flag the
     *   never-remove list needs. Names alone would mean asking the platform
     *   again for something the caller already knows.
     * @return one entry per granted permission per app. Ungranted requests are
     *   dropped here: a list of apps that *could* ask answers a different and
     *   much less useful question.
     */
    fun sweep(
        installed: List<PrivilegedPackages.Installed>,
        ownPackageManager: PackageManager,
        userId: Int = 0,
    ): List<PermissionHolding> = installed.flatMap { app ->
        // The never-remove list is answered here rather than by the screen,
        // because this is the side of the binder it lives on and because the
        // caller already knows which packages are system - the flag that
        // decides whether the structural fragment list applies at all.
        val protectedApp = ProtectedPackages.isProtected(app.packageName, app.isSystem)
        runCatching {
            holdings(app.packageName, ownPackageManager, userId, withFlags = false)
                .filter { it.isGranted }
                .map { it.copy(isProtected = protectedApp) }
        }.getOrDefault(emptyList())
    }

    /**
     * Fills in the flags for rows the user is about to be offered.
     *
     * Split from [sweep] because this is the expensive half and only a
     * few dozen rows ever need it. A row whose flags cannot be read comes back
     * **unchanged**, which leaves it at zero - and zero reads as "offerable".
     *
     * That is the one place in this file where a failure could widen what
     * Bulwark offers rather than narrow it, so it does not fail quietly: the
     * row is dropped from the result instead, and the caller shows fewer rows
     * rather than a row it cannot vouch for.
     */
    fun refineWithFlags(
        holdings: List<PermissionHolding>,
        userId: Int = 0,
    ): List<PermissionHolding> = holdings.mapNotNull { holding ->
        runCatching {
            holding.copy(flags = flags(holding.packageName, holding.permission, userId))
        }.getOrNull()
    }

    /**
     * Everything one app holds, ready for the screen to render.
     *
     * The per-app view's loader. Unlike [sweep] this **does** pay for flags:
     * one app is a handful of calls, and every row here is one the user may be
     * about to act on, so offerability has to be known before anything is
     * drawn.
     *
     * Returns holdings already marked with both refusals the screen must
     * respect - the never-remove list, and one permission implying another -
     * so the composable renders answers rather than working them out.
     *
     * @param isSystem from the privileged enumeration the caller already has.
     *   It decides whether the never-remove list's structural fragments apply,
     *   and asking the platform again for something the caller knows is a
     *   binder call for nothing.
     */
    fun forApp(
        packageName: String,
        isSystem: Boolean,
        ownPackageManager: PackageManager,
        userId: Int = 0,
    ): List<PermissionHolding> {
        val protectedApp = ProtectedPackages.isProtected(packageName, isSystem)
        val raw = holdings(packageName, ownPackageManager, userId, withFlags = true)
            .map { it.copy(isProtected = protectedApp) }
        return markImplied(raw)
    }

    /**
     * Whether one permission is currently granted, read fresh.
     *
     * This is the read-back that makes a revoke honest. It goes back to the
     * platform rather than trusting what the call returned, because on
     * 2026-09-11 the call returned fine and the permission stayed granted.
     */
    fun isGranted(packageName: String, permission: String, userId: Int = 0): Boolean {
        val info = packageInfo(packageName, userId)
        val names = info.requestedPermissions ?: return false
        val granted = info.requestedPermissionsFlags ?: return false
        val index = names.indexOf(permission)
        if (index < 0 || index >= granted.size) return false
        return (granted[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun packageInfo(packageName: String, userId: Int): PackageInfo {
        val service = PrivilegedBinder.packageManager()
        val flags = PackageManager.GET_PERMISSIONS.toLong()
        // Same long/int split as getInstalledPackages: long flags from API 33,
        // int before it, and minSdk 26 keeps both live.
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PrivilegedBinder.callPackageManager(service, "getPackageInfo", packageName, flags, userId)
        } else {
            PrivilegedBinder.callPackageManager(
                service, "getPackageInfo", packageName, flags.toInt(), userId,
            )
        }
        return info as? PackageInfo ?: error("getPackageInfo returned nothing for $packageName")
    }

    /**
     * Whether a permission is a runtime one - the kind a person is meant to be
     * able to answer for.
     *
     * **Three-valued.** Null means Bulwark could not find out, which happens
     * for permissions an app declares itself. Returning false there would put
     * "Android does not let anything switch this off" on screen about a
     * permission that might well be revocable, and a confident wrong sentence
     * is the failure mode this project keeps paying for. `Revocable` refuses to
     * offer on null, so unknown fails closed *and* says so.
     *
     * Memoised: the answer is a property of the permission, not of the app, and
     * a device-wide audit asks about the same thirty names hundreds of times.
     */
    private val runtimeKind = HashMap<String, Boolean?>()

    private fun isRuntime(permission: String, packageManager: PackageManager): Boolean? =
        synchronized(runtimeKind) {
            // containsKey rather than a null check, so "we asked and could not
            // tell" is remembered as an answer instead of being asked again for
            // every one of 370 packages.
            if (runtimeKind.containsKey(permission)) return@synchronized runtimeKind[permission]
            val answer = runCatching {
                val info = packageManager.getPermissionInfo(permission, 0)
                val base = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                base == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrNull()
            runtimeKind[permission] = answer
            answer
        }
}
