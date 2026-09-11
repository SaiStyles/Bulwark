package com.bulwark.app.shizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.bulwark.app.permissions.PermissionHolding
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
 * So the shape is read off the device with
 * [PrivilegedBinder.declaredParameterTypes] and matched against the table in
 * [Flavour]. A shape that is not in the table means **no revoke is offered on
 * this device at all** - see [canChangePermissions]. That is guardrail 1 in
 * `context/layers/02-permissions.md`: an action whose undo cannot be resolved
 * is not a reversible action, and shipping it as one would be the false sense
 * of protection `safety-rules.md` calls worse than none.
 *
 * The flavour is decided by the **read** call, never by trying a write.
 * `getPermissionFlags` changes nothing, so probing it costs nothing and rules
 * out no-op writes; rule 6 forbids feeling our way through destructive calls.
 *
 * ## UNVERIFIED ON HARDWARE
 *
 * Every other privileged path in this package has been watched working on the
 * Agni 2. This one has not: the 2026-09-11 spike proved `pm revoke` and
 * `pm grant` work at uid 2000 **through the shell**, which does not prove this
 * binder path resolves the same methods. `PrivilegedSmokeTest` has a read-only
 * check that names the resolved flavour, and `NOW.md` carries it as the next
 * hardware action.
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

    private val STRING: Class<*> = String::class.java
    private val INT: Class<*> = Int::class.javaPrimitiveType!!

    /**
     * Which generation of the permission API this device carries.
     *
     * Named by what is actually being distinguished - where the methods live
     * and what they take - rather than by Android version, because OEM builds
     * do not always match the release they claim.
     */
    enum class Flavour {
        /**
         * `IPermissionManager` with a virtual-device parameter. Introduced when
         * permissions became per-device; the extra `String` is a persistent
         * device id, not a reason.
         */
        DEVICE_AWARE_PERMISSION_MANAGER,

        /** `IPermissionManager`, no device parameter. Android 11 to 13. */
        PERMISSION_MANAGER,

        /**
         * `IPackageManager`, the pre-Android-11 home.
         *
         * **`getPermissionFlags` here takes (permission, package)** - the
         * reverse of every other call in this file. Getting it backwards reads
         * the flags of a package named `android.permission.CAMERA`, which does
         * not exist, which reads as "no flags set", which reads as "safe to
         * offer". A silent wrong answer in the direction of acting.
         */
        LEGACY_PACKAGE_MANAGER,
    }

    /** The resolved API, or null when this device has none we recognise. */
    @Volatile
    private var resolved: Flavour? = null

    @Volatile
    private var resolutionAttempted = false

    /**
     * The flavour this device has, resolved once.
     *
     * Cached because the answer cannot change while the process lives - it is
     * a property of the platform image. Nothing here is a binder, so this does
     * not reintroduce the held-service problem `security.md` FIXED-9 removed.
     */
    fun flavour(): Flavour? {
        if (resolutionAttempted) return resolved
        synchronized(this) {
            if (resolutionAttempted) return resolved
            resolved = resolve()
            resolutionAttempted = true
        }
        return resolved
    }

    /**
     * Whether Bulwark may offer permission changes on this device.
     *
     * Read this **before drawing a revoke control**, not after pressing it.
     * Needs no Shizuku: it inspects class metadata, so the screen can say
     * honestly what it will and will not be able to do before the user goes
     * anywhere near the onboarding.
     */
    val canChangePermissions: Boolean get() = flavour() != null

    private fun resolve(): Flavour? {
        val permissionManager = runCatching { Class.forName(PERMISSION_INTERFACE) }.getOrNull()
        if (permissionManager != null) {
            val shapes = runCatching {
                PrivilegedBinder.declaredParameterTypes(permissionManager, "getPermissionFlags")
            }.getOrDefault(emptyList())
            // (package, permission, persistentDeviceId, userId)
            if (shapes.any { it == listOf(STRING, STRING, STRING, INT) }) {
                return Flavour.DEVICE_AWARE_PERMISSION_MANAGER
            }
            // (package, permission, userId)
            if (shapes.any { it == listOf(STRING, STRING, INT) }) {
                return Flavour.PERMISSION_MANAGER
            }
        }

        val packageManager = runCatching { Class.forName(PrivilegedBinder.PM_INTERFACE) }.getOrNull()
            ?: return null
        val legacy = runCatching {
            PrivilegedBinder.declaredParameterTypes(packageManager, "getPermissionFlags")
        }.getOrDefault(emptyList())
        // (permission, package, userId) - reversed, see LEGACY_PACKAGE_MANAGER.
        if (legacy.any { it == listOf(STRING, STRING, INT) }) return Flavour.LEGACY_PACKAGE_MANAGER

        // Nothing recognised. Refuse rather than approximate.
        return null
    }

    private fun interfaceClass(flavour: Flavour): Class<*> = when (flavour) {
        Flavour.LEGACY_PACKAGE_MANAGER -> Class.forName(PrivilegedBinder.PM_INTERFACE)
        else -> Class.forName(PERMISSION_INTERFACE)
    }

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
     * The default virtual-device id, for the device-aware flavour.
     *
     * Null when the platform does not expose it, which is reported as a failed
     * call rather than guessed at: a made-up device id either throws or changes
     * a permission on a device that is not this one.
     */
    private fun defaultPersistentDeviceId(): String? = runCatching {
        Class.forName("android.companion.virtual.VirtualDeviceManager")
            .getField("PERSISTENT_DEVICE_ID_DEFAULT")
            .get(null) as? String
    }.getOrNull()

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
        val args: Array<Any?> = when (flavour) {
            Flavour.DEVICE_AWARE_PERMISSION_MANAGER ->
                arrayOf(packageName, permission, defaultPersistentDeviceId(), userId)
            Flavour.PERMISSION_MANAGER -> arrayOf(packageName, permission, userId)
            // Reversed on purpose. See LEGACY_PACKAGE_MANAGER.
            Flavour.LEGACY_PACKAGE_MANAGER -> arrayOf(permission, packageName, userId)
        }
        return PrivilegedBinder.invokeHidden(
            interfaceClass(flavour), service(flavour), "getPermissionFlags", *args,
        ) as? Int ?: error("getPermissionFlags returned no flags for $permission on $packageName")
    }

    /**
     * Takes [permission] away from [packageName].
     *
     * **Undo:** [grant] with the same arguments. Proven possible at uid 2000 on
     * the Agni 2, 2026-09-11 - `dumpsys package com.android.shell` shows
     * `GRANT_RUNTIME_PERMISSIONS: granted=true`, and a grant/revoke round trip
     * on `com.android.egg` confirmed it.
     *
     * Returning without throwing does **not** mean it worked: a `SYSTEM_FIXED`
     * permission accepts this call and ignores it. The caller reads the state
     * back - `policy/PermissionActions` does, and it is the only caller.
     */
    fun revoke(packageName: String, permission: String, userId: Int = 0) =
        change("revokeRuntimePermission", packageName, permission, userId)

    /** Gives [permission] back. The undo for [revoke]. */
    fun grant(packageName: String, permission: String, userId: Int = 0) =
        change("grantRuntimePermission", packageName, permission, userId)

    /**
     * The shared body of [grant] and [revoke].
     *
     * The argument list is built from the shape the device actually declares,
     * under one stated rule: **the first two strings are the package and the
     * permission, the single int is the user, and anything left over is
     * bookkeeping** - a device id under the device-aware flavour, then a reason
     * string. Every known signature fits that rule; a shape that does not fit
     * is refused rather than filled in hopefully.
     *
     * Why a rule rather than a list of exact signatures: the two four-argument
     * variants differ only in what the third string *means*, which no amount of
     * reflection can tell us. The flavour - established by the read call -
     * decides that, and the caller's read-back catches it if the inference is
     * wrong on some build we have not seen.
     */
    private fun change(method: String, packageName: String, permission: String, userId: Int) {
        val flavour = flavour() ?: error("This device has no permission API Bulwark recognises")
        val clazz = interfaceClass(flavour)

        // How many strings past the package and the permission this flavour
        // needs filled in. The device-aware one must be told which device, or
        // it changes nothing on this one.
        val minimumExtras = if (flavour == Flavour.DEVICE_AWARE_PERMISSION_MANAGER) 1 else 0

        val shape = PrivilegedBinder.declaredParameterTypes(clazz, method)
            .filter { types ->
                types.size >= 3 &&
                    types.take(2) == listOf(STRING, STRING) &&
                    types.all { it == STRING || it == INT } &&
                    types.count { it == INT } == 1 &&
                    types.size - 3 >= minimumExtras &&
                    types.size - 3 <= MAX_EXTRA_STRINGS
            }
            // The fewest bookkeeping arguments that still satisfies the
            // flavour: every extra string is one more thing inferred rather
            // than read, and inference is what this file is trying to avoid.
            .minByOrNull { it.size }
            ?: error("No usable $method on ${clazz.name} - Bulwark will not guess at one")

        // In order: a device id where the flavour needs one, then why we asked.
        val bookkeeping = ArrayDeque<Any?>().apply {
            if (minimumExtras > 0) addLast(defaultPersistentDeviceId())
            addLast(REASON)
        }

        val args = mutableListOf<Any?>(packageName, permission)
        shape.drop(2).forEach { type ->
            args += if (type == INT) userId else bookkeeping.removeFirstOrNull()
        }

        PrivilegedBinder.invokeHidden(clazz, service(flavour), method, *args.toTypedArray())
    }

    /** A device id and a reason. Anything past that is a shape we do not know. */
    private const val MAX_EXTRA_STRINGS = 2

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
     * @return one entry per granted permission per app. Ungranted requests are
     *   dropped here: a list of apps that *could* ask answers a different and
     *   much less useful question.
     */
    fun sweep(
        packageNames: List<String>,
        ownPackageManager: PackageManager,
        userId: Int = 0,
    ): List<PermissionHolding> = packageNames.flatMap { packageName ->
        runCatching {
            holdings(packageName, ownPackageManager, userId, withFlags = false)
                .filter { it.isGranted }
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
