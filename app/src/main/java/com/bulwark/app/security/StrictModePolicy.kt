package com.bulwark.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode

/**
 * Makes Bulwark's central promise fail loudly rather than quietly.
 *
 * ## Why this is not an Application subclass
 *
 * It was one, for a day, and it broke the entire privilege path.
 *
 * Shizuku launches the privileged user service in a stripped-down process.
 * That process calls `LoadedApk.makeApplication()`, which tries to instantiate
 * whatever `android:name` the manifest declares on `<application>` - and
 * NPEs, because the process has no real application context to build one in:
 *
 *     java.lang.NullPointerException
 *         at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1505)
 *
 * The failure surfaces as the service never binding, so the app sits on
 * "connecting" forever with nothing in its own logs. The stack trace is under
 * the `ShizukuServiceStarter` tag, not ours.
 *
 * **Do not add a custom Application class to this app** unless you have first
 * proven the user service still binds on hardware. A debug-only convenience is
 * not worth the feature the whole product is built on. See
 * `context/_shared/security.md`.
 */
object StrictModePolicy {

    /** Call from the main Activity. Debug builds only; no-op in release. */
    fun installIfDebuggable(context: Context) {
        if (isDebuggable(context)) enableStrictMode()
    }

    /**
     * Third layer of the zero-network guarantee.
     *
     * The other two are structural: no `INTERNET` permission in the manifest,
     * so Android refuses at runtime, and `verifyNoNetworkPermission`, which
     * fails the build if one ever appears. This one catches the case those
     * miss — a developer adding networking code that has not shipped yet, or
     * a dependency attempting a connection during development.
     *
     * `penaltyDeath` is deliberate. A logged warning gets scrolled past; a
     * process that dies gets fixed. The promise in `README.md` is that nothing
     * leaves the phone, and the cost of finding out late is a broken promise
     * to people who trusted a privacy tool.
     *
     * Debug builds only, because `penaltyDeath` in a shipped app turns a
     * mistake into a crash on a stranger's phone.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .apply {
                    // Intent redirection is a real privilege-escalation route
                    // for an app holding shell access. API 31+ only.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                    }
                }
                // Deliberately NOT detectNonSdkApiUsage(): reaching hidden
                // APIs through Shizuku is the point of this app, so it would
                // fire constantly and train us to ignore StrictMode.
                .penaltyLog()
                .build()
        )
    }

    /**
     * Read from the running app rather than `BuildConfig`, so this keeps
     * working regardless of whether the build-config feature is enabled.
     */
    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
