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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bulwark.app.debloat.CatalogEntry
import com.bulwark.app.debloat.PackageCatalog
import com.bulwark.app.debloat.UadDatabase
import com.bulwark.app.debloat.readableDescription
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.policy.Restorability
import com.bulwark.app.policy.Standing
import com.bulwark.app.policy.badge
import com.bulwark.app.policy.labels
import com.bulwark.app.policy.standingFor
import com.bulwark.app.shizuku.CriticalRoles
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.RuntimePermissionAccess
import com.bulwark.app.shizuku.ShizukuState
import com.bulwark.app.ui.theme.bulwark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the package list renders, as data.
 *
 * **No defaults, deliberately** - same reason as [AuditReadings]. A field that
 * quietly defaults is a card that quietly renders empty.
 */
data class AppsReadings(
    /** Null means no read has finished; empty means the read found nothing. */
    val entries: List<CatalogEntry>?,
    val summary: PackageCatalog.Summary?,
    val error: String?,
    /**
     * What this phone says its critical jobs belong to. Null until it lands,
     * which `standingFor` treats as an unfinished check rather than an
     * all-clear. Needs no privilege - these are ordinary reads, so the labels
     * are right even with Shizuku down.
     */
    val roles: CriticalRoles.Reading?,
    val blockedApps: Set<String>,
    val shizukuReady: Boolean,
)

/**
 * Layer 1's real screen: everything installed, and what Bulwark will say about
 * each of it.
 *
 * `_shared/design.md` rule 1 - one screen, one question. This one answers
 * "find an app and change it". What apps can do *to you* is the Audit screen.
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
 *
 * ## Why this is two composables
 *
 * This one owns the state and the reads; [AppsContent] owns the drawing and
 * takes plain data plus a [RowActions]. That is what lets `AppsContentTest`
 * render the list, the summary and a real row on a device with no Shizuku -
 * the detector `design.md` said this split could not safely happen without.
 */
