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
import com.bulwark.app.firewall.Firewall
import com.bulwark.app.firewall.FirewallState
import com.bulwark.app.firewall.firewallDetail
import com.bulwark.app.firewall.firewallHeadline
import com.bulwark.app.firewall.firewallState
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.AuditSummary
import com.bulwark.app.permissions.HiddenSwitch
import com.bulwark.app.permissions.HiddenSwitchHolder
import com.bulwark.app.security.AddedCertificates
import com.bulwark.app.security.InstalledApp
import com.bulwark.app.security.MONITORING_SAFETY_NOTE
import com.bulwark.app.security.MONITORING_UNREADABLE
import com.bulwark.app.security.MonitoringFinding
import com.bulwark.app.security.MonitoringIndicators
import com.bulwark.app.security.ambiguityNote
import com.bulwark.app.security.monitoringDetail
import com.bulwark.app.security.monitoringFooter
import com.bulwark.app.security.monitoringHeadline
import com.bulwark.app.security.recognisedBy
import com.bulwark.app.permissions.hiddenSwitchDetail
import com.bulwark.app.permissions.hiddenSwitchHeadline
import com.bulwark.app.shizuku.AppOpsAccess
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
 * **No defaults, deliberately.** Sixteen values reach this screen from four
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
    /** Which access is mid-change, so its control is not pressed twice. */
    val revoking: Pair<String, Access>?,
    /** Apps holding an op Android gives no screen for. Null until the read lands. */
    val hiddenSwitches: List<HiddenSwitchHolder>?,
    /** Why the hidden-switch read produced nothing usable. */
    val hiddenSwitchesCouldNotTell: String?,
    /** Which hidden switch is mid-change. */
    val revokingSwitch: Pair<String, HiddenSwitch>?,
    /**
     * Certificate authorities somebody added. Null until the read lands;
     * **empty is a real answer here**, and a good one.
     */
    val addedCertificates: List<AddedCertificates.Added>?,
    /**
     * Installed apps matching a known monitoring tool. **Null means Bulwark
     * has not checked** - never "nothing found". Empty means it checked and
     * matched none, which is a different sentence.
     */
    val monitoring: List<MonitoringFinding>?,
    /** True once a check has run, so the footer can distinguish the two. */
    val monitoringChecked: Boolean,
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
)

