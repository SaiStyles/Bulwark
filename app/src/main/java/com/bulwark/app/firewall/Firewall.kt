package com.bulwark.app.firewall

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Starting and stopping the tunnel, and saying honestly whether it is up.
 *
 * The seam between the policy layer's *rules* and whether those rules are
 * *in force*. Keeping them apart is the whole point: a firewall rule changes no
 * system state, so "the user asked for this" and "this is happening right now"
 * are different facts, and the screen has to be able to tell them apart.
 */
object Firewall {

    /**
     * Whether the user has already granted Bulwark permission to run a VPN.
     *
     * Null means consent is needed and [consentIntent] must be launched from an
     * Activity first - Android will not let an app establish a tunnel without
     * the user seeing the system's own dialogue, which is exactly right for a
     * capability this broad.
     */
    fun needsConsent(context: Context): Boolean = VpnService.prepare(context) != null

    /** The system's consent dialogue. Launch it for result from an Activity. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Applies [blocked] to the tunnel, starting or stopping it as needed.
     *
     * An empty set stops the service rather than running an empty tunnel:
     * holding the device's only VPN slot to enforce nothing would break the
     * user's real VPN for no reason, which the layer card forbids.
     */
    fun apply(context: Context, blocked: Set<String>) {
        val intent = Intent(context, NetworkBlockService::class.java)
        if (blocked.isEmpty()) {
            intent.action = NetworkBlockService.ACTION_STOP
        } else {
            intent.putStringArrayListExtra(
                NetworkBlockService.EXTRA_BLOCKED, ArrayList(blocked),
            )
        }
        context.startService(intent)
    }

    /**
     * Whether **Bulwark's own** tunnel is up right now.
     *
     * Asking `ConnectivityManager` would be the obvious way and needs
     * `ACCESS_NETWORK_STATE`; the no-network build check refused that, rightly,
     * and the check was left alone rather than amended to suit this feature.
     *
     * It would also have answered a slightly wrong question - "is *a* VPN up",
     * which is true when someone else's VPN is running and ours is not. The
     * service's own state answers the question actually being asked, and every
     * way it can go stale resets it. See `NetworkBlockService.isTunnelUp`.
     */
    fun ourTunnelIsUp(): Boolean = NetworkBlockService.isTunnelUp
}
