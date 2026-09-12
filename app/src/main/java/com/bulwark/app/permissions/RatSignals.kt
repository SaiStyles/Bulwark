package com.bulwark.app.permissions

/**
 * Detecting malware that works the way Bulwark works.
 *
 * There is a **demonstrated** self-provisioning chain on non-rooted Android: a
 * rogue Accessibility service switches on Developer Options and Wireless
 * Debugging by itself, reads the pairing code out of the UI tree, hides the
 * whole workflow behind an overlay, pairs with the phone's own `adbd` over
 * `127.0.0.1`, and then drives a uid-2000 helper over Binder exactly as a
 * legitimate Shizuku app would.
 *
 * **How often that happens in the wild is not something this project knows.**
 * `supply-chain.md` describes the chain and cites nobody for its prevalence, so
 * nothing here is built on a frequency claim. What is certain, and enough on
 * its own, is the exposure: Bulwark asks people to switch wireless debugging
 * on, most leave it on because Shizuku needs it to restart after a reboot, and
 * a standing open channel is worth being told about whether or not anyone is
 * currently walking through it.
 *
 * So read this as **exposure disclosure first, detection second**. That is the
 * order the findings are written in, and the order they survive scrutiny in.
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
 *
 * ## Why the loud finding needs three things, not two
 *
 * Until 2026-09-12 the alarming finding fired on wireless debugging **plus any
 * app holding Accessibility**. For Bulwark's own users that is close to
 * permanent: debugging is on because we asked for it, and Accessibility is held
 * by password managers, screen readers, clipboard tools and automation apps.
 *
 * A warning that is always on screen is one people stop reading - which is
 * exactly the reasoning already written into `developer options alone is not a
 * finding`, applied there and not applied one level up. The third element,
 * **an app that can also draw over other apps**, is what makes the combination
 * rare, and it is the piece that makes the attack *hideable*: without an
 * overlay the pairing dialogue happens in front of you.
 *
 * So overlay is not extra coverage. It is the gate that keeps the loud finding
 * worth reading.
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
 * it. Naming which apps hold what is A2's job; this only says which
 * combinations mean something together.
 *
 * At most one finding, always. Four states, and the difference between the
 * last two is the whole point of this function:
 *
 * 1. nothing can drive the screen - the mild case, and it says so
 * 2. something can, and **we could not check overlay** - says that, and does
 *    not imply safety
 * 3. something can, nothing can hide it - ordinary, stated calmly
 * 4. one app can do both - the full shape, and the only loud one
 *
 * @param signals device-level facts.
 * @param apps the audit, as already gathered.
 * @param shizukuRunning whether Bulwark's own privilege source is up. When it
 *   is, wireless debugging has an obvious and correct explanation and saying so
 *   is part of being honest rather than alarming.
 * @param overlayKnown whether "can draw over other apps" could be read at all.
 *   It comes from app-ops and therefore needs Shizuku, so with Shizuku down it
 *   is simply unknown. **Unknown must never be reported as absent**: a gate
 *   that silently opens to "nothing to see" exactly when privilege is gone is
 *   the failure this codebase keeps finding - a claim with no source, wearing
 *   the face of a clean result.
 */
fun ratFindings(
    signals: Set<DeviceSignal>,
    apps: List<AppAccess>,
    shizukuRunning: Boolean,
    overlayKnown: Boolean,
): List<RatFinding> {
    if (DeviceSignal.WIRELESS_DEBUGGING_ON !in signals) return emptyList()

    val screenControllers = apps.filter { Access.ACCESSIBILITY in it.accesses }
    val canAlsoHide = screenControllers.filter { Access.DRAW_OVER_APPS in it.accesses }

    val names = screenControllers.joinToString(", ") { it.packageName }
    val hidingNames = canAlsoHide.joinToString(", ") { it.packageName }

    val debuggingIsOurs = if (shizukuRunning) {
        "Expected - Shizuku needs it, and that is how Bulwark is working right now."
    } else {
        "You probably turned this on yourself, for Shizuku or for a computer."
    }

    val finding = when {
        // 1. The mild case. Unchanged wording: it was already right.
        screenControllers.isEmpty() -> RatFinding(
            headline = "Wireless debugging is on.",
            innocentFirst = debuggingIsOurs,
            whatWouldWorry = "Anything that can reach it can pair with your phone " +
                "and hold the same access Bulwark does. Nothing here currently has " +
                "screen control, which is the other half an attack would need. " +
                "Switching it off is the safer choice, and it has a cost worth " +
                "knowing: Shizuku uses it to restart itself after a reboot, so you " +
                "would have to turn it back on before using Bulwark again. Your " +
                "call - leaving it on is a standing way in, turning it off is a " +
                "step to repeat.",
        )

        // 2. We cannot see the deciding piece. Say so; claim nothing.
        !overlayKnown -> RatFinding(
            headline = "Wireless debugging is on, and an app can control your screen.",
            innocentFirst = if (shizukuRunning) {
                "Most likely: you turned on wireless debugging for Shizuku, and " +
                    "the app below is a screen reader or automation tool you chose. " +
                    "Both are normal."
            } else {
                "Most likely: you turned wireless debugging on yourself, and the " +
                    "app below is one you chose to give screen access to."
            },
            whatWouldWorry = "Bulwark could not finish this check. Whether $names " +
                "can also draw over other apps needs Shizuku to read, and Shizuku " +
                "is not running - so the piece that would tell you whether any of " +
                "this could be hidden from you is missing. This is not the same as " +
                "finding nothing. Start Shizuku and open this screen again.",
        )

        // 3. Two ordinary facts that are ordinary together. Stated, not alarmed.
        canAlsoHide.isEmpty() -> RatFinding(
            headline = "Wireless debugging is on, and an app can control your screen.",
            innocentFirst = if (shizukuRunning) {
                "Most likely: you turned on wireless debugging for Shizuku, and " +
                    "$names is a screen reader, password manager or automation tool " +
                    "you chose. Both are normal, and common together."
            } else {
                "Most likely: you turned wireless debugging on yourself, and " +
                    "$names is an app you chose to give screen access to."
            },
            whatWouldWorry = "These two on their own are the ordinary case. The " +
                "attack this screen looks for needs a third piece - an app that " +
                "can also draw over other apps, to cover the pairing prompt while " +
                "it happens - and nothing here can do that. Worth a look anyway if " +
                "you did not turn wireless debugging on, or you do not recognise " +
                "$names.",
        )

        // 4. All three. The only finding allowed to be loud.
        else -> RatFinding(
            headline = "An app can control your screen and draw over it, " +
                "and wireless debugging is on.",
            innocentFirst = "Most likely: $hidingNames is a password manager, " +
                "screen reader or automation tool you chose, and overlays are how " +
                "several of those work. If you also turned on wireless debugging " +
                "yourself, every part of this has an ordinary explanation.",
            whatWouldWorry = "Together these are the full shape of a known attack: " +
                "an app that can drive the screen switches on wireless debugging " +
                "itself, covers the pairing prompt with an overlay so you never see " +
                "it, pairs with the phone, and gains the same access Bulwark has. " +
                "The overlay is what makes it invisible, which is why this reads " +
                "louder than the rest of the audit. If you do not recognise " +
                "$hidingNames, or you did not turn wireless debugging on, that is " +
                "worth investigating now.",
        )
    }

    return listOf(finding)
}
