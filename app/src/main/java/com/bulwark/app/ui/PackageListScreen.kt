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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.bulwark.app.debloat.Verdict
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Layer 1's real screen: everything installed, and what Bulwark will say about
 * each of it.
 *
 * **Read-only.** There is no remove button yet, deliberately. No destructive
 * action ships before its undo is written and tested
 * (`context/_shared/safety-rules.md` rule 3), and every one will pass through
 * `DestructiveActionGuard` when it does.
 *
 * What this screen is for right now is honesty: showing the user what is on
 * their phone, what it does, and which parts Bulwark refuses to touch and why.
 * That is useful on its own and it is the surface the actions will attach to.
 */
@Composable
fun PackageListScreen(
    state: ShizukuState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var summary by remember { mutableStateOf<PackageCatalog.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyOffered by remember { mutableStateOf(false) }

    val ready = state is ShizukuState.Ready

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        try {
            val built = withContext(Dispatchers.IO) {
                val installed = PrivilegedPackages.listDetailed()
                val catalog = PackageCatalog(UadDatabase.load(context))
                val list = catalog.build(
                    installed.map { it.packageName },
                    installed.filter { it.isSystem }.map { it.packageName }.toSet(),
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
                    label = { Text("Only what Bulwark would offer") },
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
                    items(shown, key = { it.packageName }) { PackageRow(it) }
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
            Text("${s.offered} Bulwark would offer to remove")
            Text("${s.protected} protected — never removable")
            Text("${s.tooRisky} known to be unsafe to remove")
            Text("${s.unknown} unknown — not offered, because nobody has verified them")
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
private fun PackageRow(entry: CatalogEntry) {
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

            entry.description?.let {
                Text(it.lineSequence().first(), style = MaterialTheme.typography.bodySmall)
            }

            when (val v = entry.verdict) {
                is Verdict.Protected -> Reason("Bulwark will never remove this. ${v.reason}")
                is Verdict.TooRisky -> Reason("Known to be unsafe to remove. ${v.reason}")
                is Verdict.Unknown -> Reason(
                    "Not offered. Nobody has verified what removing this does, and " +
                        "an unverified guess is how a phone gets bricked."
                )
                is Verdict.Offered -> Unit
            }

            if (entry.neededByInstalled.isNotEmpty()) {
                Reason(
                    "Other apps you have installed depend on this: " +
                        entry.neededByInstalled.joinToString(", ")
                )
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
    val (label, colour) = when (entry.verdict) {
        is Verdict.Offered -> when (entry.rating) {
            RemovalRating.RECOMMENDED -> "SAFE" to Color(0xFF2E7D32)
            RemovalRating.ADVANCED -> "CARE" to Color(0xFFE65100)
            RemovalRating.EXPERT -> "EXPERT" to Color(0xFF6A1B9A)
            else -> "?" to Color.Gray
        }
        is Verdict.Protected -> "LOCKED" to Color(0xFF1565C0)
        is Verdict.TooRisky -> "UNSAFE" to Color(0xFFB71C1C)
        is Verdict.Unknown -> "UNKNOWN" to Color.Gray
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
