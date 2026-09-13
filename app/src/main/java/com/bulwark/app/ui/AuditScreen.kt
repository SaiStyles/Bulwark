package com.bulwark.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bulwark.app.firewall.AlwaysOn
import com.bulwark.app.observability.AppOpLedger
import com.bulwark.app.observability.DozeExemptions
import com.bulwark.app.firewall.Firewall
import com.bulwark.app.firewall.FirewallState
import com.bulwark.app.firewall.firewallDetail
import com.bulwark.app.firewall.firewallHeadline
import com.bulwark.app.firewall.firewallState
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.AuditSummary
import com.bulwark.app.permissions.DeviceSignal
import com.bulwark.app.permissions.PermissionAcrossApps
import com.bulwark.app.permissions.RatFinding
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.groupByPermission
import com.bulwark.app.permissions.permissionAuditNotice
import com.bulwark.app.permissions.permissionAuditState
import com.bulwark.app.permissions.ratFindings
import com.bulwark.app.permissions.summarise
import com.bulwark.app.policy.ActionRecord
import com.bulwark.app.shizuku.CloseAction
import com.bulwark.app.shizuku.DumpsysAccess
import com.bulwark.app.shizuku.Closeable
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.RuntimePermissionAccess
import com.bulwark.app.shizuku.ShizukuState
import com.bulwark.app.shizuku.SpecialAccessReader
import com.bulwark.app.shizuku.doneForNowHeadline
import com.bulwark.app.shizuku.whatCanBeClosed
import com.bulwark.app.ui.theme.CautionBackground
import com.bulwark.app.ui.theme.CautionText
import com.bulwark.app.ui.theme.Incomplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the audit renders, as data.
 *
 * **No defaults, deliberately.** Twenty values reach this screen from six
 * separate reads, and the failure this type exists to prevent is one of them
 * silently not arriving - which draws a card with nothing in it rather than an
 * error. A default would turn that into a value the compiler is happy with. No
 * default means adding a field breaks the build until someone wires it, which
 * is a stronger guarantee than any test.
 *
 * Tests build one through `auditReadings(...)` in the androidTest source set,
 * which is where the defaults live and where they are harmless.
 */
data class AuditReadings(
    /** Null until a read has produced a list, which is not the same as empty. */
    val access: List<AppAccess>?,
    val accessSummary: AuditSummary?,
    val accessUnavailable: List<String>,
    val ratSignals: List<RatFinding>,
    val wirelessDebuggingOn: Boolean,
    val interrupted: List<ActionRecord>,
    /** Null means no read produced a list; the screen says something different for each. */
    val permissionGroups: List<PermissionAcrossApps>?,
    val permissionsReadFailed: Boolean,
    /** Null means "could not tell", never "none". */
    val systemPackages: Set<String>?,
    val refinedPermissions: Set<String>,
    val blockedApps: Set<String>,
    val firewallConsentNeeded: Boolean,
    val aVpnIsUp: Boolean,
    val alwaysOn: AlwaysOn,
    val lockdown: Boolean,
    val shizukuReady: Boolean,
    /** Null until the Doze read lands. Empty is a different fact from absent. */
    val doze: DozeExemptions.Reading?,
    /** Why the Doze read produced nothing usable. Null when it worked. */
    val dozeCouldNotTell: String?,
    /** Null until the app-op ledger lands. Empty is a different fact from absent. */
    val ledger: List<AppOpLedger.Summary>?,
    /** Why the ledger produced nothing usable. Null when it worked. */
    val ledgerCouldNotTell: String?,
)

/**
 * What is true about this phone: special access, RAT signals, permissions
 * across apps, and the firewall's real state.
 *
 * `_shared/design.md` rule 1 - one screen, one question. This one answers
 * "what can the software on this phone do to me", and nothing here changes a
 * package; that is the Apps screen's job.
 *
 * ## Why this is two composables
 *
 * This one owns the state and the reads. [AuditContent] owns the drawing and
 * takes plain data, so a test can render every card without a device read and
 * without an `ActionRunner`. That seam is the whole reason the split could
 * finally happen: `design.md` recorded that threading these values into their
 * own screen has a failure mode of a card rendering **empty** rather than
 * erroring, and that a screenshot was the only detector. `AuditContentTest` is
 * the detector now.
 */
