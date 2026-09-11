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
fun firewallDetail(state: FirewallState, lockdownOn: Boolean): String? = when (state) {
    FirewallState.NOTHING_BLOCKED -> null

    FirewallState.NEEDS_CONSENT ->
        "Android asks before any app may run a VPN. Bulwark's tunnel goes " +
            "nowhere - blocked apps are routed into it and their traffic is " +
            "dropped. No other app's traffic passes through Bulwark."

    FirewallState.NOT_IN_FORCE ->
        "Rules are cleared whenever the phone restarts, and whenever Android " +
            "stops the tunnel. These apps can reach the internet until it is " +
            "running again."

    FirewallState.IN_FORCE -> if (lockdownOn) {
        "Always-on VPN is on, so these apps stay blocked across restarts too."
    } else {
        "This stops them sending. It does not stop them collecting, and it " +
            "lapses at every restart until Bulwark runs again. Turn on " +
            "Always-on VPN in Settings to close that gap."
    }
}
