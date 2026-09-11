package com.bulwark.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.summarise
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.SpecialAccessReader
import com.bulwark.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
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
    var notice by remember { mutableStateOf<String?>(null) }
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

    fun report(outcome: ActionRunner.Outcome) {
        notice = when (outcome) {
            is ActionRunner.Outcome.Done -> outcome.message
            is ActionRunner.Outcome.Refused -> outcome.why
            is ActionRunner.Outcome.Failed -> "Did not work. ${outcome.why}"
            ActionRunner.Outcome.Cancelled -> null
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
                ratFindings(result.signals, audited, shizukuRunning = privileged),
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

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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

            if (interrupted.isNotEmpty()) {
                item(key = "interrupted") {
                    InterruptedCard(interrupted.map { it.packageName })
                }
            }

            notice?.let { message ->
                item(key = "notice") { NoticeCard(message) { notice = null } }
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
                    TextButton(onClick = {
                        exporter.export { outcome ->
                            notice = when (outcome) {
                                is LogExporter.Outcome.Saved ->
                                    "Saved to ${outcome.where}. It lists the apps " +
                                        "you changed — check before sharing it."
                                is LogExporter.Outcome.Failed ->
                                    "Could not save. ${outcome.why}"
                                LogExporter.Outcome.Cancelled -> null
                            }
                        }
                    }) { Text("Export what Bulwark changed") }
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
                PackageRow(entry, runner, ::report)
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
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PackageRow(
    entry: CatalogEntry,
    runner: ActionRunner,
    onOutcome: (ActionRunner.Outcome) -> Unit,
) {
    var busy by remember(entry.packageName) { mutableStateOf(false) }

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