@Composable
fun AuditScreen(
    state: ShizukuState,
    runner: ActionRunner,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var access by remember { mutableStateOf<List<AppAccess>?>(null) }
    var accessSummary by remember { mutableStateOf<AuditSummary?>(null) }
    var accessUnavailable by remember { mutableStateOf<List<String>>(emptyList()) }
    var ratSignals by remember { mutableStateOf<List<RatFinding>>(emptyList()) }
    // Needs no privilege - Settings.Global, world-readable, which is also
    // why a rogue app can check it before deciding to use it.
    var wirelessDebuggingOn by remember { mutableStateOf(false) }
    // Read off the main thread, below. This was a `remember { }` block in the
    // composable body, which put a SQLite read on the main thread on every
    // refresh - invisible on a fast phone and exactly the kind of thing that
    // makes a cheap one stutter.
    var interrupted by remember { mutableStateOf<List<ActionRecord>>(emptyList()) }
    // Null means no read has produced a list, which is NOT the same as a read
    // that produced an empty one. The screen says something different for each.
    var permissionGroups by remember { mutableStateOf<List<PermissionAcrossApps>?>(null) }
    var permissionsReadFailed by remember { mutableStateOf(false) }
    // Null means "could not tell", never "none".
    var systemPackages by remember { mutableStateOf<Set<String>?>(null) }
    // Capabilities whose flag read has come back. Until a permission is in
    // here, its rows are still being checked and no control is drawn.
    var refinedPermissions by remember { mutableStateOf(emptySet<String>()) }
    var blockedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var firewallConsentNeeded by remember { mutableStateOf(false) }
    var aVpnIsUp by remember { mutableStateOf(false) }
    var alwaysOn by remember { mutableStateOf(AlwaysOn.CANNOT_TELL) }
    var lockdown by remember { mutableStateOf(false) }
    var doze by remember { mutableStateOf<DozeExemptions.Reading?>(null) }
    var dozeCouldNotTell by remember { mutableStateOf<String?>(null) }
    var ledger by remember { mutableStateOf<List<AppOpLedger.Summary>?>(null) }
    var ledgerCouldNotTell by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    val ready = state is ShizukuState.Ready

    fun report(outcome: ActionRunner.Outcome) {
        val message = when (outcome) {
            is ActionRunner.Outcome.Done -> outcome.message
            is ActionRunner.Outcome.Refused -> outcome.why
            // Already written for a person by ActionRunner; no prefix.
            is ActionRunner.Outcome.Failed -> outcome.why
            ActionRunner.Outcome.Cancelled -> null
        }
        // A failure never auto-dismisses. The default four seconds is fine for
        // "switched it off"; it is not fine for "this did not work", which the
        // user has to see to act on. Rule 6 is report-and-stop, and a report
        // that disappears on its own is neither.
        message?.let { text ->
            val failed = outcome is ActionRunner.Outcome.Failed ||
                outcome is ActionRunner.Outcome.Refused
            scope.launch {
                snackbar.showSnackbar(
                    message = text,
                    withDismissAction = failed,
                    duration = if (failed) SnackbarDuration.Indefinite else SnackbarDuration.Long,
                )
            }
        }
        // Re-read from the device rather than assuming the change landed.
        if (outcome is ActionRunner.Outcome.Done) reload++
    }

    // The special-access audit runs whether or not Shizuku is up: accessibility
    // and device admin need no privilege at all, so the two most dangerous
    // accesses are readable on first launch. Only the AppOps four wait.
    LaunchedEffect(state, reload) {
        val built = withContext(Dispatchers.IO) {
            val privileged = state is ShizukuState.Ready
            val result = SpecialAccessReader.read(context, privileged)
            // The privileged list when we have it - it sees all 370 and is
            // authoritative - falling back to our own PackageManager, which
            // sees about 176 under visibility filtering. Null when neither
            // knows, which the UI says out loud rather than guessing.
            val installed = if (privileged) {
                runCatching { PrivilegedPackages.listDetailed() }.getOrNull()
            } else {
                null
            }
            val known = installed?.map { it.packageName }?.toSet()
            val systemNames = installed?.filter { it.isSystem }?.map { it.packageName }?.toSet()
            val apps = result.holders.map { (pkg, accesses) ->
                AppAccess(
                    pkg, accesses,
                    isSystem = SpecialAccessReader.isSystem(context, pkg, systemNames, known),
                )
            }
            val audited = apps.audit()
            AuditOutcome(
                audited,
                apps.summarise(),
                result.unavailable,
                DeviceSignal.WIRELESS_DEBUGGING_ON in result.signals,
                ratFindings(
                    result.signals,
                    audited,
                    shizukuRunning = privileged,
                    overlayKnown = result.overlayKnown,
                ),
            )
        }
        access = built.apps
        accessSummary = built.summary
        accessUnavailable = built.unavailable
        wirelessDebuggingOn = built.wirelessDebuggingOn
        ratSignals = built.rat
    }

    // The log is a database, so this reads it off the main thread.
    LaunchedEffect(reload) {
        interrupted = withContext(Dispatchers.IO) {
            runCatching { runner.interrupted() }.getOrDefault(emptyList())
        }
    }

    // The firewall's state, read from the platform every time rather than
    // remembered. Every interesting case is one where a remembered value would
    // be wrong: the service was killed, consent was withdrawn, another VPN
    // replaced ours, or the phone restarted and nothing has started yet.
    //
    // No Shizuku here on purpose - this is the layer that must work without it.
    //
    // **Re-applying the rules is not done here.** It moved to MainActivity on
    // 2026-09-13, when this screen stopped being one half of the screen a
    // person always lands on: a sync that only ran if you happened to open
    // Audit would leave the blocks down for anyone who stayed on Apps. Opening
    // the app re-applies them; opening a tab does not have to.
    LaunchedEffect(reload) {
        val rules = withContext(Dispatchers.IO) {
            runCatching { runner.blockedNetworkApps() }.getOrDefault(emptySet())
        }
        blockedApps = rules
        firewallConsentNeeded = Firewall.needsConsent(context)
        alwaysOn = Firewall.alwaysOnState(context)
        lockdown = Firewall.lockdownIsOn(context)
        aVpnIsUp = Firewall.ourTunnelIsUp()

        if (rules.isEmpty()) return@LaunchedEffect

        // Read again while the tunnel comes up, because starting it is
        // asynchronous: the service is told to start, reads the rules on its own
        // thread, then establishes. A single read taken here lands before any of
        // that and sticks, so the card would say "nothing is stopping them"
        // while the block was working.
        //
        // Wrong in the safe direction, which is the dangerous kind of wrong for
        // this card: it teaches people to distrust the one screen that has to be
        // trusted, and the next time it says that truthfully they will shrug.
        //
        // Bounded, and it stops the moment the answer is yes. A tunnel that has
        // not appeared in this long is genuinely not coming.
        repeat(TUNNEL_CHECKS) {
            if (aVpnIsUp) return@LaunchedEffect
            delay(TUNNEL_CHECK_MS)
            aVpnIsUp = Firewall.ourTunnelIsUp()
        }
    }

    // The cross-app permission view. Needs Shizuku: reading another app's
    // granted permissions is privileged, unlike the special-access audit above.
    //
    // Deliberately without flags. Flags cost a binder call each and decide only
    // whether a row may be *offered*, which matters for the handful of rows a
    // user opens - not for the few thousand this sweep sees.
    LaunchedEffect(ready, reload) {
        if (!ready) {
            // **Cleared, not kept.** Returning early here used to leave the
            // previous list on screen, so a phone whose Shizuku had died went on
            // showing permissions Bulwark could no longer verify. Data we cannot
            // re-read is worse than no data: it is a claim with no source.
            permissionGroups = null
            systemPackages = null
            permissionsReadFailed = false
            refinedPermissions = emptySet()
            return@LaunchedEffect
        }
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val installed = PrivilegedPackages.listDetailed()
                val holdings = RuntimePermissionAccess.sweep(
                    installed, context.packageManager,
                )
                groupByPermission(holdings) to
                    installed.filter { it.isSystem }.map { it.packageName }.toSet()
            }.getOrNull()
        }
        permissionGroups = built?.first
        systemPackages = built?.second
        permissionsReadFailed = built == null
        refinedPermissions = emptySet()
    }

    // What is allowed to keep working while the phone sleeps.
    //
    // Needs Shizuku: `android.permission.DUMP` is signatureOrSystem, which is
    // the whole reason this is worth showing - no Play Store app can. Cleared
    // rather than kept when the server dies, for the same reason the permission
    // sweep is: data we cannot re-read is a claim with no source.
    LaunchedEffect(ready, reload) {
        if (!ready) {
            doze = null
            dozeCouldNotTell = null
            return@LaunchedEffect
        }
        val reading = withContext(Dispatchers.IO) {
            DumpsysAccess.read(DumpsysAccess.Dump.DOZE_WHITELIST)
        }
        when (reading) {
            is DumpsysAccess.Reading.Lines -> {
                val parsed = DozeExemptions.parse(reading.lines)
                doze = parsed
                // Rows arrived but none parsed: that is a reach failure, not a
                // phone with nothing exempt, and it must not read as one.
                dozeCouldNotTell = if (parsed.readNothing) {
                    "Bulwark reached this phone's sleep settings but could not " +
                        "read the answer it got back."
                } else {
                    null
                }
            }
            is DumpsysAccess.Reading.CouldNotTell -> {
                doze = null
                dozeCouldNotTell = reading.why
            }
        }
    }

    // When apps used the microphone, camera and precise location, and whether
    // they were in front of you at the time.
    //
    // Three dumps rather than one: `--op` filters at the source, and the
    // unfiltered dump is 2.29 MB against 149 KB for one op. Sequential and off
    // the main thread - three binder transactions that each produce a few
    // thousand lines are not something to run while anyone is scrolling.
    LaunchedEffect(ready, reload) {
        if (!ready) {
            ledger = null
            ledgerCouldNotTell = null
            return@LaunchedEffect
        }
        val outcome = withContext(Dispatchers.IO) {
            val readings = mutableListOf<AppOpLedger.Reading>()
            val failures = mutableListOf<String>()
            for (op in AppOpLedger.Op.entries) {
                when (val dump = DumpsysAccess.read(dumpFor(op))) {
                    is DumpsysAccess.Reading.Lines ->
                        readings += AppOpLedger.parse(dump.lines, op)
                    is DumpsysAccess.Reading.CouldNotTell -> failures += dump.why
                }
            }
            readings to failures
        }
        val (readings, failures) = outcome
        // One op failing is not the whole card failing, but it is not nothing
        // either: the list is short by an unknown amount and must say so.
        ledger = if (readings.isEmpty()) null else AppOpLedger.summarise(readings)
        ledgerCouldNotTell = when {
            readings.isEmpty() -> failures.firstOrNull()
                ?: "Bulwark could not read this phone's record of app activity."
            failures.isNotEmpty() ->
                "Bulwark could not read all three of these, so the list may be short."
            else -> AppOpLedger.unreadableNotice(readings)
        }
    }

    AuditContent(
        readings = AuditReadings(
            access = access,
            accessSummary = accessSummary,
            accessUnavailable = accessUnavailable,
            ratSignals = ratSignals,
            wirelessDebuggingOn = wirelessDebuggingOn,
            interrupted = interrupted,
            permissionGroups = permissionGroups,
            permissionsReadFailed = permissionsReadFailed,
            systemPackages = systemPackages,
            refinedPermissions = refinedPermissions,
            blockedApps = blockedApps,
            firewallConsentNeeded = firewallConsentNeeded,
            aVpnIsUp = aVpnIsUp,
            alwaysOn = alwaysOn,
            lockdown = lockdown,
            shizukuReady = ready,
            doze = doze,
            dozeCouldNotTell = dozeCouldNotTell,
            ledger = ledger,
            ledgerCouldNotTell = ledgerCouldNotTell,
        ),
        onOpenVpnSettings = { runCatching { context.startActivity(Firewall.vpnSettings()) } },
        onAllowVpn = { runner.requestVpnConsent() },
        onOpenShizuku = {
            // Bulwark cannot stop the server - Shizuku refuses "exit" from
            // anyone but its own manager. Measured 2026-09-12, see DoneForNow.
            val intent = context.packageManager
                .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent == null) {
                report(
                    ActionRunner.Outcome.Failed(
                        "Bulwark could not open Shizuku. Open it from " +
                            "your app list and use Stop there.",
                    ),
                )
            } else {
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    report(
                        ActionRunner.Outcome.Failed(
                            "Bulwark could not open Shizuku. Open it " +
                                "from your app list and use Stop there.",
                        ),
                    )
                }
            }
        },
        onOpenDeveloperOptions = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                report(
                    ActionRunner.Outcome.Failed(
                        "Bulwark could not open Developer options on " +
                            "this phone. The switch is under Settings, " +
                            "Developer options, Wireless debugging.",
                    ),
                )
            }
        },
        onOpenPermission = { permission ->
            // The expensive half, paid only for a capability someone opened.
            if (permission !in refinedPermissions) scope.launch {
                val refreshed = withContext(Dispatchers.IO) {
                    val group = permissionGroups
                        ?.firstOrNull { it.permission == permission }
                        ?: return@withContext null
                    RuntimePermissionAccess.refineWithFlags(group.holders)
                }
                if (refreshed != null) {
                    permissionGroups = permissionGroups?.map {
                        if (it.permission == permission) it.copy(holders = refreshed) else it
                    }
                    refinedPermissions = refinedPermissions + permission
                }
            }
        },
        onRevokeAcross = { permission, packages ->
            runner.revokeAcrossApps(permission, packages) { outcome ->
                report(outcome)
                // Re-read rather than assume: the screen must show what the
                // phone says, not what we asked for. A revoke the platform
                // ignored has to come back looking ignored.
                reload++
            }
        },
        modifier = modifier,
    )
}