/**
 * What is true about this phone: special access, RAT signals, permissions
 * across apps, and the firewall's real state.
 *
 * `_shared/design.md` rule 1 - one screen, one question. This one answers
 * "what can the software on this phone do to me", and nothing here changes a
 * package; that is the Apps screen's job.
 *
 * **What apps actually *did* moved to `ActivityScreen` on 2026-09-13.** This
 * screen had quietly grown a second question - capability here, behaviour
 * there - and two questions on one screen is a feed. The Doze and app-op cards
 * went with it.
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
    var revoking by remember { mutableStateOf<Pair<String, Access>?>(null) }
    var addedCertificates by remember { mutableStateOf<List<AddedCertificates.Added>?>(null) }
    var monitoring by remember { mutableStateOf<List<MonitoringFinding>?>(null) }
    var monitoringChecked by remember { mutableStateOf(false) }
    var hiddenSwitches by remember { mutableStateOf<List<HiddenSwitchHolder>?>(null) }
    var hiddenSwitchesCouldNotTell by remember { mutableStateOf<String?>(null) }
    var revokingSwitch by remember { mutableStateOf<Pair<String, HiddenSwitch>?>(null) }
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

    // The trust store. **Not gated on Shizuku**, and that is the point of it:
    // `AndroidCAStore` is readable by any app, so this is the one audit that
    // works on first launch before anybody has been asked to set anything up.
    // Still off the main thread - it parses every certificate on the phone.
    LaunchedEffect(reload) {
        addedCertificates = withContext(Dispatchers.IO) {
            runCatching { AddedCertificates.read() }.getOrNull()
        }
    }

    // Apps matching a known monitoring tool. **Needs Shizuku**, unlike the
    // certificate card: it enumerates every package and reads a signing
    // certificate for each, and neither is reachable without privilege.
    //
    // `monitoring` stays null unless a check actually completed. An empty list
    // and a failed read render as different sentences, and getting that
    // backwards turns "could not check" into "nothing found".
    LaunchedEffect(ready, reload) {
        if (!ready) {
            monitoring = null
            monitoringChecked = false
            return@LaunchedEffect
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val list = MonitoringIndicators.load(context)
                    ?: error("the indicator list could not be read")
                val certificates = PrivilegedPackages.signingCertificates()
                val installed = PrivilegedPackages.listDetailed().map {
                    InstalledApp(it.packageName, certificates[it.packageName])
                }
                list.match(installed)
            }
        }
        monitoring = outcome.getOrNull()
        monitoringChecked = outcome.isSuccess
    }

    // The switches Android gives no screen for. Needs Shizuku: these are app
    // ops, and reading who holds one is the same privileged call as the rest.
    LaunchedEffect(ready, reload) {
        if (!ready) {
            hiddenSwitches = null
            hiddenSwitchesCouldNotTell = null
            return@LaunchedEffect
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val byPackage = AppOpsAccess.holdersOfOps(HiddenSwitch.allOps)
                val installed = runCatching { PrivilegedPackages.listDetailed() }.getOrNull()
                val known = installed?.map { it.packageName }?.toSet()
                val systemNames = installed?.filter { it.isSystem }?.map { it.packageName }?.toSet()
                byPackage.map { (pkg, ops) ->
                    HiddenSwitchHolder(
                        packageName = pkg,
                        switches = ops.mapNotNull { HiddenSwitch.forOp(it) }.toSet(),
                        isSystem = SpecialAccessReader.isSystem(context, pkg, systemNames, known),
                    )
                }.filter { it.switches.isNotEmpty() }.sortedBy { it.packageName }
            }
        }
        hiddenSwitches = outcome.getOrNull()
        hiddenSwitchesCouldNotTell = outcome.exceptionOrNull()?.let {
            "Bulwark could not read which apps hold these: ${it.message}"
        }
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

    AuditContent(
        readings = AuditReadings(
            access = access,
            accessSummary = accessSummary,
            accessUnavailable = accessUnavailable,
            ratSignals = ratSignals,
            revoking = revoking,
            hiddenSwitches = hiddenSwitches,
            hiddenSwitchesCouldNotTell = hiddenSwitchesCouldNotTell,
            revokingSwitch = revokingSwitch,
            addedCertificates = addedCertificates,
            monitoring = monitoring,
            monitoringChecked = monitoringChecked,
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
        ),
        onRevokeSwitch = { holder, switch ->
            revokingSwitch = holder.packageName to switch
            runner.revokeAppOp(
                holder.packageName, switch.opName, switch.shortLabel, switch.plainMeaning,
            ) { outcome ->
                revokingSwitch = null
                report(outcome)
                reload++
            }
        },
        onRevokeAccess = { app, access ->
            revoking = app.packageName to access
            runner.revokeSpecialAccess(app.packageName, access) { outcome ->
                revoking = null
                report(outcome)
                // Re-read rather than assume. The card must show what the phone
                // says, not what was asked for - a revoke the platform ignored
                // has to come back looking ignored.
                reload++
            }
        },
        onOpenVpnSettings = { runCatching { context.startActivity(Firewall.vpnSettings()) } },
        // Two intents, most specific first. The trusted-credentials screen is
        // the one worth landing on, but its action is Settings' own and not
        // framework API, so an OEM may not answer it; `ACTION_SECURITY_SETTINGS`
        // is public and always does. Falling back beats a dead button, and
        // saying where to look beats both if neither opens.
        onOpenCertificateSettings = {
            val direct = Intent("com.android.settings.TRUSTED_CREDENTIALS_USER")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(direct) }
                .recoverCatching { context.startActivity(fallback) }
                .onFailure {
                    report(
                        ActionRunner.Outcome.Failed(
                            "Bulwark could not open that screen on this phone. " +
                                "It is under Settings, Security, Encryption & " +
                                "credentials, Trusted credentials, User.",
                        ),
                    )
                }
        },
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
    onRevokeAccess: (AppAccess, Access) -> Unit,
    onRevokeSwitch: (HiddenSwitchHolder, HiddenSwitch) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenCertificateSettings: () -> Unit,
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
            // and the permissions card below says "Who holds each permission"
            // - which it did not until 2026-09-14, when it read "What apps can
            // do" and the two were indistinguishable at a glance. design.md
            // calls this screen's job "what is true about this phone", so the
            // heading says that and each card says its own question.
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
                        CollapsibleAudit(
                            a, s, readings.accessUnavailable, readings.ratSignals,
                            onRevokeAccess, readings.revoking,
                        )
                    } else {
                        Text(
                            "Checking what apps can do to you…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                // **First on the screen when it exists, and absent otherwise.**
                // A card that appears only on a finding means a clean phone
                // shows nothing here at all - no permanently visible label for
                // somebody to read over a shoulder, which is why SAI chose this
                // over a fifth tab. The cost is that absence reads as
                // reassurance, and the footer at the bottom pays it.
                if (readings.monitoring?.isNotEmpty() == true) {
                    item(key = "monitoring") {
                        MonitoringCard(readings.monitoring)
                    }
                }

                // **Above special access**, which is the only card that
                // outranks it, because this is the one audit that does not need
                // Shizuku: on first launch it is the only thing on this screen
                // with an answer, and a screen whose top two cards both say
                // "start Shizuku" teaches people the screen is empty.
                //
                // Slot emitted from first composition, like every card here.
                item(key = "added-certificates") {
                    AddedCertificateCard(
                        added = readings.addedCertificates,
                        onOpenSettings = onOpenCertificateSettings,
                    )
                }

                // Below special access on purpose. That card names rare,
                // dangerous capabilities and has to stay short enough to read;
                // this one is held by most apps on the phone, so putting it
                // above would bury the signal under the ordinary.
                //
                // Slot emitted from the first composition, like every card here.
                item(key = "hidden-switches") {
                    HiddenSwitchCard(
                        holders = readings.hiddenSwitches,
                        couldNotTell = readings.hiddenSwitchesCouldNotTell,
                        shizukuReady = readings.shizukuReady,
                        busy = readings.revokingSwitch,
                        onRevoke = onRevokeSwitch,
                    )
                }

                // The quiet half of the monitoring check, and the reason the
                // card above may be absent without lying. Bottom of the screen,
                // small type, no heading: a person who wants to know what was
                // checked finds it, and nobody else is alarmed by it.
                item(key = "monitoring-footer") {
                    Text(
                        when {
                            !readings.shizukuReady ->
                                "Shizuku is not running, so Bulwark has not checked " +
                                    "this phone against known monitoring tools."
                            readings.monitoring == null && readings.monitoringChecked -> MONITORING_UNREADABLE
                            readings.monitoring == null -> "Checking this phone against known monitoring tools…"
                            else -> monitoringFooter()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
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
 * The switches Android does not give you.
 *
 * The part of this screen that no other app can show, and the reason the
 * copy leads with "Android has no setting for this" rather than with a count.
 *
 * Capped at first sight like the Activity cards - most apps hold these, so the
 * list is long by nature and a wall of rows informs nobody - but **the cap is a
 * default, not a ceiling.** Every app holding a switch is reachable in one tap,
 * because a count somebody cannot act on is a report and this card is meant to
 * be a control.
 */
