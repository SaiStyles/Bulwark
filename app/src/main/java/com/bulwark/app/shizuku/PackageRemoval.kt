package com.bulwark.app.shizuku

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import androidx.core.content.ContextCompat
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * ## The result channel is listened to *and* the state is read back
 *
 * The platform reports asynchronously through an `IntentSender`, and the first
 * version of this threw that away as "required by the signature and otherwise
 * ignored". That was the bug. The call returned cleanly, nothing was removed,
 * and the only symptom was our own read-back failing - twice, on two different
 * packages, with no way to tell why.
 *
 * The reason was in the channel the whole time. So both now happen: wait for
 * the platform to say what it did, then confirm it against the device. Rule 6
 * still stands - a callback claiming success is not evidence - but a callback
 * explaining a *failure* is the only place that explanation exists.
 */
internal object PackageRemoval {

    private const val INSTALLER_INTERFACE = "android.content.pm.IPackageInstaller"
    private const val INSTALLER_STUB = "android.content.pm.IPackageInstaller\$Stub"

    /** Our result channel. Named so it is obvious in a bug report. */
    private const val RESULT_ACTION = "com.bulwark.app.UNINSTALL_RESULT"

    /**
     * The package the call is *attributed* to, which is not us.
     *
     * The binder transaction arrives as uid 2000 because Shizuku forwards it,
     * so the caller name has to match that uid. Passing our own package made
     * the platform check **Bulwark's** permissions, and Bulwark does not hold
     * `DELETE_PACKAGES` - so it answered `STATUS_PENDING_USER_ACTION` on the
     * result channel, threw nothing, and removed nothing. On 2026-09-12 that
     * looked exactly like a silent no-op, twice, until the channel was read.
     */
    private const val SHELL_PACKAGE = "com.android.shell"

    /** How long to wait for the platform to report. Generous; it is one call. */
    private const val RESULT_TIMEOUT_MS = 10_000L

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
        @Suppress("UNUSED_PARAMETER") callingPackage: String,
    ) {
        val installer = packageInstaller()
        val versioned = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)
        val channel = ResultChannel(context)

        try {
            PrivilegedBinder.invokeHidden(
                Class.forName(INSTALLER_INTERFACE),
                installer,
                "uninstall",
                versioned,
                // Not our package. See SHELL_PACKAGE: attributing this to
                // Bulwark makes the platform check Bulwark's permissions.
                SHELL_PACKAGE,
                0, // flags: remove for this user, nothing exotic
                channel.sender,
                userId,
            )

            val outcome = channel.await(RESULT_TIMEOUT_MS)
            if (outcome != null && outcome.status != PackageInstaller.STATUS_SUCCESS) {
                error(
                    "The system refused to remove $packageName: " +
                        "${outcome.describe()}. Nothing was changed.",
                )
            }
        } finally {
            channel.close()
        }

        // Then confirm against the device anyway. A report of success is a
        // claim; this is the evidence.
        if (isInstalledForUser(packageName, userId)) {
            error(
                "Uninstall of $packageName reported no failure and the package " +
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

    /** What the platform said about one uninstall. */
    private data class Outcome(val status: Int, val message: String?) {
        /**
         * In the user's words where the status has a known meaning, and
         * verbatim otherwise - an unrecognised code is still better handed
         * over than swallowed.
         */
        fun describe(): String = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                "it asked for confirmation Bulwark cannot give, which usually " +
                    "means the request was not attributed to a caller allowed " +
                    "to remove packages"
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "the system blocked it"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "it conflicts with the package already installed"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "the package is not compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "the request was rejected as invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "there was a storage problem"
            else -> message?.takeIf { it.isNotBlank() } ?: "status $status"
        }
    }

    /**
     * The platform's answer, waited for rather than discarded.
     *
     * Registered before the call and closed in a `finally`, so a throw cannot
     * leave a receiver behind. [await] blocks, so this must not be used from
     * the main thread - which is true of every call in this file.
     */
    private class ResultChannel(private val context: Context) {
        private val latch = CountDownLatch(1)
        @Volatile private var outcome: Outcome? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                outcome = Outcome(
                    intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    ),
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
                latch.countDown()
            }
        }

        init {
            // Ours alone. Exporting it would let any app forge the answer to
            // "did that removal work". Through ContextCompat rather than a
            // version branch: lint caught the pre-33 arm of that branch
            // missing the flag, which is the fourth real bug it has found
            // here that reading missed.
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(RESULT_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        val sender: android.content.IntentSender
            get() {
                // FLAG_MUTABLE because the platform fills the result in.
                // Required from API 31; harmless before it.
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

        /** Null when nothing arrived in time, which is not the same as success. */
        fun await(millis: Long): Outcome? =
            if (latch.await(millis, TimeUnit.MILLISECONDS)) outcome else null

        fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