/**
 * The audit, drawn from data alone.
 *
 * Every read is already done by the time this runs and every action is a
 * lambda, which is what lets `AuditContentTest` render the whole screen on a
 * device with no Shizuku and assert that each card has content in it.
 */
@Composable
fun AuditContent(
    readings: AuditReadings,
    onOpenVpnSettings: () -> Unit,
    onAllowVpn: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenPermission: (String) -> Unit,
    onRevokeAcross: (String, List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Says which of the three this is. "Your phone" was right when
            // there was one screen; with a tab bar naming three, a heading that
            // repeats none of them is a heading that tells you nothing.
            //
            // Not "what apps can do to you" - the first card already says that,
            // and the permissions card below says "What apps can do". Three
            // near-identical phrases on one screen is the heading telling you
            // nothing. design.md calls this screen's job "what is true about
            // this phone", so it says that.
            Text(
                "What is true about this phone",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )

            // EVERYTHING scrolls. The audit used to sit in a fixed header above
            // a LazyColumn, so an audit with a dozen findings could not be
            // scrolled and its own later rows were unreachable. A header that
            // grows with the data is not a header.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {

                // Shown whether or not Shizuku is up. Three of four sources need
                // no privilege, so this is what Bulwark can say on first launch -
                // before asking anyone to do anything difficult.
                //
                // **The slot is always emitted, even before the read lands.**
                // Not for tidiness - for a bug this screen actually had on
                // 2026-09-13. `LazyColumn` anchors its scroll to the first
                // visible *key*. While this item was conditional, opening Audit
                // composed the list with "permissions" as item 0; the audit read
                // finished a second later and **prepended** "access" above the
                // anchor, leaving the most important card on the screen sitting
                // off the top of the viewport. It rendered, it was correct, and
                // nobody could see it without scrolling up.
                //
                // It never happened before the screens were split because both
                // tabs' effects ran whichever tab was shown, so this had always
                // landed before anyone could look at it. Holding the slot keeps
                // item 0 stable, and saying "still reading" is the honest thing
                // to show meanwhile: absent reads as "nothing to report".
                item(key = "access") {
                    val a = readings.access
                    val s = readings.accessSummary
                    if (a != null && s != null) {
                        CollapsibleAudit(a, s, readings.accessUnavailable, readings.ratSignals)
                    } else {
                        Text(
                            "Checking what apps can do to you…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                // Shown whenever there is anything to say, which is whenever a
                // rule exists. A firewall that is not working must announce that
                // where it is looked at, not in a settings page.
                if (readings.blockedApps.isNotEmpty()) {
                    item(key = "firewall") {
                        FirewallCard(
                            ruleCount = readings.blockedApps.size,
                            consentNeeded = readings.firewallConsentNeeded,
                            vpnUp = readings.aVpnIsUp,
                            alwaysOn = readings.alwaysOn,
                            lockdown = readings.lockdown,
                            onOpenVpnSettings = onOpenVpnSettings,
                            onAllow = onAllowVpn,
                        )
                    }
                }

                // The cross-app permission view, below the special-access audit
                // because that one names the more dangerous accesses and needs
                // no privilege to do it.
                //
                // **Always rendered.** It used to appear only when it had data,
                // so the three different silences - no Shizuku, read failed,
                // genuinely nothing - all looked identical to a section that had
                // vanished.
                item(key = "permissions") {
                    val auditState = permissionAuditState(
                        shizukuReady = readings.shizukuReady,
                        readFailed = readings.permissionsReadFailed,
                        groups = readings.permissionGroups,
                    )
                    PermissionAuditSection(
                        groups = readings.permissionGroups.orEmpty(),
                        notice = permissionAuditNotice(auditState),
                        systemPackages = readings.systemPackages,
                        refined = readings.refinedPermissions,
                        onOpen = onOpenPermission,
                        onRevoke = onRevokeAcross,
                    )
                }

                // The sharpest card on the screen, so it goes above the Doze
                // one: both answer "what did this app do", and a timestamped
                // use of the microphone outranks a sleep exemption.
                //
                // Slot emitted from the first composition, like every card
                // here - a late read that adds an item shifts the scroll
                // anchor, which already hid one card on this screen.
                item(key = "ledger") {
                    LedgerCard(
                        summaries = readings.ledger,
                        couldNotTell = readings.ledgerCouldNotTell,
                        shizukuReady = readings.shizukuReady,
                    )
                }

                // What keeps running while the phone sleeps.
                //
                // Below the permission view because that one answers a bigger
                // question - who can hear you - and this one is about
                // behaviour rather than capability.
                //
                // The slot is emitted from the first composition, like the
                // access card above it and for the same reason: a late read
                // that *adds* a `LazyColumn` item shifts everything under it
                // relative to the scroll anchor. That already hid one card on
                // this screen. `design.md` carries the rule.
                item(key = "doze") {
                    DozeCard(
                        reading = readings.doze,
                        couldNotTell = readings.dozeCouldNotTell,
                        shizukuReady = readings.shizukuReady,
                    )
                }

                // Nothing guards this. It once sat after an early return
                // belonging to the package list, which made the offer
                // unreachable in exactly the state where half of it is the
                // useful half - wireless debugging can be closed whether or not
                // Shizuku is running. Placed for reading order, suppressed by a
                // guard about something else entirely. Separate screens make
                // that class of mistake harder to make.
                val closeable = whatCanBeClosed(
                    shizukuRunning = readings.shizukuReady,
                    wirelessDebuggingOn = readings.wirelessDebuggingOn,
                )
                if (closeable.isNotEmpty()) {
                    item(key = "done-for-now") {
                        DoneForNowCard(
                            closeable = closeable,
                            onOpenShizuku = onOpenShizuku,
                            onOpenDeveloperOptions = onOpenDeveloperOptions,
                        )
                    }
                }

                if (readings.interrupted.isNotEmpty()) {
                    item(key = "interrupted") {
                        InterruptedCard(readings.interrupted.map { it.packageName })
                    }
                }
            }
        }
    }
}

/**
 * What the firewall is actually doing, as opposed to what was asked for.
 *
 * Reads all three facts fresh and says the true one. The state that matters is
 * the uncomfortable one - rules exist and nothing is enforcing them - which
 * happens after every restart and must never be softened into "paused".
 */
@Composable
private fun FirewallCard(
    ruleCount: Int,
    consentNeeded: Boolean,
    vpnUp: Boolean,
    alwaysOn: AlwaysOn,
    lockdown: Boolean,
    onOpenVpnSettings: () -> Unit,
    onAllow: () -> Unit,
) {
    val state = firewallState(ruleCount, consentNeeded, vpnUp)

    // Colour carries the state rather than decorating it. A firewall that is
    // working is ordinary and gets the ordinary card; one that is not is the
    // app failing to do what it said, and that earns the caution colour.
    // Lockdown is never 'working', whatever the tunnel is doing: the phone
    // is cut off and the card has to look like it.
    val working = state.isEnforcing && !lockdown
    val colors =
        if (working) CardDefaults.cardColors()
        else CardDefaults.cardColors(containerColor = CautionBackground)
    val textColour = if (working) Color.Unspecified else CautionText

    Card(colors = colors) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                firewallHeadline(state, ruleCount),
                style = MaterialTheme.typography.titleSmall,
                color = textColour,
            )
            // lockdownOn is false until Bulwark can read it. Claiming the gap
            // is closed when it has not been checked would be the exact
            // failure this card exists to prevent.
            firewallDetail(state, alwaysOn = alwaysOn, lockdown = lockdown)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = textColour)
            }
            // Each state offers the thing that state actually needs. The
            // first version showed "Open VPN settings" while the card said
            // "waiting for your permission" - and consent is not granted in
            // settings, it is a dialogue Bulwark has to raise. A button that
            // sends someone where they cannot do the thing is worse than no
            // button.
            when {
                // Outranks every other state: the phone is off the network and
                // the only useful action is going and turning it off.
                lockdown ->
                    TextButton(onClick = onOpenVpnSettings) {
                        Text("Open VPN settings and turn it off")
                    }
                state == FirewallState.NEEDS_CONSENT ->
                    TextButton(onClick = onAllow) { Text("Allow Bulwark to run it") }
                state != FirewallState.NOTHING_BLOCKED && alwaysOn != AlwaysOn.ON ->
                    TextButton(onClick = onOpenVpnSettings) { Text("Open VPN settings") }
            }
        }
    }
}

