package com.bulwark.app.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Which packages hold this phone's critical jobs, asked of the phone.
 *
 * ## Why this replaced a blocklist
 *
 * Until 2026-09-12 Bulwark refused a list of packages matched by name
 * fragment. It caught `com.mediatek.ims` because that vendor used the word
 * "ims" - a vendor using a codename would have been protected by nothing, so
 * the guarantee was real on one phone and theatre everywhere else.
 *
 * It mislabelled as well. `com.google.android.ims` reports as user-installed
 * here, so the fragment list skipped it; extending the fragments to catch it
 * would have named Carrier Services as critical telephony when the device says
 * the IMS provider is MediaTek's. The list was guessing while the answer was
 * available.
 *
 * Nothing here refuses anything. `safety-rules.md` now states the labels and
 * the ceremony; this is only the reading behind them.
 *
 * ## Fail closed
 *
 * Every job is read independently and every read is caught, because one
 * unavailable API must not blank the rest. A job that cannot be read lands in
 * [Reading.unreadable] and the UI must say it could not check - **never that
 * the package is ordinary.** "We could not tell" and "it is not critical" are
 * different facts and only one of them is safe to act on.
 */
object CriticalRoles {

    /** A job this phone needs someone to do. */
    enum class Job {
        /** The home screen. Remove it and there is nothing to return to. */
        HOME,

        /** The dialer. */
        DIALER,

        /** The default messaging app. */
        SMS,

        /** The status bar, notification shade and navigation. */
        SYSTEM_UI,

        /** Settings. */
        SETTINGS,

        /** Whatever installs APKs, and therefore whatever puts things back. */
        PACKAGE_INSTALLER,

        /** Carries calls over LTE/Wi-Fi on this phone. */
        IMS,
    }

    /**
     * @property holders package name to the jobs it holds here.
     * @property unreadable jobs whose holder could not be determined. While
     *   this is non-empty no package can be *cleared* as ordinary, because the
     *   holder we failed to read might be the package being looked at.
     */
    data class Reading(
        val holders: Map<String, Set<Job>>,
        val unreadable: Set<Job>,
    ) {
        fun jobsFor(packageName: String): Set<Job> = holders[packageName].orEmpty()

        /** True when every job was read. */
        val complete: Boolean get() = unreadable.isEmpty()
    }

    fun read(context: Context): Reading {
        val found = mutableMapOf<String, MutableSet<Job>>()
        val unreadable = mutableSetOf<Job>()

        fun record(job: Job, names: List<String?>) {
            val real = names.filterNotNull().filter { it.isNotBlank() }
            if (real.isEmpty()) {
                unreadable += job
                return
            }
            real.forEach { found.getOrPut(it) { mutableSetOf() } += job }
        }

        fun attempt(job: Job, read: () -> List<String?>) {
            val names = runCatching(read).getOrNull()
            if (names == null) unreadable += job else record(job, names)
        }

        val pm = context.packageManager

        attempt(Job.HOME) {
            // The resolver answers on every API level, unlike RoleManager.
            listOf(
                pm.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo?.packageName,
            )
        }

        attempt(Job.DIALER) { roleHolders(ROLE_DIALER) ?: dialers(context) }

        attempt(Job.SMS) {
            roleHolders(ROLE_SMS) ?: listOf(Telephony.Sms.getDefaultSmsPackage(context))
        }

        attempt(Job.SETTINGS) {
            listOf(
                pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                    ?.activityInfo?.packageName,
            )
        }

        attempt(Job.PACKAGE_INSTALLER) {
            // Whatever opens an APK is what can put an uninstalled one back by
            // hand, so it belongs in the same class as the launcher.
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    android.net.Uri.parse("file:///x.apk"),
                    "application/vnd.android.package-archive",
                )
            pm.queryIntentActivities(view, 0).map { it.activityInfo?.packageName }
        }

        attempt(Job.SYSTEM_UI) {
            // The framework names its own SystemUI component, so this is read
            // rather than assumed to be com.android.systemui - OEMs do replace
            // it. Format is "pkg/cls".
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_systemUIServiceComponent", "string", "android")
            val component = if (id != 0) res.getString(id) else null
            listOf(component?.substringBefore('/')?.takeIf { it.isNotBlank() })
        }

        attempt(Job.IMS) {
            // The services the platform binds for IMS. Guarded by
            // BIND_IMS_SERVICE, so this cannot be squatted by an ordinary app -
            // which is what makes it trustworthy enough to label with.
            pm.queryIntentServices(Intent("android.telephony.ims.ImsService"), 0)
                .map { it.serviceInfo?.packageName }
        }

        return Reading(found.mapValues { it.value.toSet() }, unreadable)
    }

    private const val ROLE_DIALER = "android.app.role.DIALER"
    private const val ROLE_SMS = "android.app.role.SMS"

    /**
     * Role holders, asked of the platform the way `cmd role` asks.
     *
     * `RoleManager.getRoleHolders` is `@SystemApi` and needs
     * `MANAGE_ROLE_HOLDERS`, which we do not hold in-process - so the first
     * version used the public fallbacks instead. On the Agni 2 that was wrong
     * for SMS: `sms_default_application` is **null** there, so
     * `getDefaultSmsPackage` returns nothing and the SMS holder could not be
     * named at all. `cmd role` had the answer the whole time - `com.jio.myjio`.
     *
     * Through Shizuku the call is made as uid 2000, which does hold the
     * permission. Null when that is unavailable, and the caller falls back to
     * the public route - so this is better with Shizuku and no worse without.
     */
    @Suppress("UNCHECKED_CAST")
    private fun roleHolders(role: String): List<String>? = runCatching {
        val binder = SystemServiceHelper.getSystemService("role") ?: return null
        val service = PrivilegedBinder.invokeHidden(
            Class.forName("android.app.role.IRoleManager\$Stub"),
            null,
            "asInterface",
            ShizukuBinderWrapper(binder),
        ) ?: return null
        val holders = PrivilegedBinder.invokeHidden(
            Class.forName("android.app.role.IRoleManager"),
            service,
            "getRoleHoldersAsUser",
            role,
            0,
        ) as? List<String>
        holders?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * The dialer, or dialers.
     *
     * `RoleManager.getRoleHolders` is the obvious call and is **@SystemApi** -
     * it needs `MANAGE_ROLE_HOLDERS`, which we do not hold in-process. This is
     * the public route and returns the same answer this phone gave `cmd role`.
     *
     * Both are taken: the *default* dialer is what the user chose, the
     * *system* dialer is the fallback the platform uses for emergency calls,
     * and they are frequently different packages.
     */
    private fun dialers(context: Context): List<String?> {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        return listOf(telecom.defaultDialerPackage, telecom.systemDialerPackage())
    }

    private fun TelecomManager.systemDialerPackage(): String? = runCatching {
        // @SystemApi, present since API 21 and stable. Reflection rather than
        // a compile-time reference so the absence of it is a null, not a
        // link error on an OEM that stripped it.
        javaClass.getMethod("getSystemDialerPackage").invoke(this) as? String
    }.getOrNull()
}
