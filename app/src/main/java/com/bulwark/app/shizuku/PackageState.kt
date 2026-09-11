package com.bulwark.app.shizuku

import android.content.pm.PackageManager
import android.os.Build

/**
 * Reading and changing whether a package is enabled, at uid 2000.
 *
 * This is `pm disable-user` and `pm enable`, done through `IPackageManager`
 * rather than by spawning a shell. Same operation, no command line for a
 * package name to be interpreted by (`CommandSafety`).
 *
 * **Nothing here is a policy decision.** It does not consult the never-remove
 * list, does not ask for authentication and does not write the log. Those sit
 * above it in `policy/`, and this stays the dumbest possible layer so that the
 * guard cannot be satisfied by the thing it is guarding.
 *
 * ## Proven on hardware
 *
 * Watched working on the Agni 2, 2026-09-10, against `com.android.egg`:
 * `0` -> `3` -> `0`, surviving a reboot. Unlike `getInstalledPackages`, these
 * calls went through `HiddenApiBypass.invoke` first try - the signature has
 * been `(String, int, int, int, String)` since well before minSdk 26, so there
 * is no long/int split to branch on.
 */
object PackageState {

    /**
     * Enabled-states, from `PackageManager`. Public SDK constants, so these
     * are named rather than reflected.
     *
     * `DISABLED_USER` (3) is what `pm disable-user` sets and what Bulwark
     * uses: it is the state a *user* chose, distinct from `DISABLED` (2) which
     * an app sets for its own components. Using the right one matters because
     * Settings shows a user-disabled app as something the user can turn back
     * on, which is the whole basis for calling this reversible.
     */
    const val DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT           // 0
    const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED           // 1
    const val DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED         // 2
    const val DISABLED_USER = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER // 3
    const val DISABLED_UNTIL_USED =
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED               // 4

    /** True when this state means the package is not running. */
    fun isDisabled(state: Int): Boolean = state == DISABLED || state == DISABLED_USER

    /**
     * The current enabled-state of [packageName].
     *
     * **Read this before disabling and record it**, because reversible means
     * back to what it was rather than back to enabled. A package sitting at
     * [DISABLED_UNTIL_USED] that Bulwark set to [DISABLED_USER] must return to
     * [DISABLED_UNTIL_USED]; restoring it to [ENABLED] would be Bulwark making
     * a change of its own while claiming to undo one.
     *
     * @throws Exception if the privileged call fails. Callers must not treat a
     *   failure as [DEFAULT] - "we could not read it" and "it is at its
     *   default" are different facts, and only one of them is safe to act on.
     */
    fun get(packageName: String, userId: Int = 0): Int {
        val service = PrivilegedBinder.packageManager()
        return PrivilegedBinder.callPackageManager(
            service, "getApplicationEnabledSetting", packageName, userId,
        ) as? Int ?: error("getApplicationEnabledSetting returned no state for $packageName")
    }

    /**
     * Sets the enabled-state of [packageName].
     *
     * @param state one of the constants above.
     * @param userId Android user. 0 is primary.
     *
     * `flags` is passed as 0. The one flag that exists here is
     * `DONT_KILL_APP`, and **not** passing it is deliberate: disabling an app
     * whose process keeps running would leave the user looking at something
     * they just switched off, and a "disabled" app still executing is exactly
     * the false sense of protection `safety-rules.md` forbids.
     *
     * `callingPackage` is our own package name. The platform records it as
     * who asked, which is the correct answer and also the auditable one - a
     * user running `pm list` afterwards should see Bulwark named, not shell.
     */
    fun set(packageName: String, state: Int, userId: Int = 0, callingPackage: String) {
        val service = PrivilegedBinder.packageManager()
        // The parameter has been (String, int, int, int, String) since well
        // before minSdk 26, so unlike getInstalledPackages there is no
        // long/int split to branch on across our supported range.
        PrivilegedBinder.callPackageManager(
            service, "setApplicationEnabledSetting",
            packageName, state, 0, userId, callingPackage,
        )
    }

    /**
     * Puts a package back to a previously recorded state.
     *
     * Falls back to [DEFAULT] rather than [ENABLED] when nothing was recorded.
     * `DEFAULT` means "whatever the system says", which for a package that
     * shipped enabled is enabled - and for one that shipped disabled leaves it
     * disabled, instead of Bulwark switching on something the vendor had off.
     */
    fun restore(packageName: String, previousState: Int?, userId: Int = 0, callingPackage: String) {
        set(packageName, previousState ?: DEFAULT, userId, callingPackage)
    }

    /**
     * Whether this device is new enough for the calls above to be shaped as
     * written. Present so a caller can fail with a clear message rather than a
     * reflection error.
     */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
