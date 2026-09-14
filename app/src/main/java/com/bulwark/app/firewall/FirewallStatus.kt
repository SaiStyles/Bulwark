package com.bulwark.app.firewall

/**
 * What Bulwark may honestly say about the firewall right now.
 *
 * Pure, and separate from everything that touches Android, because this is the
 * sentence most likely to be wrong in the direction that hurts. A firewall rule
 * changes no system state: it is enforced while a tunnel runs and forgotten the
 * moment one does not. So "you asked for this" and "this is happening" are
 * different facts, and a screen that shows the first while implying the second
 * is selling the false sense of protection `safety-rules.md` calls worse than
 * none.
 *
 * Every state below is derived from a fresh reading of the platform, never from
 * what Bulwark last asked for.
 */
/**
 * What Bulwark knows about Android's always-on VPN setting.
 *
 * Three values because [CANNOT_TELL] is a real, common answer and not a
 * synonym for [OFF]. On Android 12 and later `always_on_vpn_app` is `@hide`
 * and unreadable by ordinary apps, so it is the *only* answer we get there -
 * measured on hardware 2026-09-12, not inferred.
 */
enum class AlwaysOn { ON, OFF, CANNOT_TELL }

enum class FirewallState {
    /** No rules. Nothing to enforce and nothing to claim. */
    NOTHING_BLOCKED,

    /** Rules exist, but the user has not let Bulwark run a tunnel yet. */
    NEEDS_CONSENT,

    /**
     * Rules exist and **nothing is enforcing them**.
     *
     * The state this whole design is arranged around. It happens after every
     * reboot, and whenever the service is killed or consent is withdrawn.
     */
    NOT_IN_FORCE,

    /** Rules exist and a tunnel is up. */
    IN_FORCE,
    ;

    val isEnforcing: Boolean get() = this == IN_FORCE
}

/**
 * Works out the state from three facts, none of which Bulwark may assume.
 *
 * @param ruleCount how many apps the user has asked to block.
 * @param consentNeeded whether Android still wants the user to approve a VPN.
 * @param vpnUp whether a tunnel is established, read from the platform.
 */
fun firewallState(ruleCount: Int, consentNeeded: Boolean, vpnUp: Boolean): FirewallState = when {
    ruleCount == 0 -> FirewallState.NOTHING_BLOCKED
    consentNeeded -> FirewallState.NEEDS_CONSENT
    vpnUp -> FirewallState.IN_FORCE
    else -> FirewallState.NOT_IN_FORCE
}

/**
 * The headline, said plainly.
 *
 * The wording of [FirewallState.NOT_IN_FORCE] is the one that matters. It says
 * what is true - the apps are reaching the network - rather than a softer
 * phrase like "paused", which would let someone close the app believing they
 * were covered.
 */
fun firewallHeadline(state: FirewallState, ruleCount: Int): String {
    val apps = if (ruleCount == 1) "1 app" else "$ruleCount apps"
    return when (state) {
        FirewallState.NOTHING_BLOCKED ->
            "No apps are blocked from the internet."
        FirewallState.NEEDS_CONSENT ->
            "$apps set to block, waiting for your permission to run."
        FirewallState.NOT_IN_FORCE ->
            "$apps set to block - but nothing is stopping them right now."
        FirewallState.IN_FORCE ->
            "$apps blocked from the internet."
    }
}

/**
 * The sentence under the headline. Null when there is nothing to add.
 *
 * Carries the two things a person needs and will not otherwise be told: that
 * this stops sending and never stops gathering, and that a restart drops the
 * rules until Bulwark runs again.
 *
 * The reboot warning is attached to the **working** state on purpose. Warning
 * someone only once it has already lapsed is telling them after it mattered.
 */
fun firewallDetail(
    state: FirewallState,
    alwaysOn: AlwaysOn,
    lockdown: Boolean,
): String? {
    // Before anything else, in every state, because it is the one setting that
    // can take a whole phone off the network.
    if (lockdown) return LOCKDOWN_WARNING

    return when (state) {
        FirewallState.NOTHING_BLOCKED -> null

        FirewallState.NEEDS_CONSENT ->
            "Android asks before any app may run a VPN. Bulwark's tunnel goes " +
                "nowhere - blocked apps are routed into it and their traffic is " +
                "dropped. No other app's traffic passes through Bulwark."

        FirewallState.NOT_IN_FORCE ->
            // Ends by saying what to *do*. The rest of this sentence tells
            // someone their blocks have lapsed and then leaves them there;
            // opening Bulwark is what puts them back, it happens by itself,
            // and until 2026-09-14 the one screen reporting the lapse never
            // mentioned it. Verified against `MainActivity`, which re-applies
            // on open whenever at least one rule exists.
            "Rules are cleared whenever the phone restarts, and whenever Android " +
                "stops the tunnel. These apps can reach the internet until it is " +
                "running again. Opening Bulwark puts them back."

        FirewallState.IN_FORCE -> when (alwaysOn) {
            AlwaysOn.ON ->
                "Android starts the tunnel by itself, so these stay blocked " +
                    "across restarts too."

            AlwaysOn.OFF ->
                "This stops them sending. It does not stop them collecting, and " +
                    "it lapses at every restart until Bulwark runs again. " +
                    "Always-on VPN in Settings closes that gap - but leave Block " +
                    "connections without VPN switched OFF."

            // The usual answer on Android 12+, where the setting is unreadable.
            // It must not be dressed up as "off": someone who already closed
            // the gap would be told their protection lapses, on the one card
            // that has to be trusted. Say what is true - the gap is open unless
            // they have done something Bulwark is not allowed to check.
            AlwaysOn.CANNOT_TELL ->
                "This stops them sending. It does not stop them collecting. " +
                    "Unless you have turned on Always-on VPN, it also lapses at " +
                    "every restart until Bulwark runs again - and Android does " +
                    "not let Bulwark check that setting, so this says the same " +
                    "thing either way. Leave Block connections without VPN " +
                    "switched OFF."
        }
    }
}

/**
 * The one sentence in this app that exists because Bulwark broke a phone.
 *
 * **Learned by doing it, 2026-09-11.** Bulwark recommended turning on "Block
 * connections without VPN" to close the reboot gap. That was wrong in a way
 * that is obvious once stated: lockdown denies every app the VPN does not
 * carry, and this tunnel deliberately carries almost nobody. The two combine
 * into the blocked app blocked and **every other app on the phone cut off**.
 *
 * `dumpsys connectivity` showed it exactly: lockdown filtering applied to every
 * uid on the device except Bulwark's own.
 *
 * So the two settings are not a pair, and anything mentioning one must be
 * explicit about the other. **Always-on is good. Lockdown is incompatible.**
 */
const val LOCKDOWN_WARNING: String =
    "Block connections without VPN is on, and it does not work with per-app " +
        "blocking: it cuts off every app Bulwark is not already blocking, " +
        "which is all of them. Turn it off in Settings. Always-on VPN on its " +
        "own is fine and worth having."
