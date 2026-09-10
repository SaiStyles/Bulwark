package com.bulwark.app.shizuku

/**
 * Two tiers: what Bulwark refuses outright, and what it warns about.
 *
 * ## The line, narrowed 2026-09-10
 *
 * The hard floor is **not** "this is dangerous". Two things put a package on it,
 * and nothing else does.
 *
 * **1. It breaks the thing you would use to undo it.**
 *
 * - Telephony. A phone that cannot dial emergency services is not a risk you
 *   get to accept on someone else's behalf, and you cannot fix it by calling
 *   for help.
 * - SystemUI, launcher, Settings, the package installer, the permission
 *   controller. Turn these off and there is no screen left to turn them back on
 *   with.
 * - Bootloop-class framework modules. The undo is a factory reset.
 *
 * **2. It is a life-safety system.** Emergency calling, and cell broadcast -
 * the channel that carries evacuation orders and earthquake warnings. Android
 * already lets people switch off alert *categories* in Settings, reversibly and
 * discoverably. Deleting the receiver is not that, and the downside is missing
 * an evacuation order. That is not a trade we offer on someone's behalf.
 *
 * Everything else is the user's call. This file used to refuse messaging,
 * Wi-Fi and security components too - that was paternalism dressed as safety.
 * They are reversible, so [cautionFor] warns and the user decides.
 *
 * **Bulwark exists because Android decides for people. Copying that habit
 * while claiming to fight it would be the worst possible outcome.**
 *
 * ## Where it is enforced
 *
 * Below the UI and below the policy engine, on the uid-2000 side of the binder
 * (`CommandSafety.requireMutable`). A buggy or compromised layer above cannot
 * route around a check that does not live up there.
 *
 * ## Adding to the hard floor
 *
 * Free, if it genuinely breaks the recovery path. Removing from it needs
 * evidence from real hardware and a note in the device card.
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
        // Telephony and calling. Breaking IMS can break emergency calls, and
        // there is no undo you can reach while unable to phone anyone.
        "telephony", "telecom", "ims", "carrier", "dialer", "phone",
        "emergency", "sim", "euicc", "radio", "modem", "volte", "rcs",

        // The recovery path itself. Disable SystemUI or the launcher and you
        // cannot reach Settings to put it back.
        "systemui", "launcher", "settings", "packageinstaller",
        "permissioncontroller", "provision", "setupwizard", "keyguard",

        // Core framework surfaces.
        "com.android.systemui", "android.system", "com.android.providers",

        // Added 2026-09-10 after cross-referencing the Universal Debloater
        // Alliance database against the test device. Our category list caught
        // telephony perfectly and knew nothing about these - every one is
        // marked Unsafe upstream, several with "bootloop" in the description.
        //
        // These are structural categories, not a list of specific packages.
        // Per-package knowledge belongs in the bundled UAD data, not here;
        // this file is only for classes of thing that are dangerous by shape.
        "networkstack",      // network stack module
        "modulemetadata",    // "extremely large chance of bootlooping"
        "ext.shared",        // Android shared library
        "ext.services",      // default text classifier and friends
        "devicelock",        // device lock controller
        "frameworkres",      // framework resource overlays
        "wifi.resources",    // Wi-Fi module resources
        "connectivity.resources",
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
        // Emergency broadcast alerts - evacuation orders, earthquake warnings.
        // Life safety, not convenience. Alert categories remain switchable in
        // Settings; that is the reversible control, and this is not it.
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.ext.services",
        // The bare MediaTek core package - "core system services and drivers
        // for MediaTek-powered devices". Exact match on purpose: "mediatek" as
        // a fragment would protect every MediaTek package, including the many
        // that are safely removable, and an over-broad rule that defeats the
        // feature is its own kind of failure.
        "com.mediatek",
        // Shizuku itself. Removing our own privilege source mid-operation
        // would strand the user with no way to undo what we just did.
        "moe.shizuku.privileged.api",
        // Bulwark. An app that can uninstall itself can destroy its own
        // undo log, which is the thing that makes every action reversible.
        "com.bulwark.app",
    )

    /**
     * Serious consequences, but recoverable - so **offered with a warning
     * rather than refused**.
     *
     * Losing your messaging app is bad. It is not the same kind of bad as a
     * phone that cannot dial emergency services or reach its own Settings, and
     * treating them identically was paternalism dressed as safety. The user
     * owns the device; our job is to make sure they know what they are
     * choosing, not to choose for them.
     */
    private val CAUTION_FRAGMENTS = listOf(
        // Carries 2FA codes. The emergency-broadcast receivers themselves are
        // on the hard floor above; ordinary messaging is not.
        "sms", "mms", "messaging",
        // Security and identity components.
        "keychain", "certinstaller", "credential", "biometric", "fingerprint",
        // Connectivity. Losing Wi-Fi on a phone with no data is isolating.
        "wifi", "bluetooth", "nfc",
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
     * A warning to show *before acting*, or null if there is nothing to say.
     *
     * Never a refusal. If this returns text, the action still happens when the
     * user chooses it - they simply get told what they are trading first.
     */
    fun cautionFor(packageName: String): String? {
        if (isProtected(packageName)) return null // Already refused outright.
        val name = packageName.lowercase()
        return when {
            listOf("sms", "mms", "messaging", "cellbroadcast").any { name.contains(it) } ->
                "This is part of the messaging system. Turning it off can stop " +
                    "two-factor codes and emergency alerts from arriving."

            listOf("keychain", "certinstaller", "credential", "biometric", "fingerprint")
                .any { name.contains(it) } ->
                "This is a security component. Turning it off may weaken the " +
                    "phone rather than protect it."

            listOf("wifi", "bluetooth", "nfc").any { name.contains(it) } ->
                "This handles a radio your phone uses to connect. Turning it " +
                    "off may leave you without Wi-Fi, Bluetooth or contactless."

            CAUTION_FRAGMENTS.any { name.contains(it) } ->
                "This is a sensitive system component. It is reversible, but " +
                    "check you know what it does first."

            else -> null
        }
    }

    /**
     * Why an outright refusal happened. A user told "no" deserves to be told
     * why, or they will go looking for a tool that just says yes.
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

            listOf("systemui", "launcher", "settings", "keyguard", "setupwizard",
                "provision", "packageinstaller").any { name.contains(it) } ->
                "Required to operate the phone. Removing it can leave the " +
                    "device unusable with no way back except a factory reset."

            name.contains("permissioncontroller") ->
                "Controls app permissions. Removing it takes away the screen " +
                    "you would use to undo anything else."

            else ->
                "On Bulwark's permanent never-remove list. If you believe this " +
                    "is wrong, it needs verifying on a test device first."
        }
    }
}