/** Which dump answers for which op. Kept beside the effect that uses it. */
private fun dumpFor(op: AppOpLedger.Op): DumpsysAccess.Dump = when (op) {
    AppOpLedger.Op.MICROPHONE -> DumpsysAccess.Dump.MICROPHONE_USES
    AppOpLedger.Op.CAMERA -> DumpsysAccess.Dump.CAMERA_USES
    AppOpLedger.Op.PRECISE_LOCATION -> DumpsysAccess.Dump.PRECISE_LOCATION_USES
}

/**
 * When apps used the microphone, camera and precise location **while they were
 * not in front of you**.
 *
 * The sharpest thing Bulwark shows, and the one Android keeps and does not
 * display: the Privacy Dashboard covers 24 hours, coarsely, and never says
 * whether the app was on screen at the time.
 *
 * ## Why this card is careful
 *
 * A ledger of who used the microphone is the most sensitive thing in the app -
 * it is why `security.md` OPEN-1 existed - so it lives on Audit, which is
 * `FLAG_SECURE`. And it states rather than accuses: a music app recording in
 * the background, or a navigation app holding location, is doing the job it
 * was installed for. `design.md` rule 4 - no colour here, because colour would
 * make every row read as a finding.
 */
@Composable
private fun LedgerCard(
    summaries: List<AppOpLedger.Summary>?,
    couldNotTell: String?,
    shizukuReady: Boolean,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "What apps did while you were not looking",
                style = MaterialTheme.typography.titleMedium,
            )

            when {
                !shizukuReady -> Text(
                    "Shizuku is not running. This record is kept by Android and " +
                        "never shown to you, and reading it needs the access " +
                        "Shizuku provides.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                summaries == null && couldNotTell != null -> Text(
                    couldNotTell,
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                summaries == null -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        AppOpLedger.headline(summaries),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppOpLedger.detail(summaries)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    couldNotTell?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
                    }
                    summaries.forEach { summary ->
                        Text(
                            AppOpLedger.line(summary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What is allowed to keep working while the phone sleeps.
 *
 * The first thing Bulwark shows that **no Play Store app can** -
 * `android.permission.DUMP` is `signatureOrSystem`, so this list is reachable
 * only through Shizuku. `capability-research/observability.md` has the ranking
 * and the other five sources.
 *
 * ## What this card refuses to do
 *
 * It does not accuse. An exemption is a capability, not a symptom: a messaging
 * app that must receive messages while the phone is idle has an ordinary
 * reason to be here, and colouring the list would make every row look like a
 * finding. `design.md` rule 7 - name what a thing is - and rule 4, colour is
 * meaning.
 *
 * It also does not offer a fix it does not have. Taking an app off this list is
 * a *write* to `deviceidle`, and `DumpsysAccess` is a closed set of reads on
 * purpose, so the copy points at what Bulwark can actually do instead.
 */
@Composable
private fun DozeCard(
    reading: DozeExemptions.Reading?,
    couldNotTell: String?,
    shizukuReady: Boolean,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "What keeps running while your phone sleeps",
                style = MaterialTheme.typography.titleMedium,
            )

            when {
                // Said out loud rather than shown as an empty list. Three
                // different silences used to look identical on this screen.
                // Deliberately not "Start Shizuku to see this" - the
                // permissions card above already opens with that exact
                // sentence, and two identical sentences on one screen is the
                // near-identical-copy problem `design.md` keeps cutting.
                !shizukuReady -> Text(
                    "Shizuku is not running. Android does not let an ordinary " +
                        "app ask which apps are exempt from sleeping, which is " +
                        "why this list is worth showing - and why Bulwark " +
                        "cannot read it on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                couldNotTell != null && reading == null -> Text(
                    couldNotTell,
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                reading == null -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        DozeExemptions.headline(reading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DozeExemptions.detail(reading)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    // The partial-read warning sits with the list it qualifies,
                    // never folded away: a short list read as complete is the
                    // false all-clear this whole file is written against.
                    DozeExemptions.unreadableNotice(reading)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
                    }
                    couldNotTell?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
                    }
                    reading.exemptions.forEach { exemption ->
                        Text(
                            exemption.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The audit, collapsible.
 *
 * Expanded by default - being seen is the entire point - but foldable, because
 * a phone with thirty findings would otherwise bury everything under them.
 * Collapsed it still states the count, so folding it away never hides that
 * there is something there.
 */
@Composable
private fun CollapsibleAudit(
    apps: List<AppAccess>,
    summary: AuditSummary,
    unavailable: List<String>,
    ratFindings: List<RatFinding>,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column {
        if (expanded) {
            SpecialAccessSection(apps, summary, unavailable, ratFindings)
            TextButton(onClick = { expanded = false }) { Text("Hide") }
        } else {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "What apps can do to you",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${summary.appsWithAnyAccess} apps hold screen control, " +
                            "notification access, device admin, overlay, usage " +
                            "access, all-files access or the ability to install apps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { expanded = true }) { Text("Show") }
                }
            }
        }
    }
}

/** One pass of the audit, so the screen sets its state from a single value. */
private data class AuditOutcome(
    val apps: List<AppAccess>,
    val summary: AuditSummary,
    val unavailable: List<String>,
    val wirelessDebuggingOn: Boolean,
    val rat: List<RatFinding>,
)

/**
 * The offer to close the door setting Bulwark up opened.
 *
 * Last on the screen deliberately - it is what you do when finished, not
 * something to trip over on the way in.
 *
 * The two actions are drawn the same way on purpose, except for their labels.
 * One is performed by Bulwark and one is a signpost to a switch only the user
 * can flip, and the labels are the only honest place to carry that difference:
 * "Stop Shizuku" versus "Open Developer options". A button that reads like it
 * did the thing, and did not, is the false sense of protection
 * `safety-rules.md` calls worse than none.
 */
@Composable
private fun DoneForNowCard(
    closeable: List<Closeable>,
    onOpenShizuku: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    Card(Modifier.padding(top = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            doneForNowHeadline(closeable)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            closeable.forEach { item ->
                Text(item.what, style = MaterialTheme.typography.bodySmall)
                // Never folded away behind a "more" link. The cost is the half
                // people skip, so it sits in the same block as the button.
                Text(item.cost, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = when (item.action) {
                        CloseAction.OPEN_SHIZUKU -> onOpenShizuku
                        CloseAction.TURN_OFF_WIRELESS_DEBUGGING -> onOpenDeveloperOptions
                    },
                ) { Text(item.label) }
            }
        }
    }
}

@Composable
private fun InterruptedCard(packages: List<String>) {
    // safety-rules.md rule 6: on ambiguity, stop and report. Bulwark does not
    // know whether these applied and will not guess, so it says exactly that
    // rather than quietly "fixing" a change that may never have happened.
    Card(colors = CardDefaults.cardColors(containerColor = CautionBackground)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Bulwark was interrupted",
                style = MaterialTheme.typography.titleSmall,
                color = CautionText,
            )
            Text(
                "It was part-way through changing ${packages.size} app(s) and " +
                    "never recorded finishing. They may or may not have applied " +
                    "- Bulwark does not know, and will not guess. Check each one:",
                style = MaterialTheme.typography.bodySmall,
                color = CautionText,
            )
            packages.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = CautionText,
                )
            }
        }
    }
}

/**
 * How long the screen waits for the tunnel before believing it is not coming.
 *
 * Ten checks over three seconds. Long enough for a service start, a log read and
 * an `establish()` on a slow phone; short enough that a genuinely absent tunnel
 * is reported promptly rather than hidden behind a spinner.
 */
private const val TUNNEL_CHECKS = 10
private const val TUNNEL_CHECK_MS = 300L
