package com.bulwark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bulwark.app.permissions.wordsFor
import com.bulwark.app.ui.theme.CautionText
import com.bulwark.app.policy.Change
import com.bulwark.app.policy.canBeUndone
import com.bulwark.app.policy.whyNoUndo
import com.bulwark.app.policy.Reconciliation
import com.bulwark.app.policy.ChangeKind
import com.bulwark.app.policy.changesHeadline
import com.bulwark.app.policy.describe
import com.bulwark.app.policy.undoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Bulwark has changed, and taking any of it back.
 *
 * Its own destination rather than another card in the package list, because it
 * answers a different question - `design.md` rule 1, and the shape both Canta
 * and App Manager arrived at independently.
 *
 * Before this existed Bulwark could change fourteen things and then not tell
 * you which fourteen. The count was on screen; the names were nowhere. That is
 * not the reversibility `safety-rules.md` promises: one all-or-nothing button
 * and an exported file is not an undo you can aim.
 *
 * ## No cards
 *
 * Rows separated by rules, not boxes. Every entry here is the same kind of
 * thing and none is more urgent than another, so containment would add weight
 * without adding order - `design.md` rule 3. The type scale does the work.
 */
@Composable
fun ChangesScreen(
    runner: ActionRunner,
    exporter: LogExporter,
    onOutcome: (ActionRunner.Outcome) -> Unit,
    reloadKey: Int,
    modifier: Modifier = Modifier,
) {
    var reconciled by remember { mutableStateOf<Reconciliation?>(null) }

    LaunchedEffect(reloadKey) {
        reconciled = withContext(Dispatchers.IO) {
            // Null on failure, never an empty list. "We could not read the
            // phone" and "the phone has nothing on it" are different facts and
            // only one of them is safe to render as reassurance.
            runCatching { runner.changes() }.getOrNull()
        }
    }

    val result = reconciled
    val list = result?.changes
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "headline") {
            Text(
                text = when {
                    list == null -> "Reading what has changed on this phone…"
                    else -> changesHeadline(list)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // Said whenever the phone itself could not be read. Without it this
        // screen presents the log as the state of the device, which is exactly
        // the claim the log cannot support on its own.
        if (result?.deviceUnknown == true) {
            item(key = "device-unknown") {
                Text(
                    "Bulwark could not read this phone's current state, so this " +
                        "is its own record and may be out of date. Anything " +
                        "switched off outside Bulwark is not listed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        // Not an error. Something was put back by another route, and the row
        // for it is gone rather than left offering an undo that would switch
        // a working app off again.
        if (result != null && result.alreadyBack > 0) {
            item(key = "already-back") {
                Text(
                    if (result.alreadyBack == 1) {
                        "1 app Bulwark switched off is already back on, so it is " +
                            "not listed here."
                    } else {
                        "${result.alreadyBack} apps Bulwark switched off are " +
                            "already back on, so they are not listed here."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        if (list != null && list.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing on this phone is switched off, and Bulwark has no " +
                        "record of changing anything. Anything you change will " +
                        "be listed here, with its own undo. This is the record " +
                        "of what was changed, not of what is wrong with your " +
                        "phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(list.orEmpty(), key = { "${it.kind}:${it.packageName}:${it.permission}" }) { change ->
            ChangeRow(change, result?.restorable.orEmpty(), runner, onOutcome)
            HorizontalDivider()
        }

        if (!list.isNullOrEmpty()) {
            item(key = "bulk") {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // "Put everything back" was here and is gone, 2026-09-12.
                    // Every row above has its own undo, which is the precise
                    // version of the same thing - and the bulk path was a
                    // second implementation of undo that drifted from the
                    // first: it handed an uninstalled package an ENABLE and
                    // wrote SUCCEEDED over work it had not done.
                    Text(
                        "Undo them one at a time above, or keep a copy of the " +
                            "list first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Warned BEFORE the picker, not after the file exists.
                    // threat-model.md: for someone who has just switched off
                    // monitoring software, a file naming it - sitting in
                    // Downloads on a phone another person can reach - is the
                    // discovery risk the whole detection section is about. A
                    // warning after the save has happened is not a warning, it
                    // is a receipt. Carried over verbatim when this moved here,
                    // because the first rewrite of it was weaker and later.
                    Text(
                        "This file lists every app you changed, and it stays " +
                            "wherever you save it. If someone else can reach " +
                            "this phone, choose somewhere they cannot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CautionText,
                    )
                    TextButton(onClick = {
                        // Translated into the same Outcome the rest of the app
                        // reports through, so there is one way a result reaches
                        // a person rather than two.
                        exporter.export { result ->
                            when (result) {
                                is LogExporter.Outcome.Saved ->
                                    onOutcome(ActionRunner.Outcome.Done("Saved to ${result.where}."))
                                is LogExporter.Outcome.Failed ->
                                    onOutcome(
                                        ActionRunner.Outcome.Failed("Could not save. ${result.why}"),
                                    )
                                LogExporter.Outcome.Cancelled ->
                                    onOutcome(ActionRunner.Outcome.Cancelled)
                            }
                        }
                    }) { Text("Export what Bulwark changed") }
                }
            }
        }
    }
}

/**
 * One change, and the button that reverses that one thing.
 *
 * The package name is the identifier, so it is monospace and it leads. What was
 * done to it is a sentence underneath - `design.md` rule 6: the copy is the
 * element, the control goes below it rather than crowding beside it.
 */
@Composable
private fun ChangeRow(
    change: Change,
    restorable: Set<String>,
    runner: ActionRunner,
    onOutcome: (ActionRunner.Outcome) -> Unit,
) {
    var busy by remember(change) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            change.packageName,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        change.permission?.let {
            Text(wordsFor(it).name, style = MaterialTheme.typography.labelLarge)
        }
        Text(change.describe(), style = MaterialTheme.typography.bodySmall)

        // No button where Bulwark cannot do the thing the button names. The
        // sentence says why, so the row is still informative - it just stops
        // offering to spend an authentication on a refusal.
        if (!change.canBeUndone(restorable)) {
            Text(
                change.whyNoUndo(),
                style = MaterialTheme.typography.bodySmall,
                color = CautionText,
            )
            return@Column
        }

        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                val done: (ActionRunner.Outcome) -> Unit = { busy = false; onOutcome(it) }
                when (change.kind) {
                    ChangeKind.SWITCHED_OFF ->
                        runner.switchBackOn(change.packageName, change.packageName, done)
                    ChangeKind.INTERNET_BLOCKED ->
                        runner.allowNetwork(change.packageName, done)
                    ChangeKind.PERMISSION_TAKEN ->
                        runner.giveBackPermission(
                            change.packageName,
                            change.permission.orEmpty(),
                            done,
                        )
                    // Offered from 2026-09-12, once uninstall itself was
                    // proven on hardware. It reads the state back and fails
                    // loudly if the package is still missing, so an
                    // install-existing that does not work reports that rather
                    // than leaving a green tick over a gap.
                    ChangeKind.UNINSTALLED ->
                        runner.putBack(change.packageName, change.packageName, done)
                    // **No undo exists yet.** The log kinds for special access
                    // landed on 2026-09-14 ahead of the action that writes
                    // them, so nothing can produce this row today and this
                    // branch is unreachable. It reports rather than silently
                    // doing nothing: a button that reads like it acted and did
                    // not is the false sense of protection `safety-rules.md`
                    // calls worse than none. Delete this the moment the revoke
                    // ships with a working `giveBackSpecialAccess`.
                    ChangeKind.SPECIAL_ACCESS_TAKEN -> done(
                        ActionRunner.Outcome.Failed(
                            "Bulwark cannot give this access back yet - the " +
                                "action has not been built. Nothing was changed.",
                        ),
                    )
                }
            },
        ) { Text(change.undoLabel()) }
    }
}
