package com.bulwark.app.shizuku

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import rikka.shizuku.ShizukuBinderWrapper

/**
 * `pm uninstall --user 0` and `pm install-existing`, through the binder.
 *
 * The escalation above [PackageState]. Disable stays the default and this is
 * what `safety-rules.md` rule 3 calls the escalation - offered for anything
 * from 2026-09-12, with the undo stated per package rather than assumed.
 *
 * ## What "uninstall" actually does here
 *
 * `--user 0` removes the package **for this user**. A preinstalled app's APK
 * stays on `/system`, so [installExisting] brings it back - the app returns and
 * its data does not. An app the user installed has no such copy: it is gone,
 * and Bulwark says so on the row before anything happens rather than after.
 *
 * ## The wrapper has to be applied twice
 *
 * `IPackageManager.getPackageInstaller()` hands back an `IPackageInstaller`
 * proxy built from a **raw** binder - the [ShizukuBinderWrapper] does not
 * travel through the call. Using it directly would run the uninstall as our
 * own uid, which fails, and the failure looks like a permission problem rather
 * than a plumbing one. So the returned binder is unwrapped and re-wrapped
 * here. This is the single most common way this call is got wrong.
 *
 * ## The result is read back, never taken on trust
 *
 * The platform reports through an `IntentSender`, asynchronously. Rule 6 says
 * confirm the change landed, and a callback that says "success" is still a
 * claim - so [uninstall] re-reads the installed set and fails if the package
 * is still there. The broadcast is required by the signature and is otherwise
 * ignored.
 */
internal object PackageRemoval {

    private const val INSTALLER_INTERFACE = "android.content.pm.IPackageInstaller"
    private const val INSTALLER_STUB = "android.content.pm.IPackageInstaller\$Stub"

    /** Our own no-op result channel. Named so it is obvious in a bug report. */
    private const val RESULT_ACTION = "com.bulwark.app.UNINSTALL_RESULT"

    /**
     * Removes [packageName] for [userId].
     *
     * @throws IllegalStateException if the package is still installed
     *   afterwards. A silent no-op is the failure this project has already
     *   been bitten by on `pm revoke`.
     */
    fun uninstall(
        context: Context,
        packageName: String,
        userId: Int = 0,
        callingPackage: String,
    ) {
        val installer = packageInstaller()
        val versioned = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)

        PrivilegedBinder.invokeHidden(
            Class.forName(INSTALLER_INTERFACE),
            installer,
            "uninstall",
            versioned,
            callingPackage,
            0, // flags: remove for this user, nothing exotic
            resultSender(context),
            userId,
        )

        // Read it back. The IntentSender has not necessarily fired yet and its
        // word would not be evidence anyway.
        if (isInstalledForUser(packageName, userId)) {
            error(
                "Uninstall of $packageName returned without error and the package " +
                    "is still installed for user $userId",
            )
        }
    }

    /**
     * Puts a preinstalled package back for [userId], from the copy on
     * `/system`.
     *
     * Only meaningful for a package whose APK is still on the system image.
     * For anything the user installed there is nothing to install from, which
     * is why the row says so before the uninstall rather than after.
     *
     * @throws IllegalStateException if the package is still missing afterwards.
     */
    fun installExisting(packageName: String, userId: Int = 0) {
        val service = PrivilegedBinder.packageManager()

        // The signature grew over time and arity is what `invokeHidden`
        // matches on, so the longest known form is tried first and each
        // shorter one is a fallback rather than a guess.
        val attempts: List<Array<Any?>> = listOf(
            arrayOf(packageName, userId, 0, 0, null),  // API 29+: + whitelisted permissions
            arrayOf(packageName, userId, 0, 0),        // API 28
            arrayOf(packageName, userId, 0),           // older
        )
        var lastFailure: Throwable? = null
        val applied = attempts.any { args ->
            runCatching {
                PrivilegedBinder.callPackageManager(
                    service, "installExistingPackageAsUser", *args,
                )
            }.onFailure { lastFailure = it }.isSuccess
        }
        if (!applied) {
            throw IllegalStateException(
                "installExistingPackageAsUser is unavailable in every known form",
                lastFailure,
            )
        }

        if (!isInstalledForUser(packageName, userId)) {
            error("Restore of $packageName returned without error and it is still missing")
        }
    }

    /**
     * Whether the package is installed for this user right now.
     *
     * Deliberately the *visible* list rather than a flags query: that is the
     * same question the screen asks, so the confirmation and the display
     * cannot disagree.
     */
    fun isInstalledForUser(packageName: String, userId: Int = 0): Boolean =
        PrivilegedPackages.list(includeUninstalled = false, userId = userId)
            .contains(packageName)

    /**
     * Packages removed for this user whose APK is still on the system image.
     *
     * The restore surface, and the reason uninstall is reversible at all. It
     * is derived from the device rather than from the log - a log lost with
     * the app would otherwise take the restore list with it, which is exactly
     * what happened on 2026-09-12.
     */
    fun restorable(userId: Int = 0): Set<String> {
        val all = PrivilegedPackages.list(includeUninstalled = true, userId = userId).toSet()
        val visible = PrivilegedPackages.list(includeUninstalled = false, userId = userId).toSet()
        return all - visible
    }

    private fun packageInstaller(): Any {
        val service = PrivilegedBinder.packageManager()
        val raw = PrivilegedBinder.callPackageManager(service, "getPackageInstaller")
            ?: error("IPackageManager.getPackageInstaller returned null")
        // Unwrap to the binder and wrap it again - see the note at the head.
        val binder = PrivilegedBinder.invokeHidden(raw.javaClass, raw, "asBinder")
            ?: error("IPackageInstaller proxy has no binder")
        return PrivilegedBinder.invokeHidden(
            Class.forName(INSTALLER_STUB),
            null,
            "asInterface",
            ShizukuBinderWrapper(binder as android.os.IBinder),
        ) ?: error("IPackageInstaller.Stub.asInterface returned null")
    }

    /**
     * A result channel the platform will accept and we will ignore.
     *
     * `FLAG_MUTABLE` because the platform fills the result in. Required from
     * API 31; harmless before it.
     */
    private fun resultSender(context: Context): android.content.IntentSender {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(RESULT_ACTION).setPackage(context.packageName),
            flags,
        ).intentSender
    }
}
