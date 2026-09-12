package com.bulwark.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bulwark.app.ui.theme.CautionBackground
import com.bulwark.app.ui.theme.CautionText
import com.bulwark.app.ui.theme.Incomplete
import com.bulwark.app.ui.theme.RatingAdvanced
import com.bulwark.app.ui.theme.RatingExpert
import com.bulwark.app.ui.theme.RatingRecommended
import com.bulwark.app.ui.theme.RatingUnsafe
import com.bulwark.app.ui.theme.Refused
import com.bulwark.app.ui.theme.WorthLookingAt
import com.bulwark.app.debloat.CatalogEntry
import com.bulwark.app.debloat.PackageCatalog
import com.bulwark.app.debloat.RemovalRating
import com.bulwark.app.debloat.UadDatabase
import com.bulwark.app.debloat.readableDescription
import com.bulwark.app.policy.ActionRecord
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.AuditSummary
import com.bulwark.app.permissions.RatFinding
import com.bulwark.app.permissions.ratFindings
import com.bulwark.app.firewall.Firewall
import com.bulwark.app.firewall.FirewallState
import com.bulwark.app.firewall.firewallDetail
import com.bulwark.app.firewall.firewallHeadline
import com.bulwark.app.firewall.firewallState
import com.bulwark.app.permissions.PermissionAcrossApps
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.groupByPermission
import com.bulwark.app.permissions.permissionAuditNotice
import com.bulwark.app.permissions.permissionAuditState
import com.bulwark.app.shizuku.RuntimePermissionAccess
import com.bulwark.app.permissions.summarise
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.SpecialAccessReader
import com.bulwark.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layer 1's real screen: everything installed, and what Bulwark will say about
 * each of it.
 *
 * **Switch off is live; uninstall is not.** Its undo was written and tested
 * before it shipped (`context/_shared/safety-rules.md` rule 3) and it passes
 * through `DestructiveActionGuard`, so the system authenticates outside our
 * process before anything changes.
 *
 * **One action per row, chosen by current state.** Never two side by side:
 * hardware testing on 2026-09-10 found that offering "Switch off" and "Undo"
 * together meant a second press of Undo performed a *disable* under a label
 * promising the opposite. A button must say what it does.
 *
 * **No bulk selection, deliberately.** `safety-rules.md` rule 1 - forty
 * changes at once means nobody can tell which one broke the phone.
 *
 * Beyond the actions, the screen's job is honesty: what is installed, what it
 * does in the community's own words rather than the first line of them, and
 * which parts Bulwark refuses to touch and why.
 */
