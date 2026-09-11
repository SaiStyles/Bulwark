package com.bulwark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.REVOKE_IS_NOT_A_LOCK
import com.bulwark.app.permissions.appPermissionSummary
import com.bulwark.app.permissions.heldByApp
import com.bulwark.app.permissions.revocable
import com.bulwark.app.permissions.wordsFor
import com.bulwark.app.ui.theme.Incomplete

/**
 * What one app can do to you.
 *
 * The other half of the permission layer. `PermissionAuditSection` answers
 * *"who can hear me"* - a question about a capability. This answers *"what
 * does this app have"* - a question about an app. People arrive with both, and
 * Android answers neither well, so building only the first left the layer
 * half-delivered.
 *
 * ## What it shows, and what it does not
 *
 * **What the app holds**, not what it asks for. The count above the rows says
 * how many it was refused, because an app that keeps asking is worth noticing,
 * but a list padded with denials buries the answer to the question actually
 * being asked.
 *
 * **Runtime permissions only.** Install-time permissions cannot be revoked by
 * anyone - not Bulwark, not Settings, not the user - and listing thirty of
 * them would bury the handful that can be acted on. The section says so rather
 * than letting the omission read as "this app has nothing else".
 *
 * ## One action, one app, one permission
 *
 * No ticks and no batch here. The batch exists because clearing one capability
 * across twenty-seven apps is the job nobody can face doing by hand; taking
 * three permissions off one app is not that job, and rule 1's preference for
 * one considered change at a time applies with nothing pulling the other way.
 *
 * Like every other screen in this project, this composable chooses no words.
 * Each string comes from a tested pure function in `permissions/`.
 */
@Composable
fun AppPermissionsSection(
    packageName: String,
    /** Null while still loading. */
    holdings: List<PermissionHolding>?,
    /** Set when the read failed, so the screen can say so instead of showing nothing. */
    failure: String?,
    busyPermission: String?,
    onRevoke: (permission: String) -> Unit,
) {
    Column(
        Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("What it can do", style = MaterialTheme.typography.titleSmall)

        if (failure != null) {
            // "Could not read" and "has nothing" are different facts and must
            // never look the same on screen.
            Text(
                "Bulwark could not read this app's permissions. $failure",
                style = MaterialTheme.typography.bodySmall,
                color = Incomplete,
            )
            return@Column
        }

        if (holdings == null) {
            Text(
                "Reading…",
                style = MaterialTheme.typography.bodySmall,
                color = Incomplete,
            )
            return@Column
        }

        Text(appPermissionSummary(holdings), style = MaterialTheme.typography.bodySmall)

        val held = heldByApp(holdings)
        if (held.isEmpty()) {
            Text(
                "It holds none of them right now.",
                style = MaterialTheme.typography.bodySmall,
                color = Incomplete,
            )
        } else {
            Text(
                REVOKE_IS_NOT_A_LOCK,
                style = MaterialTheme.typography.bodySmall,
                color = Incomplete,
            )
            held.forEach { holding ->
                HeldPermissionRow(
                    holding = holding,
                    busy = busyPermission != null,
                    onRevoke = { onRevoke(holding.permission) },
                )
            }
        }

        Text(
            // Said out loud. An omission the user cannot see reads as "there is
            // nothing else here", which would be false for most apps.
            "Permissions granted when the app was installed are not listed. " +
                "Nothing can switch those off, including Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = Incomplete,
        )
    }
}

/** One capability the app holds, with the one action that fits it. */
@Composable
private fun HeldPermissionRow(
    holding: PermissionHolding,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    val words = wordsFor(holding.permission)
    val verdict = holding.revocable()

    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(words.name, fontWeight = FontWeight.Medium)
            words.meaning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            // A row Bulwark will not act on says why, in the same place the
            // button would have been. A greyed control with no explanation is
            // the app deciding for someone and not telling them.
            verdict.plainReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
            }
        }
        if (verdict.isOffered) {
            OutlinedButton(enabled = !busy, onClick = onRevoke) { Text("Take away") }
        }
    }
}
