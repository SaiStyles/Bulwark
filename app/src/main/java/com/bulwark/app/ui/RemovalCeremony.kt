package com.bulwark.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bulwark.app.policy.Standing
import com.bulwark.app.policy.confirmationAccepts
import com.bulwark.app.policy.confirmationPhrase
import com.bulwark.app.policy.labels
import com.bulwark.app.policy.secondWarning

/**
 * The gate in front of removing something this phone named as critical.
 *
 * A warning, a second warning that says what stops working, then the package
 * name typed by hand. Authentication happens after this, outside our process,
 * in `ActionRunner` - so the full sequence is read, read again, type, confirm.
 *
 * ## Who sees it
 *
 * **Only packages the device itself named** - the dialer, the launcher, the
 * SystemUI, the IMS provider. Not the other three hundred and seventy.
 * `Standing.needsCeremony` is the one place that decides, and it deliberately
 * does not fire on an unread check: one failed role read would otherwise put
 * this dialog in front of everything, and a gate that fires on everything
 * teaches people to type through the one that mattered.
 *
 * ## What it is and is not for
 *
 * Aimed at the accidental tap, not at disagreement. Someone who has read what
 * the package does and typed its name is deciding, and it is their phone -
 * `safety-rules.md` gave up refusing on their behalf. The teaching is carried
 * by the label; this only carries the meaning-it.
 *
 * Nothing here is cached between openings: the dialog is rebuilt per package,
 * so a typed name can never be carried from one package to another.
 */
@Composable
fun RemovalCeremony(
    standing: Standing,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    // Keyed on the package, so opening a different row starts at stage one
    // with an empty field rather than inheriting the last one's progress.
    var stage by remember(standing.packageName) { mutableIntStateOf(1) }
    var typed by remember(standing.packageName) { mutableStateOf("") }

    val accepted = standing.confirmationAccepts(typed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (stage) {
                    1 -> "Remove ${standing.packageName}?"
                    2 -> "This is what stops working"
                    else -> "Type the name to confirm"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (stage) {
                    // One: what the phone says this is. The label does the
                    // teaching, so it is shown in full rather than summarised.
                    1 -> standing.labels().forEach { Text(it) }

                    // Two: the consequence, stated once and plainly. Not a
                    // repeat of "are you sure" - a second identical warning
                    // teaches people that warnings carry no information.
                    2 -> Text(standing.secondWarning().orEmpty())

                    else -> {
                        Text(
                            "Type ${standing.confirmationPhrase()} to remove it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            singleLine = true,
                            label = { Text("Package name") },
                            modifier = Modifier,
                        )
                        // Said here rather than after: the fingerprint prompt
                        // is the system's and cannot carry our wording.
                        Text(
                            "You will be asked to confirm it is you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // The last step is the only one that can act, and only with
                // the name typed exactly. Every earlier button advances.
                enabled = stage < 3 || accepted,
                onClick = { if (stage < 3) stage++ else onConfirmed() },
            ) {
                Text(if (stage < 3) "Continue" else "Remove it")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
