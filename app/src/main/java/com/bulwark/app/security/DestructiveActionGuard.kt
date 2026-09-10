package com.bulwark.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Requires a human to authenticate before anything destructive runs.
 *
 * ## The hole this closes
 *
 * A malicious Accessibility service can read another app's screen and
 * synthesise taps on it. For most apps that is a fraud risk. For Bulwark it is
 * a **privilege escalation route**: malware would not need Shizuku access of
 * its own, because it could simply drive ours. Bulwark holds shell; anything
 * that can press Bulwark's buttons holds shell by proxy.
 *
 * Overlay defences in [WindowHardening] do not help here. The attacker is not
 * covering our UI, it is operating it.
 *
 * ## Why biometrics specifically
 *
 * Security research on Accessibility malware is consistent on this point:
 * authentication backed by biometric or device-credential hardware is the
 * mitigation that holds, because an Accessibility service cannot present a
 * fingerprint or type a PIN it does not know. A custom in-app confirmation
 * dialog is worthless — that is just another button to press.
 *
 * Device credential (PIN/pattern/password) is accepted alongside biometrics
 * deliberately. Requiring a fingerprint would lock out anyone whose phone has
 * no sensor or whose sensor has failed — common on exactly the budget hardware
 * this project exists for. A PIN entered on the secure keyguard is still
 * outside our process and still unreachable by Accessibility.
 *
 * ## No new dependency
 *
 * Uses the framework APIs rather than `androidx.biometric`.
 * `context/_shared/supply-chain.md` requires a written justification for every
 * dependency, and "it would have been three branches shorter" is not one when
 * the alternative is more supply-chain surface in the security-critical path.
 */
object DestructiveActionGuard {

    sealed interface Result {
        data object Authenticated : Result
        data object Failed : Result
        /** No PIN, pattern, password or biometric is configured on the device. */
        data object NoDeviceSecurity : Result
    }

    /**
     * Prompts, then calls [onResult] on the main thread.
     *
     * @param reason shown to the user. Name the actual consequence — "Remove 3
     *   packages" — never "Confirm". Someone who does not know what they are
     *   authorising is not really authorising it.
     */
    fun confirm(
        activity: Activity,
        title: String,
        reason: String,
        onResult: (Result) -> Unit,
    ) {
        if (!isDeviceSecured(activity)) {
            // Fail closed. Without a screen lock there is nothing an
            // Accessibility service cannot already do, so there is no
            // meaningful confirmation available.
            onResult(Result.NoDeviceSecurity)
            return
        }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                promptModern(activity, title, reason, onResult)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                promptLegacy(activity, title, reason, onResult)

            else ->
                // API 26-27 has no BiometricPrompt. The keyguard confirmation
                // Intent is still outside our process, which is the property
                // that matters. The caller handles the activity result.
                onResult(Result.NoDeviceSecurity)
        }
    }

    /** True when a PIN, pattern, password or biometric is set. */
    fun isDeviceSecured(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }

    /**
     * Fallback for API 26-27: an Intent the caller starts for result. The
     * keyguard runs in the system process, so Accessibility cannot drive it.
     */
    @Suppress("DEPRECATION")
    fun deviceCredentialIntent(context: Context, title: String, description: String) =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.createConfirmDeviceCredentialIntent(title, description)

    private fun promptModern(
        activity: Activity,
        title: String,
        reason: String,
        onResult: (Result) -> Unit,
    ) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setDescription(reason)
            .setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            )
            // BIOMETRIC_WEAK is excluded on purpose: face unlock that can be
            // satisfied by a photograph is not a confirmation that someone
            // meant to remove packages from their phone.
            .setConfirmationRequired(true)
            .build()

        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            callbackFor(onResult),
        )
    }

    @Suppress("DEPRECATION")
    private fun promptLegacy(
        activity: Activity,
        title: String,
        reason: String,
        onResult: (Result) -> Unit,
    ) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setDescription(reason)
            .setDeviceCredentialAllowed(true)
            .build()

        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            callbackFor(onResult),
        )
    }

    private fun callbackFor(onResult: (Result) -> Unit) =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult?,
            ) = onResult(Result.Authenticated)

            override fun onAuthenticationError(code: Int, message: CharSequence?) =
                onResult(Result.Failed)

            // Deliberately not treated as terminal: a smudged finger is not an
            // attack, and forcing a restart of the flow trains people to rush.
            override fun onAuthenticationFailed() = Unit
        }
}
