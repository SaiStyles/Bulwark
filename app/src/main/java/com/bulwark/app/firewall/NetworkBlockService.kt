package com.bulwark.app.firewall

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bulwark.app.policy.ActionJournal
import com.bulwark.app.policy.SqliteActionLog
import com.bulwark.app.policy.blockedPackages
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * A tunnel that goes nowhere, and only the blocked apps are in it.
 *
 * ## Why this is so much smaller than NetGuard
 *
 * NetGuard and RethinkDNS route **every** app through a tun device and forward
 * the traffic on in native code, because they filter connection by connection
 * and answer DNS. That machine is the right one for their job and the wrong one
 * for ours.
 *
 * Blocking an app needs none of it. `addAllowedApplication` inverts the
 * question: name only the apps to block, and they are the only ones routed
 * here. Everything else on the phone bypasses the tunnel entirely - full speed,
 * no battery cost from us, and **their traffic never passes through Bulwark at
 * all**, which is a privacy property worth more than the feature it enables.
 *
 * So there is no packet inspection here, no forwarding, no native code, and no
 * DNS. The blocked app writes into a pipe whose far end is a loop that throws
 * the bytes away.
 *
 * ## What this does not do, deliberately
 *
 * No tracker or ad blocklists, and no traffic log. Both would mean routing
 * every app's traffic through this process, which is the expensive half, the
 * complicated half, and the half where Bulwark would be a worse NetGuard. See
 * `context/layers/03-firewall.md`.
 *
 * ## It is not a containment field
 *
 * A blocked app still runs and still gathers. This stops it *sending* while
 * the tunnel is up, and the tunnel is not up at every moment of the phone's
 * life - see [FirewallStatus] and the guardrails on the layer card. For an app
 * that should never run at all, the answer is layer 1.
 */
class NetworkBlockService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var drain: Thread? = null

    /**
     * Reads its own rules. **Never takes them from the caller.**
     *
     * The first version was handed the blocked list in the starting Intent,
     * which worked for exactly one case: Bulwark starting the service itself.
     * It broke silently for the two that matter most.
     *
     * - **Always-on VPN.** Android starts the service at boot, with no Intent
     *   of ours and no extras. The old code read an empty list, concluded
     *   there was nothing to block, and shut itself down - so the one feature
     *   that closes the reboot gap could never have worked.
     * - **START_STICKY.** When Android restarts a killed service the Intent is
     *   null, so the "rules come back after a kill" behaviour this file claims
     *   would have come back and immediately quit.
     *
     * Both were found by walking through a reboot out loud rather than by any
     * test, which is worth remembering: the bug was in what happens when
     * *something else* starts us, and everything written here had assumed we
     * were the one doing the starting.
     *
     * So the service now asks the log, which is the same source the screen
     * reads and the only place the rules ever live.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // The log is a database and this is the main thread. Reading it here
        // would trip the debug build's own main-thread check, and deservedly.
        thread(name = "bulwark-rules", isDaemon = true) { applyRulesFromLog() }

        // START_STICKY so a killed service comes back. It means something now:
        // the restart arrives with a null Intent, and the rules are read from
        // the log rather than expected in extras.
        return START_STICKY
    }

    /** Reads the rules and brings the tunnel into line with them. */
    private fun applyRulesFromLog() {
        val blocked = runCatching {
            ActionJournal(SqliteActionLog(applicationContext)).history().blockedPackages()
        }.getOrElse {
            Log.w(TAG, "could not read rules: ${it.javaClass.simpleName}")
            return
        }

        if (blocked.isEmpty()) {
            // Nothing to block is not a reason to hold the device's only VPN
            // slot. Bulwark gets out of the way rather than sitting there
            // looking busy, which would break a real VPN for nothing.
            teardown()
            stopSelf()
            return
        }

        teardown()
        tunnel = establish(blocked.toList())
        if (tunnel == null) stopSelf()
    }

    /**
     * Builds the tunnel with exactly the blocked apps inside it.
     *
     * A package the platform does not know is skipped rather than fatal: an app
     * can be uninstalled between the rule being written and the tunnel being
     * built, and losing the other nineteen rules over it would be the wrong
     * trade.
     */
    private fun establish(blocked: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION)
            // Addresses nothing will ever reach. The tunnel is a dead end by
            // design, so these only have to be valid and private.
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:0:0:111::1", 128)
            .addRoute("::", 0)

        var added = 0
        blocked.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
                .onSuccess { added++ }
                .onFailure { Log.w(TAG, "skipping unknown package") }
        }
        if (added == 0) return null

        return runCatching { builder.establish() }
            .onFailure { Log.w(TAG, "establish failed: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.also {
                startDraining(it)
                isTunnelUp = true
            }
    }

    /**
     * Reads the blocked apps' packets and discards them.
     *
     * Draining rather than ignoring the descriptor. Leaving it unread lets the
     * kernel buffer fill, and what a blocked app experiences then is less
     * predictable than a clean discard - which matters because the app is
     * *supposed* to fail, and it should fail the same way every time.
     *
     * The only traffic reaching this loop belongs to apps the user asked to
     * block, and it is dropped without being examined. Nothing is parsed, and
     * nothing is recorded.
     */
    private fun startDraining(fd: ParcelFileDescriptor) {
        drain = thread(name = "bulwark-drain", isDaemon = true) {
            val buffer = ByteArray(BUFFER_BYTES)
            runCatching {
                FileInputStream(fd.fileDescriptor).use { stream ->
                    while (!Thread.currentThread().isInterrupted) {
                        if (stream.read(buffer) < 0) break
                    }
                }
            }
        }
    }

    private fun teardown() {
        isTunnelUp = false
        drain?.interrupt()
        drain = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        // The user replaced our VPN with another, or revoked consent. Android
        // gives no second chance here, so the rules are simply no longer in
        // force - which the screen must be able to discover rather than assume.
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        /**
         * Whether a tunnel is established **by this process, right now**.
         *
         * Deliberately not read from `ConnectivityManager`: that needs
         * `ACCESS_NETWORK_STATE`, and Bulwark's build fails if it declares any
         * network permission. The check is right and was left alone.
         *
         * This is not the "remembered value" the layer warns against, because
         * every way the tunnel can stop also clears it:
         *
         * - the service is stopped or replaced -> [teardown]
         * - consent is withdrawn or another VPN wins -> [onRevoke]
         * - Android kills the process, or the phone reboots -> the flag is
         *   process state and comes back `false`
         *
         * The one case it can be wrong is a tunnel dying without any of those
         * running, which is why the screen re-reads it on every load rather
         * than trusting a value from a minute ago.
         */
        @Volatile
        var isTunnelUp: Boolean = false
            private set

        private const val TAG = "BulwarkFirewall"
        private const val SESSION = "Bulwark"
        private const val BUFFER_BYTES = 32767

        const val ACTION_STOP = "com.bulwark.app.firewall.STOP"
    }
}
