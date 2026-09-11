package com.bulwark.app.permissions

/**
 * Detecting malware that works the way Bulwark works.
 *
 * Android RATs have stopped waiting for a user to grant anything. They
 * **replicate Shizuku's architecture**: a rogue Accessibility service switches
 * on Developer Options and Wireless Debugging by itself, an embedded ADB client
 * pairs with the phone's own `adbd` over `127.0.0.1`, and a uid-2000 helper is
 * then driven over Binder exactly as a legitimate Shizuku app would.
 *
 * Nobody ships detection for this, and most could not. It takes understanding
 * this precise privilege architecture - which is the thing Bulwark is built on.
 * **Our own design is what makes the attack legible to us.**
 *
 * ## The honesty problem, and it is not small
 *
 * Bulwark's own onboarding turns on Wireless Debugging. So the strongest signal
 * here is *also* the footprint of setting up Bulwark, and an app that cried
 * "malware!" at its own instructions would be both wrong and untrustworthy.
 *
 * Every finding below therefore names the innocent explanation **first**, and
 * says what would distinguish it. A user who enabled wireless debugging an hour
 * ago should close this feeling informed, not frightened. A user who did not
 * should close it knowing exactly what to look at.
 *
 * `security.md` puts the obligation the other way round too: our documentation
 * has to explain why each onboarding step is dangerous, so that the same
 * sequence performed by something else reads as alarming rather than routine.
 */
enum class DeviceSignal {
    /**
     * Wireless debugging is on. The channel a Shizuku-architecture RAT uses -
     * and the one Bulwark's own setup uses.
     */
    WIRELESS_DEBUGGING_ON,

    /** Developer options are on. A prerequisite for the above, not a risk itself. */
    DEVELOPER_OPTIONS_ON,
}

/**
 * Something worth a person's attention, with the ordinary explanation stated.
 *
 * Never an accusation. [innocentFirst] exists because the most likely reason
 * for every signal here is that the user did it themselves an hour ago, and a
 * finding that omits that is fearmongering.
 */
data class RatFinding(
    val headline: String,
    val innocentFirst: String,
    val whatWouldWorry: String,
)

/**
 * The RAT-shaped readings of an otherwise ordinary audit.
 *
 * Takes the same enumeration `SpecialAccessSection` already shows and reads it
 * more sharply, which is why A2b folds into A2 rather than being built beside
 * it.
 *
 * @param signals device-level facts.
 * @param apps the audit, as already gathered.
 * @param shizukuRunning whether Bulwark's own privilege source is up. When it
 *   is, wireless debugging has an obvious and correct explanation and saying so
 *   is part of being honest rather than alarming.
 */
fun ratFindings(
    signals: Set<DeviceSignal>,
    apps: List<AppAccess>,
    shizukuRunning: Boolean,
): List<RatFinding> {
    val findings = mutableListOf<RatFinding>()
    val debugging = DeviceSignal.WIRELESS_DEBUGGING_ON in signals
    val screenControllers = apps.filter { Access.ACCESSIBILITY in it.accesses }

    if (debugging && screenControllers.isNotEmpty()) {
        // The full shape: something that can drive the screen, plus the channel
        // it would drive it through.
        val names = screenControllers.joinToString(", ") { it.packageName }
        findings += RatFinding(
            headline = "Wireless debugging is on, and an app can control your screen.",
            innocentFirst = if (shizukuRunning) {
                "Most likely: you turned on wireless debugging for Shizuku, and " +
                    "the app below is a screen reader or automation tool you chose. " +
                    "Both are normal."
            } else {
                "Most likely: you turned wireless debugging on yourself, and the " +
                    "app below is one you chose to give screen access to."
            },
            whatWouldWorry = "Together these are the shape of a known attack: an " +
                "app that can control the screen switches on wireless debugging " +
                "itself, then pairs with the phone and gains the same access " +
                "Bulwark has. If you did not turn wireless debugging on, or you " +
                "do not recognise $names, that is worth investigating.",
        )
    } else if (debugging) {
        findings += RatFinding(
            headline = "Wireless debugging is on.",
            innocentFirst = if (shizukuRunning) {
                "Expected — Shizuku needs it, and that is how Bulwark is working " +
                    "right now."
            } else {
                "You probably turned this on yourself, for Shizuku or for a " +
                    "computer."
            },
            whatWouldWorry = "It is worth switching off when you are not using it. " +
                "Anything that can reach it can pair with your phone and hold the " +
                "same access Bulwark does. Nothing on this phone currently has " +
                "screen control, which is the other half an attack would need.",
        )
    }

    return findings
}