@Composable
fun PackageListScreen(
    state: ShizukuState,
    runner: ActionRunner,
    exporter: LogExporter,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var summary by remember { mutableStateOf<PackageCatalog.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyOffered by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf<List<AppAccess>?>(null) }
    var accessSummary by remember { mutableStateOf<AuditSummary?>(null) }
    var accessUnavailable by remember { mutableStateOf<List<String>>(emptyList()) }
    var ratSignals by remember { mutableStateOf<List<RatFinding>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    // Read off the main thread, below. These were `remember { }` blocks in the
    // composable body, which put two SQLite reads on the main thread on every
    // refresh - invisible on a fast phone and exactly the kind of thing that
    // makes a cheap one stutter.
    var interrupted by remember { mutableStateOf<List<ActionRecord>>(emptyList()) }
    // Null means no read has produced a list, which is NOT the same as a read
    // that produced an empty one. The screen says something different for each.
    var permissionGroups by remember { mutableStateOf<List<PermissionAcrossApps>?>(null) }
    var permissionsReadFailed by remember { mutableStateOf(false) }
    var blockedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var firewallConsentNeeded by remember { mutableStateOf(false) }
    var aVpnIsUp by remember { mutableStateOf(false) }
    var alwaysOn by remember { mutableStateOf(false) }
    var lockdown by remember { mutableStateOf(false) }
    // Null means "could not tell", never "none" - see originLabelFor.
    var systemPackages by remember { mutableStateOf<Set<String>?>(null) }
    // Capabilities whose flag read has come back. Until a permission is in
    // here, its rows are still being checked and no control is drawn.
    var refinedPermissions by remember { mutableStateOf(emptySet<String>()) }

    fun report(outcome: ActionRunner.Outcome) {
        val message = when (outcome) {
            is ActionRunner.Outcome.Done -> outcome.message
            is ActionRunner.Outcome.Refused -> outcome.why
            // Already written for a person by ActionRunner; no prefix.
            is ActionRunner.Outcome.Failed -> outcome.why
            ActionRunner.Outcome.Cancelled -> null
        }
        // A snackbar, not a card in the list. Hardware showed why: the outcome
        // rendered as a list item appeared wherever that item sits, which after
        // tapping "Put everything back" was off the top of the screen - and for
        // a row action deep in 370 packages it would be hundreds of rows away.
        // A result the user has to go looking for is not a result.
        //
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

    val ready = state is ShizukuState.Ready

    val shown = entries.orEmpty().filter { e ->
        (!onlyOffered || e.isOffered) &&
            (query.isBlank() || e.packageName.contains(query, ignoreCase = true))
    }
    val shownCount = shown.size
    var exportable by remember { mutableStateOf(false) }

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
            val systemPackages = installed?.filter { it.isSystem }?.map { it.packageName }?.toSet()
            val apps = result.holders.map { (pkg, accesses) ->
                AppAccess(
                    pkg, accesses,
                    isSystem = SpecialAccessReader.isSystem(context, pkg, systemPackages, known),
                )
            }
            val audited = apps.audit()
            AuditOutcome(
                audited,
                apps.summarise(),
                result.unavailable,
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
        ratSignals = built.rat
    }

    // The log is a database. Both of these read it, so both belong here rather
    // than in the composable body.
    LaunchedEffect(reload) {
        val log = withContext(Dispatchers.IO) {
            runCatching { runner.interrupted() }.getOrDefault(emptyList()) to
                runCatching { exporter.hasAnything() }.getOrDefault(false)
        }
        interrupted = log.first
        exportable = log.second
    }

    LaunchedEffect(ready, reload) {
        if (!ready) return@LaunchedEffect
        try {
            val built = withContext(Dispatchers.IO) {
                val installed = PrivilegedPackages.listDetailed()
                val catalog = PackageCatalog(UadDatabase.load(context))
                val list = catalog.build(
                    installed.map { it.packageName },
                    installed.filter { it.isSystem }.map { it.packageName }.toSet(),
                    installed.filterNot { it.isEnabled }.map { it.packageName }.toSet(),
                )
                list to catalog.summarise(list)
            }
            entries = built.first
            summary = built.second
            error = null
        } catch (t: Throwable) {
            error = "${t::class.java.simpleName}: ${t.message}"
        }
    }

    // The firewall's state, read from the platform every time rather than
    // remembered. Every interesting case is one where a remembered value would
    // be wrong: the service was killed, consent was withdrawn, another VPN
    // replaced ours, or the phone restarted and nothing has started yet.
    //
    // No Shizuku here on purpose - this is the layer that must work without it.
    LaunchedEffect(reload) {
        val rules = withContext(Dispatchers.IO) {
            runCatching { runner.blockedNetworkApps() }.getOrDefault(emptySet())
        }
        blockedApps = rules
        firewallConsentNeeded = Firewall.needsConsent(context)
        alwaysOn = Firewall.alwaysOnHoldsOurTunnel(context)
        lockdown = Firewall.lockdownIsOn(context)
        aVpnIsUp = Firewall.ourTunnelIsUp()

        if (rules.isEmpty()) return@LaunchedEffect
        runner.syncFirewall()

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

    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Your phone",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )

        // EVERYTHING scrolls, including the audit. It used to sit in a fixed
        // header above a LazyColumn, so an audit with a dozen findings squeezed
        // the package list to nothing - and worse, the audit itself could not
        // be scrolled, so its own later rows were unreachable. A header that
        // grows with the data is not a header.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // Shown whether or not Shizuku is up. Three of four sources need no
            // privilege, so this is what Bulwark can say on first launch -
            // before asking anyone to do anything difficult.
            access?.let { a ->
                accessSummary?.let { s ->
                    item(key = "access") {
                        CollapsibleAudit(a, s, accessUnavailable, ratSignals)
                    }
                }
            }

            // Shown whenever there is anything to say, which is whenever a
            // rule exists. A firewall that is not working must announce that
            // where it is looked at, not in a settings page.
            if (blockedApps.isNotEmpty()) {
                item(key = "firewall") {
                    FirewallCard(
                        ruleCount = blockedApps.size,
                        consentNeeded = firewallConsentNeeded,
                        vpnUp = aVpnIsUp,
                        alwaysOn = alwaysOn,
                        lockdown = lockdown,
                        onOpenVpnSettings = {
                            runCatching { context.startActivity(Firewall.vpnSettings()) }
                        },
                        onAllow = { runner.requestVpnConsent() },
                    )
                }
            }

            // The cross-app permission view, below the special-access audit
            // because that one names the more dangerous accesses and needs no
            // privilege to do it.
            //
            // **Always rendered.** It used to appear only when it had data, so
            // the three different silences - no Shizuku, read failed, genuinely
            // nothing - all looked identical to a section that had vanished.
            item(key = "permissions") {
                val auditState = permissionAuditState(
                    shizukuReady = ready,
                    readFailed = permissionsReadFailed,
                    groups = permissionGroups,
                )
                    PermissionAuditSection(
                        groups = permissionGroups.orEmpty(),
                        notice = permissionAuditNotice(auditState),
                        systemPackages = systemPackages,
                        refined = refinedPermissions,
                        onOpen = { permission ->
                            // The expensive half, paid only for a capability
                            // someone actually opened.
                            if (permission !in refinedPermissions) scope.launch {
                                val refreshed = withContext(Dispatchers.IO) {
                                    val group = permissionGroups
                                        ?.firstOrNull { it.permission == permission }
                                        ?: return@withContext null
                                    RuntimePermissionAccess.refineWithFlags(group.holders)
                                }
                                if (refreshed != null) {
                                    permissionGroups = permissionGroups?.map {
                                        if (it.permission == permission) {
                                            it.copy(holders = refreshed)
                                        } else {
                                            it
                                        }
                                    }
                                    refinedPermissions = refinedPermissions + permission
                                }
                            }
                        },
                        onRevoke = { permission, packages ->
                            runner.revokeAcrossApps(permission, packages) { outcome ->
                                report(outcome)
                                // Re-read rather than assume: the screen must
                                // show what the phone says, not what we asked
                                // for. A revoke the platform ignored has to
                                // come back looking ignored.
                                reload++
                            }
                        },
                    )
            }

            if (interrupted.isNotEmpty()) {
                item(key = "interrupted") {
                    InterruptedCard(interrupted.map { it.packageName })
                }
            }

            if (!ready) {
                item(key = "no-shizuku") {
                    Text(
                        "Start Shizuku to see every package. Without it Bulwark " +
                            "can only see about half of what is installed - and " +
                            "the half it cannot see is the preinstalled software.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                return@LazyColumn
            }

            error?.let { message ->
                item(key = "error") {
                    Card {
                        Text(
                            "Could not read packages. " + message,
                            Modifier.padding(14.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            if (entries == null) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                return@LazyColumn
            }

            if (exportable) {
                // The one bulk operation Bulwark has, and only because it is a
                // restore - see safety-rules.md rule 1, amended 2026-09-11.
                // Offered next to the export because they answer the same
                // question: what did this app do to my phone, and can I take
                // it back?
                item(key = "restore") {
                    TextButton(onClick = {
                        runner.restoreEverything { report(it) }
                    }) { Text("Put everything back") }
                }
            }

            if (exportable) {
                // Rule 5: the record must be exportable in fact, not only in
                // principle. Offered once there is something to export.
                item(key = "export") {
                    Column {
                        TextButton(onClick = {
                            exporter.export { outcome ->
                                val text = when (outcome) {
                                    is LogExporter.Outcome.Saved ->
                                        "Saved to ${outcome.where}."
                                    is LogExporter.Outcome.Failed ->
                                        "Could not save. ${outcome.why}"
                                    LogExporter.Outcome.Cancelled -> null
                                }
                                text?.let { t ->
                                    val bad = outcome is LogExporter.Outcome.Failed
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            message = t,
                                            withDismissAction = bad,
                                            duration = if (bad) {
                                                SnackbarDuration.Indefinite
                                            } else {
                                                SnackbarDuration.Long
                                            },
                                        )
                                    }
                                }
                            }
                        }) { Text("Export what Bulwark changed") }
                        // Warned BEFORE the picker, not after the file exists.
                        // threat-model.md: for someone who has just switched off
                        // monitoring software, a file naming it — sitting in
                        // Downloads on a phone another person can reach — is the
                        // discovery risk the whole detection section is about.
                        // A warning after the save has already happened is not a
                        // warning, it is a receipt.
                        Text(
                            "This file lists every app you changed, and it stays " +
                                "wherever you save it. If someone else can reach " +
                                "this phone, choose somewhere they cannot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CautionText,
                        )
                    }
                }
            }

            summary?.let { s -> item(key = "summary") { SummaryCard(s) } }

            item(key = "filters") {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    FilterChip(
                        selected = onlyOffered,
                        onClick = { onlyOffered = !onlyOffered },
                        label = { Text("Only what I can change") },
                    )
                    Text(
                        "$shownCount shown",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            items(shown, key = { it.packageName }) { entry ->
                PackageRow(entry, runner, entry.packageName in blockedApps, ::report)
            }
        }
      }

      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
    alwaysOn: Boolean,
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
                state != FirewallState.NOTHING_BLOCKED && !alwaysOn ->
                    TextButton(onClick = onOpenVpnSettings) { Text("Open VPN settings") }
            }
        }
    }
}

/**
 * The audit, collapsible.
 *
 * Expanded by default - being seen is the entire point - but foldable, because
 * a phone with thirty findings would otherwise bury the package list under
 * them. Collapsed it still states the count, so folding it away never hides
 * that there is something there.
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

@Composable
private fun SummaryCard(s: PackageCatalog.Summary) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${s.total} packages installed", style = MaterialTheme.typography.titleMedium)
            Text("${s.offered} you can switch off — reversible, data kept")
            // Says what is KNOWN, not what is offered. Bulwark cannot uninstall
            // yet, and a count that reads as an offer is a promise the app does
            // not keep. "Safe" was our word too; the rating belongs to the
            // community database, so say whose it is.
            Text("${s.uninstallable} the database also rates removable — Bulwark cannot uninstall yet")
            Text("${s.refused} Bulwark refuses — they break your way back")
            Text("${s.unknown} nobody has documented — still offered, and shown as unknown")
            Text(
                "Package data snapshot ${UadDatabase.SNAPSHOT}, from the Universal " +
                    "Debloater Alliance. Bundled, not downloaded — Bulwark makes " +
                    "no network calls.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** One pass of the audit, so the screen sets its state from a single value. */
private data class AuditOutcome(
    val apps: List<AppAccess>,
    val summary: AuditSummary,
    val unavailable: List<String>,
    val rat: List<RatFinding>,
)

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

@Composable
private fun PackageRow(
    entry: CatalogEntry,
    runner: ActionRunner,
    isBlocked: Boolean,
    onOutcome: (ActionRunner.Outcome) -> Unit,
) {
    var busy by remember(entry.packageName) { mutableStateOf(false) }

    // The per-app permission view, folded away until asked for. Closed by
    // default because reading one app's permissions costs a binder call per
    // permission, and a list of 371 rows must not pay that for all of them.
    val context = LocalContext.current
    // rememberSaveable, not remember: a row scrolled out of a LazyColumn leaves
    // composition, and plain remember would quietly fold the section back up
    // while the user was still reading the list. Only the toggle is saved -
    // the holdings are re-read, because what the phone says may have changed
    // in between and a stale list is worse than a brief "Reading...".
    var showPermissions by rememberSaveable(entry.packageName) { mutableStateOf(false) }
    var holdings by remember(entry.packageName) { mutableStateOf<List<PermissionHolding>?>(null) }
    var permissionFailure by remember(entry.packageName) { mutableStateOf<String?>(null) }
    var busyPermission by remember(entry.packageName) { mutableStateOf<String?>(null) }
    var permissionReload by remember(entry.packageName) { mutableIntStateOf(0) }

    LaunchedEffect(showPermissions, permissionReload) {
        if (!showPermissions) return@LaunchedEffect
        holdings = null
        permissionFailure = null
        runCatching {
            withContext(Dispatchers.IO) {
                RuntimePermissionAccess.forApp(
                    entry.packageName, entry.isSystem, context.packageManager,
                )
            }
        }.fold(
            onSuccess = { holdings = it },
            // Reported, never swallowed into an empty list: "could not read"
            // and "holds nothing" are different facts about someone's phone.
            onFailure = { permissionFailure = "${it::class.java.simpleName}: ${it.message}" },
        )
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(entry)
                Text(
                    entry.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Every line, not the first. The lines after the first are the
            // ones that say what breaks - see UadDatabase.readableDescription.
            entry.description?.readableDescription()?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            entry.options.refusal?.let {
                Reason("Bulwark will not touch this. $it")
            }
            entry.options.warning?.let { Reason(it) }

            if (!entry.options.isRefused) {
                // What Bulwark can actually do, kept separate from what the
                // database merely rates. Offering "or uninstall it" for an
                // action that does not exist is guiding someone with words
                // the app cannot back up.
                Reason(
                    "You can switch it off. It stays installed, keeps its data, " +
                        "and you can switch it back on here."
                )
                if (entry.options.canUninstall) {
                    Reason(
                        "The database also rates this removable entirely. " +
                            "Bulwark cannot uninstall yet."
                    )
                }
            }

            // One app, one decision, same as everything else here. Blocking
            // is offered for any app the guards allow - including ones Bulwark
            // will not disable, because cutting an app off is a smaller act
            // than switching it off and the never-remove list is applied
            // separately in FirewallActions.
            if (isBlocked) {
                Reason("Blocked from the internet by Bulwark.")
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        runner.allowNetwork(entry.packageName) {
                            busy = false
                            onOutcome(it)
                        }
                    },
                ) { Text("Allow online") }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        runner.blockNetwork(entry.packageName) {
                            busy = false
                            onOutcome(it)
                        }
                    },
                ) { Text("Block internet") }
            }

            TextButton(onClick = { showPermissions = !showPermissions }) {
                Text(if (showPermissions) "Hide what it can do" else "What it can do")
            }

            if (showPermissions) {
                AppPermissionsSection(
                    packageName = entry.packageName,
                    holdings = holdings,
                    failure = permissionFailure,
                    busyPermission = busyPermission,
                    onRevoke = { permission ->
                        busyPermission = permission
                        runner.revokePermission(entry.packageName, permission) { outcome ->
                            busyPermission = null
                            onOutcome(outcome)
                            // Re-read rather than assume. The screen must show
                            // what the phone says, not what was asked for - a
                            // revoke the platform ignored has to come back
                            // looking ignored.
                            permissionReload++
                        }
                    },
                )
            }

            if (entry.neededByInstalled.isNotEmpty()) {
                Reason(
                    "Other apps you have installed depend on this: " +
                        entry.neededByInstalled.joinToString(", ")
                )
            }

            // One package at a time, deliberately. There is no "select all":
            // safety-rules.md rule 1 forbids it, because forty changes at once
            // means nobody can tell which one broke the phone.
            //
            // And exactly ONE action per row, chosen by current state. The
            // first version offered "Switch off" and "Undo" side by side;
            // hardware testing on 2026-09-10 showed a second press of Undo
            // undoes the *undo*, performing a disable under a label that
            // promises the opposite. A button must say what it does.
            if (entry.options.canDisable) {
                if (entry.isEnabled) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            runner.disable(entry.packageName, entry.packageName) {
                                busy = false
                                onOutcome(it)
                            }
                        },
                    ) { Text("Switch off") }
                } else {
                    Text(
                        "Switched off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            runner.switchBackOn(entry.packageName, entry.packageName) {
                                busy = false
                                onOutcome(it)
                            }
                        },
                    ) { Text("Switch back on") }
                }
            }
        }
    }
}

@Composable
private fun Reason(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Badge(entry: CatalogEntry) {
    val (label, colour) = when {
        entry.options.isRefused -> "LOCKED" to Refused
        // UAD's own word, not ours. "SAFE" was Bulwark promising nothing
        // would change; Recommended only means most people can remove it.
        // com.google.android.as.oss is rated Recommended and is a dependency
        // of System Intelligence - both true at once.
        entry.rating == RemovalRating.RECOMMENDED -> "RECOMMENDED" to RatingRecommended
        entry.rating == RemovalRating.ADVANCED -> "CARE" to RatingAdvanced
        entry.rating == RemovalRating.EXPERT -> "EXPERT" to RatingExpert
        entry.rating == RemovalRating.UNSAFE -> "RISKY" to RatingUnsafe
        else -> "UNKNOWN" to Color.Gray
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colour)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