/**
 * Certificate authorities somebody added, and the screen that can remove them.
 *
 * **No action button, on purpose.** Bulwark cannot remove one of these and
 * neither can Shizuku - `/data/misc/user/0/cacerts-added/` refuses even a
 * listing at uid 2000. So the control here opens Android's own screen, and its
 * label says that is what it does rather than implying Bulwark will act.
 */
/**
 * Apps matching a known monitoring tool.
 *
 * **Report only.** No control here removes anything, and that is not an
 * oversight: `threat-model.md` forbids presenting removal as the obvious next
 * step, because for the person this matters most to, the moment of discovery is
 * the dangerous one. Everything Bulwark can do to an app it can already do from
 * the Apps screen, deliberately chosen rather than offered here in the second
 * after a shock.
 *
 * The safety note sits **before** the rows, not under them, for the same
 * reason the export warning precedes the save dialog: a warning that arrives
 * after the decision is a receipt.
 */
@Composable
private fun MonitoringCard(findings: List<MonitoringFinding>) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Apps that match a known monitoring tool",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(monitoringHeadline(findings), style = MaterialTheme.typography.bodyMedium)
            monitoringDetail(findings)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                MONITORING_SAFETY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = CautionText,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            findings.forEach { finding ->
                Text(
                    finding.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Listed as ${finding.names.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(finding.recognisedBy(), style = MaterialTheme.typography.bodySmall)
                finding.ambiguityNote()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AddedCertificateCard(
    added: List<AddedCertificates.Added>?,
    onOpenSettings: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Certificates someone added",
                style = MaterialTheme.typography.titleMedium,
            )

            if (added == null) {
                Text("Checking…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            Text(
                AddedCertificates.headline(added),
                style = MaterialTheme.typography.bodyMedium,
            )
            AddedCertificates.detail(added)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            added.forEach { cert ->
                Text(
                    cert.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    AddedCertificates.line(cert, now),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Only where there is something to act on. An empty store needs no
            // signpost - design.md rule 5, and offering one would imply there
            // is something to go and look at.
            if (added.isNotEmpty()) {
                TextButton(onClick = onOpenSettings) { Text("Open certificate settings") }
            }
        }
    }
}

@Composable
private fun HiddenSwitchCard(
    holders: List<HiddenSwitchHolder>?,
    couldNotTell: String?,
    shizukuReady: Boolean,
    busy: Pair<String, HiddenSwitch>?,
    onRevoke: (HiddenSwitchHolder, HiddenSwitch) -> Unit,
) {
    // Collapsed by default and saved across rotation, the same way
    // `CollapsibleAudit` above does it - one idiom on this screen, not two.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Switches Android does not give you",
                style = MaterialTheme.typography.titleMedium,
            )

            when {
                !shizukuReady -> Text(
                    "Shizuku is not running. These are app ops, and reading who " +
                        "holds one needs the access Shizuku provides.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                holders == null && couldNotTell != null -> Text(
                    couldNotTell,
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )

                holders == null -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        hiddenSwitchHeadline(holders),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    hiddenSwitchDetail(holders)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    val shown = if (expanded) holders else holders.take(HIDDEN_SWITCH_ROWS)
                    shown.forEach { holder ->
                        Text(
                            holder.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        holder.switches.sortedBy { it.name }.forEach { switch ->
                            Text(
                                "• ${switch.plainMeaning}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val isBusy = busy == holder.packageName to switch
                            TextButton(
                                enabled = !isBusy,
                                onClick = { onRevoke(holder, switch) },
                            ) { Text(if (isBusy) "Taking it away…" else "Take this away") }
                        }
                    }
                    // The remainder used to be *stated* here and left there:
                    // "and 56 more apps", with no way to reach one of them. On
                    // the Agni 2 that was 56 of 64 apps counted and not
                    // actionable, which makes this card a report when its whole
                    // reason to exist is being a control.
                    if (holders.size > HIDDEN_SWITCH_ROWS) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) "Show fewer"
                                else "Show all ${holders.size} apps",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A screenful, and where "Show all N apps" appears. Never a ceiling. */
private const val HIDDEN_SWITCH_ROWS = 8

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
    onRevokeAccess: (AppAccess, Access) -> Unit,
    revoking: Pair<String, Access>?,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column {
        if (expanded) {
            SpecialAccessSection(
                apps, summary, unavailable, ratFindings,
                onRevoke = onRevokeAccess,
                busy = revoking,
            )
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