@Composable
fun AppsScreen(
    state: ShizukuState,
    runner: ActionRunner,
    snackbar: SnackbarHostState,
    query: String,
    onQueryChange: (String) -> Unit,
    onlyOffered: Boolean,
    onOnlyOfferedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var summary by remember { mutableStateOf<PackageCatalog.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Null until it lands, which standingFor treats as an unfinished check
    // rather than an all-clear.
    var roles by remember { mutableStateOf<CriticalRoles.Reading?>(null) }
    var blockedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reload by remember { mutableIntStateOf(0) }

    // An app disabled or uninstalled from Settings while Bulwark was in the
    // background must not still be listed as it was.
    val returns = rememberResumeTicker()

    val ready = state is ShizukuState.Ready

    fun report(outcome: ActionRunner.Outcome) {
        val message = when (outcome) {
            is ActionRunner.Outcome.Done -> outcome.message
            is ActionRunner.Outcome.Refused -> outcome.why
            // Already written for a person by ActionRunner; no prefix.
            is ActionRunner.Outcome.Failed -> outcome.why
            ActionRunner.Outcome.Cancelled -> null
        }
        // A snackbar, not a card in the list. Hardware showed why: the outcome
        // rendered as a list item appeared wherever that item sits, which for a
        // row action deep in 370 packages is hundreds of rows away. A result
        // the user has to go looking for is not a result.
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

    // Queries several system services, so off the main thread like the rest.
    LaunchedEffect(reload, returns) {
        roles = withContext(Dispatchers.IO) {
            runCatching { CriticalRoles.read(context) }.getOrNull()
        }
    }

    LaunchedEffect(ready, reload, returns) {
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

    // Which apps are blocked, for the per-row badge and the row's one action.
    //
    // A plain read, with no `syncFirewall()` beside it: re-applying the rules
    // belongs to opening the app, not to opening a tab, and it happens in
    // MainActivity. Doing it here as well would re-apply on every reload of a
    // screen that has nothing to do with the firewall.
    LaunchedEffect(reload, returns) {
        blockedApps = withContext(Dispatchers.IO) {
            runCatching { runner.blockedNetworkApps() }.getOrDefault(emptySet())
        }
    }

    AppsContent(
        readings = AppsReadings(
            entries = entries,
            summary = summary,
            error = error,
            roles = roles,
            blockedApps = blockedApps,
            shizukuReady = ready,
        ),
        actions = runner,
        query = query,
        onQueryChange = onQueryChange,
        onlyOffered = onlyOffered,
        onOnlyOfferedChange = onOnlyOfferedChange,
        onOutcome = ::report,
        modifier = modifier,
    )
}

/**
 * The package list, drawn from data alone.
 *
 * Every read is already done by the time this runs, and the only way it can
 * change the phone is through [RowActions] - which in production is the
 * `ActionRunner` that authenticates first, and in a test is a fake that
 * records the request and touches nothing.
 */
@Composable
fun AppsContent(
    readings: AppsReadings,
    actions: RowActions,
    query: String,
    onQueryChange: (String) -> Unit,
    onlyOffered: Boolean,
    onOnlyOfferedChange: (Boolean) -> Unit,
    onOutcome: (ActionRunner.Outcome) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val shown = readings.entries.orEmpty().filter { e ->
        (!onlyOffered || e.isOffered) &&
            (query.isBlank() || e.packageName.contains(query, ignoreCase = true))
    }
    val shownCount = shown.size

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Says which of the three this is. "Your phone" was right when
            // there was one screen; with a tab bar naming three, a heading that
            // repeats none of them is a heading that tells you nothing.
            Text(
                "What is on this phone",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {

                if (!readings.shizukuReady) {
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

                readings.error?.let { message ->
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

                if (readings.entries == null) {
                    item(key = "loading") {
                        Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                    return@LazyColumn
                }

                readings.summary?.let { s -> item(key = "summary") { SummaryCard(s) } }

                item(key = "filters") {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            label = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        FilterChip(
                            selected = onlyOffered,
                            onClick = { onOnlyOfferedChange(!onlyOffered) },
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
                    PackageRow(
                        entry,
                        standingFor(
                            entry.packageName, entry.isSystem, readings.roles,
                            context.packageName,
                        ),
                        actions,
                        entry.packageName in readings.blockedApps,
                        onOutcome,
                    )
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
            Text("${s.uninstallable} the database also rates safe to remove entirely")
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

@Composable
private fun PackageRow(
    entry: CatalogEntry,
    standing: Standing,
    actions: RowActions,
    isBlocked: Boolean,
    onOutcome: (ActionRunner.Outcome) -> Unit,
) {
    var busy by remember(entry.packageName) { mutableStateOf(false) }
    // Non-null while the ceremony is open. Keyed per row, so a typed name can
    // never travel from one package to another.
    var ceremonyFor by remember(entry.packageName) { mutableStateOf<Standing?>(null) }

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

    ceremonyFor?.let { target ->
        RemovalCeremony(
            standing = target,
            onDismiss = { ceremonyFor = null },
            onConfirmed = {
                ceremonyFor = null
                busy = true
                actions.uninstall(
                    target.packageName,
                    target.packageName,
                    target.restorability == Restorability.BULWARK_CAN_RESTORE,
                ) {
                    busy = false
                    onOutcome(it)
                }
            },
        )
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StandingBadge(standing)
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
                // Derived from this phone, not from a rating. Says what the
                // package does here and whether Bulwark could put it back.
                standing.labels().forEach { Reason(it) }

                OutlinedButton(
                    enabled = !busy && !standing.refused,
                    onClick = {
                        // The ceremony only stands in front of packages the
                        // phone named. Everything else goes straight to the
                        // system prompt - a gate on all 370 would be friction
                        // that teaches people to tap through the real one.
                        if (standing.needsCeremony) {
                            ceremonyFor = standing
                        } else {
                            busy = true
                            actions.uninstall(
                                entry.packageName,
                                entry.packageName,
                                standing.restorability == Restorability.BULWARK_CAN_RESTORE,
                            ) {
                                busy = false
                                onOutcome(it)
                            }
                        }
                    },
                ) { Text("Remove") }
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
                        actions.allowNetwork(entry.packageName) {
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
                        actions.blockNetwork(entry.packageName) {
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
                        actions.revokePermission(entry.packageName, permission) { outcome ->
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
                            actions.disable(entry.packageName, entry.packageName) {
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
                            actions.switchBackOn(entry.packageName, entry.packageName) {
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
private fun StandingBadge(standing: Standing) {
    val label = standing.badge() ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.bulwark.refused)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Reason(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
