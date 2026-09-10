package com.bulwark.app.security

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * Defences against another app drawing on top of Bulwark.
 *
 * This matters more here than in an ordinary app. Bulwark's confirmation
 * screens authorise destructive, system-level changes. An invisible overlay
 * that persuades someone they are tapping "Later" while they are actually
 * tapping "Remove" could take out their dialer — the precise outcome
 * `context/_shared/safety-rules.md` exists to prevent.
 *
 * Neither protection is on by default in Android, which is why most apps have
 * neither. See `context/_shared/security.md`.
 */
object WindowHardening {

    /**
     * Applies both available layers of overlay defence:
     *
     * 1. **API 31+: hide overlays outright.** `setHideOverlayWindows(true)`
     *    stops non-system overlays being drawn over this window at all. This
     *    is the strong version — the attack cannot render.
     * 2. **All versions: discard obscured touches.** `filterTouchesWhenObscured`
     *    makes the framework drop any touch that arrived while the window was
     *    covered. Weaker, because the overlay still draws, but it is the only
     *    option below API 31 — and our minSdk is 26, so a real share of users
     *    depend on it.
     *
     * Call from every Activity, before the user can touch anything.
     */
    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.window.setHideOverlayWindows(true)
        }
        // Set on the decor view so the whole hierarchy inherits the behaviour;
        // Compose has no per-composable equivalent of the View attribute.
        activity.window.decorView.filterTouchesWhenObscured = true
    }

    /**
     * Hides this window's content from non-tool accessibility services.
     *
     * A malicious Accessibility service can read our screen and synthesise
     * taps on it. Since Bulwark holds shell access, anything that can press
     * Bulwark's buttons holds shell by proxy - see `DestructiveActionGuard`,
     * which is the real defence.
     *
     * API 34+ only. It hides content from services that have **not** declared
     * `isAccessibilityTool`, so genuine screen readers such as TalkBack keep
     * working. That distinction matters: Bulwark exists for people who are
     * poorly served by their phones, and blinding a screen reader to "harden"
     * the app would be a straightforward harm.
     *
     * **Honest limit:** `isAccessibilityTool` is self-declared. Malware can
     * simply claim it. This raises cost, it does not close the hole. Treat
     * authentication as the control and this as a speed bump.
     */
    fun hideFromAutomation(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.window.decorView.setAccessibilityDataSensitive(
                View.ACCESSIBILITY_DATA_SENSITIVE_YES
            )
        }
    }

    /**
     * Blocks screenshots and hides the window from the recents preview.
     *
     * **Not applied globally, deliberately.** Debloat users legitimately want
     * to screenshot a package list to ask for help, and taking that away for
     * no threat-model reason is the kind of security theatre that trains
     * people to distrust real warnings.
     *
     * Apply it to screens where the *content itself* endangers the user if
     * seen over their shoulder — the stalkerware findings screen above all,
     * where `context/_shared/threat-model.md` notes that discovery can
     * escalate abuse.
     */
    fun markSensitive(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
