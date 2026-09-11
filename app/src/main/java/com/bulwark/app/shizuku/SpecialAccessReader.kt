package com.bulwark.app.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.DeviceSignal

/**
 * Reads who holds which special access. **Entirely read-only.**
 *
 * ## Half of this needs no privilege at all
 *
 * Measured on the Agni 2, 2026-09-11. The two most dangerous accesses are
 * readable through public API with no Shizuku, no ADB and no developer
 * options:
 *
 * | Access | Source | Privilege |
 * |---|---|---|
 * | Accessibility | `AccessibilityManager.getEnabledAccessibilityServiceList` | none |
 * | Device Admin | `DevicePolicyManager.getActiveAdmins` | none |
 * | Notification listener | `Settings.Secure` | none for this key |
 * | The AppOps four | `IAppOpsService` at uid 2000 | Shizuku |
 *
 * That matters more than it looks. Accessibility is full UI read *and*
 * control; Device Admin resists uninstall. An audit that names those without
 * Shizuku works for every user on first launch - including the many who will
 * never get Shizuku running - and it inverts onboarding, because the user is
 * shown something worth caring about *before* being asked to do anything
 * difficult.
 *
 * ## Degrading honestly
 *
 * [read] takes whatever it can get and reports what it could not. A partial
 * audit presented as complete is the false sense of protection
 * `safety-rules.md` names as worse than none, so the caller is told which
 * sources were unavailable and the UI says so.
 */
object SpecialAccessReader {

    /**
     * Whether [packageName] came with the phone, or **null if we cannot tell**.
     *
     * Two sources, in this order, because neither alone is enough:
     *
     * 1. **[systemPackages]**, the privileged list, when Shizuku is up. It
     *    sees all 370 packages and is authoritative.
     * 2. **Bulwark's own `PackageManager`**, which needs no privilege but is
     *    subject to package-visibility filtering under targetSdk 37 - it can
     *    see about 176 of 370, so it answers for some apps and not others.
     *
     * Null when neither knows. That is a real answer and it is said out loud
     * on screen, because the alternative has already gone wrong twice here:
     * the first hardware run reported "2 you installed yourself" about the
     * launcher, and the second reported "cannot tell" about Chrome because the
     * privileged source had been dropped instead of ordered.
     */
    fun isSystem(
        context: Context,
        packageName: String,
        systemPackages: Set<String>?,
        knownPackages: Set<String>?,
    ): Boolean? {
        if (systemPackages != null && knownPackages != null && packageName in knownPackages) {
            return packageName in systemPackages
        }
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            PrivilegedPackages.isSystemFlags(info.flags)
        } catch (_: Throwable) {
            null
        }
    }

    /** What one pass could and could not see. */
    data class Result(
        /** Package name to the accesses it holds. Only non-empty entries. */
        val holders: Map<String, Set<Access>>,
        /** Sources that could not be read, in plain language. */
        val unavailable: List<String>,
        /** Device-level facts that matter in combination. See `RatSignals`. */
        val signals: Set<DeviceSignal> = emptySet(),
    )

    /**
     * Device-level facts, read from `Settings.Global`.
     *
     * No privilege needed - these keys are world-readable, which is the same
     * reason a rogue app can *check* them before deciding to exploit them.
     *
     * Absent or unreadable is treated as off. That is the safe direction here:
     * a missing key must not manufacture an alarm, and the cost of missing a
     * real one is covered by the other half of the signal (an app that can
     * control the screen) still being reported on its own.
     */
    private fun deviceSignals(context: Context): Set<DeviceSignal> {
        fun on(key: String) = runCatching {
            Settings.Global.getInt(context.contentResolver, key, 0) != 0
        }.getOrDefault(false)

        return buildSet {
            if (on("adb_wifi_enabled")) add(DeviceSignal.WIRELESS_DEBUGGING_ON)
            if (on("development_settings_enabled")) add(DeviceSignal.DEVELOPER_OPTIONS_ON)
        }
    }

    /**
     * Reads every source available right now.
     *
     * @param privileged whether Shizuku is alive and granted. When false the
     *   AppOps four are skipped and named in [Result.unavailable] rather than
     *   silently missing.
     *
     * Blocking. The privileged half is a binder transaction; never call from
     * the main thread.
     */
    fun read(context: Context, privileged: Boolean): Result {
        val holders = mutableMapOf<String, MutableSet<Access>>()
        val unavailable = mutableListOf<String>()

        fun add(pkg: String, access: Access) {
            holders.getOrPut(pkg) { mutableSetOf() } += access
        }

        runCatching { accessibilityServices(context) }
            .onSuccess { it.forEach { pkg -> add(pkg, Access.ACCESSIBILITY) } }
            .onFailure {
                unavailable += "apps that can control your screen"
                explain("accessibility", it)
            }

        runCatching { deviceAdmins(context) }
            .onSuccess { it.forEach { pkg -> add(pkg, Access.DEVICE_ADMIN) } }
            .onFailure {
                unavailable += "apps that can lock or wipe this phone"
                explain("device admin", it)
            }

        runCatching { notificationListeners(context) }
            .onSuccess { it.forEach { pkg -> add(pkg, Access.NOTIFICATION_LISTENER) } }
            .onFailure {
                unavailable += "apps that can read your notifications"
                explain("notification listeners", it)
            }

        if (privileged) {
            runCatching { AppOpsAccess.holders() }
                .onSuccess { found ->
                    found.forEach { (pkg, accesses) -> accesses.forEach { add(pkg, it) } }
                }
                .onFailure {
                    unavailable += "apps that can draw over others, see your app " +
                        "usage, read all files, or install apps"
                    explain("app ops", it)
                }
        } else {
            unavailable += "apps that can draw over others, see your app usage, " +
                "read all files, or install apps - start Shizuku to include these"
        }

        return Result(
            holders.mapValues { it.value.toSet() },
            unavailable,
            deviceSignals(context),
        )
    }

    /**
     * Says on the log why a source was unavailable.
     *
     * The user gets "Bulwark could not check X", which is what they need. This
     * is for whoever has to find out *why*, and it exists because the first
     * run of this audit failed one source silently - a swallowed cause is a
     * check nobody can watch fail (`lessons.md` lesson 2).
     *
     * Logcat only, never a file and never the network.
     */
    private fun explain(source: String, failure: Throwable) {
        Log.w("Bulwark", "Special-access source unavailable: $source", failure)
    }

    /**
     * Enabled accessibility services, by package.
     *
     * `getEnabledAccessibilityServiceList` is public API and needs no
     * permission - Android exposes it so apps can adapt their UI for screen
     * readers. Reading it to *audit* is the same call with a different
     * purpose.
     */
    private fun accessibilityServices(context: Context): Set<String> {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: error("No AccessibilityManager")
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }

    /** Active device-admin components, by package. Public API. */
    private fun deviceAdmins(context: Context): Set<String> {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? DevicePolicyManager ?: error("No DevicePolicyManager")
        @Suppress("DEPRECATION")
        return manager.activeAdmins.orEmpty().map { it.packageName }.toSet()
    }

    /**
     * Apps granted notification access.
     *
     * A colon-separated list of flattened `ComponentName`s in `Settings.Secure`.
     * This particular key is readable without `READ_SECURE_SETTINGS`; if a
     * future Android restricts it, the `runCatching` above degrades honestly
     * rather than reporting an empty set as "nothing found".
     */
    private fun notificationListeners(context: Context): Set<String> =
        Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            .toSet()

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
}
