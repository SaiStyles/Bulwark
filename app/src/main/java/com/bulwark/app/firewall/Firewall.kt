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
     * Nudges the service to bring the tunnel into line with the rules.
     *
     * **Carries no rules.** The service reads them from the log itself, which
     * is the only way the two cases that matter can work: Android starting us
     * for always-on, and Android restarting us after a kill. Both arrive with
     * no Intent of ours, so anything passed here would be missing exactly when
     * it was needed most.
     *
     * A service that finds no rules stops itself rather than holding the
     * device's only VPN slot to enforce nothing.
     */
    fun sync(context: Context) {
        context.startService(Intent(context, NetworkBlockService::class.java))
    }

    /** Stops the tunnel outright, whatever the rules say. */
    fun stop(context: Context) {
        context.startService(
            Intent(context, NetworkBlockService::class.java)
                .setAction(NetworkBlockService.ACTION_STOP)
        )
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

    /**
     * Whether Android has been told to keep Bulwark's tunnel up by itself.
     *
     * This is the only thing that closes the reboot gap: with it on, the system
     * starts the tunnel at boot before apps get network, and with lockdown it
     * denies traffic until the tunnel is up. It persists until someone changes
     * it - set it once and it holds.
     *
     * **Bulwark cannot set it**, and Android is right to refuse: an app able to
     * make itself always-on with lockdown could hold a phone's network hostage.
     * It is a Settings toggle, which is why [vpnSettings] exists.
     *
     * ## False can mean "off" or "could not tell", and both are treated as off
     *
     * These keys are not public API, so a read can fail or come back empty on a
     * build that stores them elsewhere. Treating that as "off" is the honest
     * direction: Bulwark then keeps warning about a gap that may already be
     * closed, which is a wasted sentence. Treating it as "on" would silence a
     * warning about a gap that is real, which is the failure this layer exists
     * to avoid.
     */
    fun alwaysOnHoldsOurTunnel(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val app = android.provider.Settings.Secure.getString(resolver, ALWAYS_ON_APP)
        val lockdown = android.provider.Settings.Secure.getString(resolver, ALWAYS_ON_LOCKDOWN)
        app == context.packageName && lockdown == "1"
    }.getOrDefault(false)

    /** Android's VPN settings, where always-on lives. */
    fun vpnSettings(): Intent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)

    /**
     * Not public API, so written as the platform's own key names.
     *
     * `conventions.md` forbids inlining a constant the platform defines - and
     * the platform does define these, as `Settings.Secure.ALWAYS_ON_VPN_APP`,
     * `@hide` and unreachable. The strings are the stable part here, the same
     * way the app-op names are in `AppOpsAccess`.
     */
    private const val ALWAYS_ON_APP = "always_on_vpn_app"
    private const val ALWAYS_ON_LOCKDOWN = "always_on_vpn_lockdown"
}
