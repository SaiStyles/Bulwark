package com.bulwark.app.shizuku

/**
 * What Bulwark refuses, and what it warns about.
 *
 * ## The blocklist is gone — 2026-09-12
 *
 * This file used to hold two lists of name fragments and exact names, and
 * refused 72 of the test device's 274 system packages. It was retired by SAI's
 * decision, on two measurements:
 *
 * - **It worked by luck of naming.** It caught `com.mediatek.ims` because that
 *   vendor used the word "ims". A vendor using a codename would have been
 *   protected by nothing, so the guarantee was real on one phone and theatre
 *   on every other - which the honesty rules forbid more clearly than they
 *   ever demanded a blocklist.
 * - **It mislabelled.** `com.google.android.ims` reports as user-installed
 *   here, so the fragments skipped it; extending them to catch it would have
 *   named Carrier Services as critical telephony when the device says the IMS
 *   provider is MediaTek's. It was guessing while the answer was available.
 *
 * What names the critical packages now is [CriticalRoles], which asks the
 * phone: `ROLE`-equivalent holders for the dialer and messages, the
 * `CATEGORY_HOME` resolver, the framework's own SystemUI component, and the
 * services registered for `android.telephony.ims.ImsService`. In front of
 * those stands a ceremony rather than a wall - `policy/PackageStanding.kt`.
 *
 * ## What is still refused
 *
 * **Two things, and on technical grounds rather than moral ones.** Bulwark
 * cannot remove Bulwark: the process is killed part-way through the action and
 * it takes the log that reverses it. And it cannot disable Shizuku, because
 * re-enabling needs the privilege that disabling just removed - proven on
 * 2026-09-12, when it took a PC and `adb` to undo.
 *
 * The test is not "is this dangerous" but **"can Bulwark undo it"**. Everything
 * else on the phone belongs to its owner.
 *
 * ## Where it is enforced
 *
 * Below the UI and below the policy engine, on the uid-2000 side of the binder
 * (`CommandSafety.requireMutable`). A buggy or compromised layer above cannot
 * route around a check that does not live up there. That has not changed; only
 * the size of what it refuses has.
 */
object ProtectedPackages {

    /**
     * Bulwark's own package.
     *
     * Exact, like the other entry. An exact name cannot collide, which is the
     * whole reason the fragment matching that used to live here had to go.
     */
    private const val SELF = "com.bulwark.app"

    /**
     * Shizuku, which is where every privileged call comes from.
     *
     * **Added 2026-09-12, after it happened.** It was deliberately *not*
     * refused when the blocklist was cut: disabling it looked recoverable -
     * reinstall, pair again - and `safety-rules.md` had just given up refusing
     * recoverable things. Then SAI disabled it through Bulwark, and Bulwark
     * could not put it back: re-enabling needs the privilege that was just
     * switched off. Both attempts are in the log as ENABLE / FAILED.
     *
     * It took a PC and `adb` to recover, which is the one thing the tin says
     * you do not need. The action is not reversible **by Bulwark**, and that
     * is the test - not whether some other tool could fix it.
     */
    private const val PRIVILEGE_SOURCE = "moe.shizuku.privileged.api"

    /**
     * Substrings that mark a package as worth a warning.
     *
     * **Warnings, never refusals.** Losing your messaging app is bad. It is
     * not the same kind of bad as a phone that cannot dial, and treating them
     * identically was paternalism dressed as safety. The user owns the device;
     * our job is to make sure they know what they are choosing.
     */
    private val CAUTION_FRAGMENTS = listOf(
        // Carries 2FA codes.
        "sms", "mms", "messaging",
        // Security and identity components.
        "keychain", "certinstaller", "credential", "biometric", "fingerprint",
        // Connectivity. Losing Wi-Fi on a phone with no data is isolating.
        "wifi", "bluetooth", "nfc",
    )

    /**
     * Whether Bulwark refuses to touch this at all.
     *
     * [isSystem] is kept in the signature though it no longer changes the
     * answer: every caller passes it, and the reading it comes from is a
     * privileged call worth keeping at the call sites for when this needs it
     * again. Lowercased and trimmed, because an OEM using mixed case must not
     * slip through - the one piece of the old matching worth keeping.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isProtected(packageName: String, isSystem: Boolean): Boolean {
        val name = packageName.lowercase().trim()
        if (name.isEmpty()) return true // Cannot reason about it, so refuse.
        return name == SELF || name == PRIVILEGE_SOURCE
    }

    /**
     * A warning to show *before acting*, or null if there is nothing to say.
     *
     * Never a refusal. If this returns text, the action still happens when the
     * user chooses it - they simply get told what they are trading first.
     */
    fun cautionFor(packageName: String, isSystem: Boolean): String? {
        if (isProtected(packageName, isSystem)) return null // Already refused.
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
     * Why a refusal happened.
     *
     * A user told "no" deserves to be told why, or they will go looking for a
     * tool that just says yes.
     */
    fun reasonFor(packageName: String, isSystem: Boolean): String? {
        if (!isProtected(packageName, isSystem)) return null
        return if (packageName.lowercase().trim() == PRIVILEGE_SOURCE) {
            "This is Shizuku, which is where Bulwark's permission to change " +
                "anything comes from. Switching it off would take away the " +
                "ability to switch it back on - it needed a computer to undo " +
                "the one time it happened."
        } else {
            "This is Bulwark. It cannot remove itself - the action would be " +
                "killed part-way through, and it would destroy the record that " +
                "undoes everything else."
        }
    }
}
