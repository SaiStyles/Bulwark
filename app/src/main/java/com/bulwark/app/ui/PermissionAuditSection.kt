package com.bulwark.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bulwark.app.permissions.PermissionAcrossApps
import com.bulwark.app.permissions.REVOKE_IS_NOT_A_LOCK
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.batchRevokeButton
import com.bulwark.app.permissions.groupHeadline
import com.bulwark.app.permissions.originLabelFor
import com.bulwark.app.permissions.revocable
import com.bulwark.app.permissions.wordsFor
import com.bulwark.app.ui.theme.Incomplete

/**
 * Who can hear you, who can see you, who can read your files.
 *
 * Android answers this one app at a time, several taps deep, which is why
 * nobody ever answers it. The value of this screen is the **cross-app view** -
 * one capability, everyone holding it - and the revoke is a consequence of
 * seeing it rather than the point.
 *
 * ## What this screen is careful about
 *
 * **Selections start empty and there is no "select all".** That is a condition
 * of the rule 1 amendment (`_shared/safety-rules.md`, 2026-09-11) and the half
 * of the rule left untouched: a sweep action turns this into a bloatware
 * remover, and the project's position is to offer rather than to decide for
 * people.
 *
 * **A row Bulwark cannot change says why**, rather than being greyed out. A
 * disabled control with no explanation is the app deciding for someone and not
 * telling them.
 *
 * **Every row says where the app came from.** The microphone list opens with a
 * wall of system components on most phones, and without that label it reads as
 * alarming when it is mostly ordinary.
 *
 * This composable chooses no words. Every string comes from a tested pure
 * function in `permissions/` - `conventions.md` puts copy there because every
 * defect found on hardware so far has been in what the app *says*.
 */
@Composable
fun PermissionAuditSection(
    groups: List<PermissionAcrossApps>,
    /**
     * Said instead of the list when there is no list. Null when there is one.
     *
     * The section used to be hidden entirely whenever `groups` was empty, so a
     * view that was full yesterday was simply gone today - and an absence
     * reads as an answer. See `permissionAuditNotice`.
     */
    notice: String?,
    /** Packages that came with the phone. Absent means Bulwark could not tell. */
    systemPackages: Set<String>?,
    /** Asks for the expensive flag read for one group, once it is opened. */
    onOpen: (String) -> Unit,
    /** Groups whose flags have arrived. Others are still being checked. */
    refined: Set<String>,
    onRevoke: (permission: String, packages: List<String>) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            // **Not "What apps can do".** That sat one card below "What apps
            // can do to you" on the same screen, and two titles differing by
            // two words distinguish nothing - a person scanning the screen
            // cannot tell which card answers which question. This one names
            // its organising idea instead, which is also the reason it exists:
            // Android shows permissions one app at a time, and this shows one
            // permission across every app at once.
            Text("Who holds each permission", style = MaterialTheme.typography.titleMedium)
            Spacer()
            Text(
                "Every app holding each permission Android treats as yours to " +
                    "decide. Android shows this one app at a time; this is the " +
                    "same information in one place.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer()
            // Said before anything is offered, not after it is done. A screen
            // that only mentions the limit in the failure case has already let
            // someone walk away believing the wrong thing.
            Text(
                REVOKE_IS_NOT_A_LOCK,
                style = MaterialTheme.typography.bodySmall,
                color = Incomplete,
            )

            if (notice != null) {
                Spacer()
                Text(notice, style = MaterialTheme.typography.bodySmall, color = Incomplete)
                return@Column
            }

            groups.forEach { group ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PermissionGroup(
                    group = group,
                    systemPackages = systemPackages,
                    onOpen = onOpen,
                    isRefined = group.permission in refined,
                    onRevoke = onRevoke,
                )
            }
        }
    }
}

/**
 * One capability, and everyone holding it.
 *
 * Collapsed until opened, because the flag read behind each group costs a
 * binder call per app and most groups are never opened.
 */
@Composable
private fun PermissionGroup(
    group: PermissionAcrossApps,
    systemPackages: Set<String>?,
    onOpen: (String) -> Unit,
    isRefined: Boolean,
    onRevoke: (permission: String, packages: List<String>) -> Unit,
) {
    var expanded by remember(group.permission) { mutableStateOf(false) }
    // Selection lives with the expanded group and is dropped when it closes.
    // Nothing carries a tick from one capability to another: the batch is one
    // permission by construction, here as well as in the policy layer.
    var chosen by remember(group.permission) { mutableStateOf(emptySet<String>()) }

    val words = wordsFor(group.permission)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                if (expanded) onOpen(group.permission)
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(words.name, fontWeight = FontWeight.Medium)
            Text(
                groupHeadline(group, systemPackages),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
    }

    if (!expanded) return

    words.meaning?.let {
        Spacer()
        Text(it, style = MaterialTheme.typography.bodySmall)
    }

    if (!isRefined) {
        Spacer()
        Text(
            // Said out loud rather than showing controls that might not work.
            "Checking which of these Bulwark can change…",
            style = MaterialTheme.typography.bodySmall,
            color = Incomplete,
        )
        return
    }

    group.holders.forEach { holding ->
        HolderRow(
            holding = holding,
            systemPackages = systemPackages,
            isChosen = holding.packageName in chosen,
            onToggle = {
                chosen = if (holding.packageName in chosen) {
                    chosen - holding.packageName
                } else {
                    chosen + holding.packageName
                }
            },
        )
    }

    if (chosen.isEmpty()) return

    Spacer()
    TextButton(onClick = {
        val picked = group.holders.map { it.packageName }.filter { it in chosen }
        chosen = emptySet()
        onRevoke(group.permission, picked)
    }) {
        // Same words the system prompt will use, in the same order. If these
        // two ever disagree, the trustworthy channel has stopped matching the
        // one the user actually read.
        Text(batchRevokeButton(group.permission, chosen.size))
    }
}

/** One app inside a capability, with a tick or a reason there is none. */
@Composable
private fun HolderRow(
    holding: PermissionHolding,
    systemPackages: Set<String>?,
    isChosen: Boolean,
    onToggle: () -> Unit,
) {
    val verdict = holding.revocable()

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (verdict.isOffered) {
            Checkbox(checked = isChosen, onCheckedChange = { onToggle() })
        }
        Column(Modifier.padding(start = if (verdict.isOffered) 0.dp else 12.dp)) {
            Text(holding.packageName, style = MaterialTheme.typography.bodyMedium)
            Text(
                originLabelFor(holding.packageName, systemPackages),
                style = MaterialTheme.typography.bodySmall,
            )
            verdict.plainReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
            }
        }
    }
}

@Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
