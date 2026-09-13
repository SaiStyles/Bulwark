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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bulwark.app.policy.Standing
import com.bulwark.app.policy.confirmationAccepts
import com.bulwark.app.policy.confirmationPhrase
import com.bulwark.app.policy.labels

/**
 * The gate in front of removing something this phone named as critical.
 *
 * What the package is, then its name typed by hand. Authentication happens
 * after this, outside our process, in `ActionRunner` - so the sequence is read,
 * type, confirm.
 *
 * **It had a second warning and does not any more.** It was meant to name what
 * would stop working; SAI read it on a phone and said it read like the first
 * one, and the fix was not better wording. The device can name about seven
 * packages, so consequence copy exists for seven and nowhere else - writing it
 * teaches people to expect it on the three hundred where Bulwark has nothing
 * to say. A stage that cannot say something genuinely new is a button people
 * learn to tap through, which spends the attention the typing needs.
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
    var typing by remember(standing.packageName) { mutableStateOf(false) }
    var typed by remember(standing.packageName) { mutableStateOf("") }

    val accepted = standing.confirmationAccepts(typed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (typing) "Type the name to confirm" else "Remove ${standing.packageName}?",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!typing) {
                    // What the phone says this is, and whether Bulwark could
                    // put it back. The labels do the whole job; there is no
                    // second screen restating them.
                    standing.labels().forEach { Text(it) }
                } else {
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
                    // Said here rather than after: the fingerprint prompt is
                    // the system's and cannot carry our wording.
                    Text(
                        "You will be asked to confirm it is you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // Only the second step can act, and only with the name typed
                // exactly. The first advances.
                enabled = !typing || accepted,
                onClick = { if (!typing) typing = true else onConfirmed() },
            ) {
                Text(if (!typing) "Continue" else "Remove it")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
