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
import com.bulwark.app.debloat.RemovalRating
import com.bulwark.app.debloat.UadDatabase
import com.bulwark.app.debloat.readableDescription
import com.bulwark.app.shizuku.PrivilegedPackages
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var summary by remember { mutableStateOf<PackageCatalog.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyOffered by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val interrupted = remember(reload) { runCatching { runner.interrupted() }.getOrDefault(emptyList()) }

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

        when {
            !ready -> Text(
                "Start Shizuku to see every package. Without it Bulwark can only " +
                    "see about half of what is installed - and the half it cannot " +
                    "see is the preinstalled software.",
                style = MaterialTheme.typography.bodyMedium,
            )

            error != null -> Card {
                Text("Could not read packages\n\n$error", Modifier.padding(14.dp))
            }

            entries == null -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            else -> {
                if (interrupted.isNotEmpty()) InterruptedCard(interrupted.map { it.packageName })
                notice?.let { NoticeCard(it) { notice = null } }
                summary?.let { SummaryCard(it) }

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

                val shown = entries.orEmpty().filter { e ->
                    (!onlyOffered || e.isOffered) &&
                        (query.isBlank() || e.packageName.contains(query, ignoreCase = true))
                }

                Text(
                    "${shown.size} shown",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.packageName }) { entry ->
                        PackageRow(entry, runner, ::report)
                    }
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
            // Says what is KNOWN, not what is offered. Bulwark cannot
            // uninstall yet, and a count that reads as an offer is a promise
            // the app does not keep. "Safe" was our word too; the rating
            // belongs to the community database, so say whose it is.
            Text("${s.uninstallable} the database also rates removable — Bulwark cannot uninstall yet")
            Text("${s.refused} Bulwark refuses — they break your way back")
            Text("${s.unknown} nobody has documented — still offered, and shown as unknown")
            Text(
                "Package data snapshot ${UadDatabase.SNAPSHOT}, from the Universal " +
                    "Debloater Alliance. Bundled, not downloaded — Bulwark makes no " +
                    "network calls.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun InterruptedCard(packages: List<String>) {
    // safety-rules.md rule 6: on ambiguity, stop and report. Bulwark does not
    // know whether these applied and will not guess, so it says exactly that
    // rather than quietly "fixing" a change that may never have happened.
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Bulwark was interrupted",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF7A3E00),
            )
            Text(
                "It was part-way through changing ${packages.size} app(s) and " +
                    "never recorded finishing. They may or may not have applied " +
                    "- Bulwark does not know, and will not guess. Check each one:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7A3E00),
            )
            packages.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF7A3E00),
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
        entry.options.isRefused -> "LOCKED" to Color(0xFF1565C0)
        // UAD's own word, not ours. "SAFE" was Bulwark promising nothing
        // would change; Recommended only means most people can remove it.
        // com.google.android.as.oss is rated Recommended and is a dependency
        // of System Intelligence - both true at once.
        entry.rating == RemovalRating.RECOMMENDED -> "RECOMMENDED" to Color(0xFF2E7D32)
        entry.rating == RemovalRating.ADVANCED -> "CARE" to Color(0xFFE65100)
        entry.rating == RemovalRating.EXPERT -> "EXPERT" to Color(0xFF6A1B9A)
        entry.rating == RemovalRating.UNSAFE -> "RISKY" to Color(0xFFB71C1C)
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
