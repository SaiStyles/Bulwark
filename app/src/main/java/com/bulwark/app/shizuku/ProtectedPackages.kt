package com.bulwark.app.shizuku

/**
 * The packages Bulwark must never disable or remove, enforced in code.
 *
 * `context/_shared/safety-rules.md` names these as permanently off-limits
 * "regardless of what any allowlist says", and `context/layers/01-debloat.md`
 * promises they are "excluded at the wrapper level, not just in the UI, so a
 * bad allowlist entry still cannot remove them".
 *
 * This file is that promise. It is checked inside [PrivilegedService], on the
 * uid-2000 side of the binder — **below** the UI, below the policy engine, and
 * below any caller. A compromised or simply buggy layer above cannot route
 * around it, because the check does not live up there.
 *
 * ## Why it fails closed
 *
 * Matching is deliberately over-broad. A false positive means a user keeps a
 * package they wanted gone, and complains. A false negative means someone's
 * only phone cannot dial emergency services. Those costs are not comparable,
 * so every ambiguous case resolves to "protected".
 *
 * ## Adding to this list
 *
 * Free. Removing from it is not: it needs evidence from a real device and a
 * note in the device card. Never narrow a rule here to make a feature work.
 */
object ProtectedPackages {

    /**
     * Substrings that mark a package as untouchable wherever they appear.
     *
     * Substring matching rather than exact names because OEMs rename things.
     * On the test device (Lava, MediaTek) the telephony stack does not use
     * stock AOSP package names, and an exact-match list would silently fail
     * to protect exactly the device the project was built around.
     */
    private val PROTECTED_FRAGMENTS = listOf(
        // Telephony and calling. Breaking IMS can break emergency calls.
        "telephony", "telecom", "ims", "carrier", "dialer", "phone",
        "emergency", "sim", "euicc", "radio", "modem", "volte", "rcs",

        // Messaging. Carries 2FA codes and emergency alerts.
        "sms", "mms", "messaging", "cellbroadcast",

        // Things that make the device usable or recoverable at all.
        "systemui", "launcher", "settings", "packageinstaller",
        "permissioncontroller", "provision", "setupwizard", "keyguard",

        // Security and identity components.
        "keychain", "certinstaller", "credential", "biometric", "fingerprint",

        // Core framework surfaces.
        "com.android.systemui", "android.system", "com.android.providers",
    )

    /**
     * Exact package names that must never be touched even though their names
     * contain no protected fragment.
     */
    private val PROTECTED_EXACT = setOf(
        "android",
        "com.android.shell",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.emergency",
        "com.android.cellbroadcastreceiver",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.ext.services",
        // Shizuku itself. Removing our own privilege source mid-operation
        // would strand the user with no way to undo what we just did.
        "moe.shizuku.privileged.api",
        // Bulwark. An app that can uninstall itself can destroy its own
        // undo log, which is the thing that makes every action reversible.
        "com.bulwark.app",
    )

    /**
     * True when [packageName] must never be disabled or uninstalled.
     *
     * Case-insensitive, because package names are compared as text here and
     * an OEM using mixed case must not slip through.
     */
    fun isProtected(packageName: String): Boolean {
        val name = packageName.lowercase().trim()
        if (name.isEmpty()) return true // Cannot reason about it, so refuse.
        if (name in PROTECTED_EXACT) return true
        return PROTECTED_FRAGMENTS.any { name.contains(it) }
    }

    /**
     * Human-readable reason, for the UI and for the action log. A user told
     * "no" deserves to be told why, or they will look for a tool that just
     * says yes.
     */
    fun reasonFor(packageName: String): String? {
        if (!isProtected(packageName)) return null
        val name = packageName.lowercase()
        return when {
            listOf("telephony", "telecom", "ims", "carrier", "dialer", "phone",
                "emergency", "sim", "euicc", "radio", "modem", "volte", "rcs")
                .any { name.contains(it) } ->
                "Part of the calling system. Removing it can stop the phone " +
                    "making calls, including emergency calls."

            listOf("sms", "mms", "messaging", "cellbroadcast").any { name.contains(it) } ->
                "Part of the messaging system. Removing it can stop two-factor " +
                    "codes and emergency alerts arriving."

            listOf("systemui", "launcher", "settings", "keyguard", "setupwizard",
                "provision", "packageinstaller").any { name.contains(it) } ->
                "Required to operate the phone. Removing it can leave the " +
                    "device unusable with no way back except a factory reset."

            listOf("permissioncontroller", "keychain", "certinstaller",
                "credential", "biometric", "fingerprint").any { name.contains(it) } ->
                "A security component. Removing it would weaken the phone " +
                    "while appearing to harden it."

            else ->
                "On Bulwark's permanent never-remove list. If you believe this " +
                    "is wrong, it needs verifying on a test device first."
        }
    }
}
