package com.bulwark.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.TextButton
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.Attention
import com.bulwark.app.permissions.AuditSummary
import com.bulwark.app.permissions.RatFinding
import com.bulwark.app.permissions.combinationsToShow
import com.bulwark.app.permissions.headline
import com.bulwark.app.permissions.originLabel
import com.bulwark.app.permissions.unavailableLine
import com.bulwark.app.permissions.attention
import com.bulwark.app.permissions.combinations

/**
 * What each app can actually do to you.
 *
 * The permissions people are shown - camera, microphone, location - are not
 * the ones that matter most. These live several taps deep under Settings ->
 * Apps -> Special app access, and each is strictly more powerful than the mic.
 *
 * **This section never accuses.** It reports capability in plain language and
 * names combinations that are worse than their parts, because that is a fact
 * about Android rather than a judgement about an app. Bulwark cannot know
 * whether an app has a good reason to hold something, and a red badge on a
 * legitimate screen reader would be a false accusation pointed at the app a
 * disabled user depends on most.
 */
@Composable
fun SpecialAccessSection(
    apps: List<AppAccess>,
    summary: AuditSummary,
    unavailable: List<String>,
    ratFindings: List<RatFinding> = emptyList(),
    /**
     * Take one access away from one app. Null while the screen is read-only.
     *
     * Offered only for the four that are app ops; the other three are
     * enrolments Bulwark can see and cannot change, and `Access.opName` is what
     * says which is which. A control that cannot act must not be drawn.
     */
    onRevoke: ((AppAccess, Access) -> Unit)? = null,
    /** Which access is mid-change, so its control is not pressed twice. */
    busy: Pair<String, Access>? = null,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What apps can do to you", style = MaterialTheme.typography.titleMedium)

            if (apps.isEmpty() && unavailable.isEmpty()) {
                Text(
                    "Nothing on this phone holds screen control, notification " +
                        "access, device admin, overlay, usage access, all-files " +
                        "access or the ability to install apps.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            // The sentence is chosen in permissions/, where it is unit-tested.
            // This renders it.
            Text(summary.headline(), style = MaterialTheme.typography.bodySmall)
            if (summary.appsToLookAt > 0) {
                Text(
                    "${summary.appsToLookAt} hold a combination worth looking at.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Degrading honestly: a partial audit presented as complete is the
            // false sense of protection safety-rules.md calls worse than none.
            unavailableLine(unavailable)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Incomplete)
            }

            // Above the per-app list: these are readings of the whole device,
            // not of one app, and they lose their meaning split across rows.
            ratFindings.forEach { RatFindingCard(it) }

            apps.forEach { AccessRow(it, ratFindings, onRevoke, busy) }
        }
    }
}

/**
 * A device-level finding, with the ordinary explanation first.
 *
 * The order of these three blocks is the design. Bulwark's own onboarding
 * switches on wireless debugging, so leading with the alarming reading would
 * have the app frightening a user about its own footprint - and a user who
 * learns to dismiss this card will dismiss the one that mattered.
 */
@Composable
private fun RatFindingCard(finding: RatFinding) {
    Card(colors = CardDefaults.cardColors(containerColor = CautionBackground)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                finding.headline,
                style = MaterialTheme.typography.titleSmall,
                color = CautionText,
            )
            Text(
                finding.innocentFirst,
                style = MaterialTheme.typography.bodySmall,
                color = CautionText,
            )
            Text(
                finding.whatWouldWorry,
                style = MaterialTheme.typography.bodySmall,
                color = CautionText,
            )
        }
    }
}

@Composable
private fun AccessRow(
    app: AppAccess,
    findings: List<RatFinding> = emptyList(),
    onRevoke: ((AppAccess, Access) -> Unit)? = null,
    busy: Pair<String, Access>? = null,
) {
    Column(
        Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.attention == Attention.LOOK_AT_THIS) {
                Text(
                    "LOOK",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(WorthLookingAt)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = if (app.attention == Attention.LOOK_AT_THIS) 8.dp else 0.dp),
            )
        }

        Text(app.originLabel, style = MaterialTheme.typography.labelSmall)

        app.accesses.sortedBy { it.name }.forEach { access ->
            Text("• ${access.plainMeaning}", style = MaterialTheme.typography.bodySmall)
            // One control per access, and only where there is an app op behind
            // it. Accessibility, notification listening and device admin are
            // enrolments kept elsewhere: Bulwark can report them and cannot
            // switch them off, and an offer it cannot honour is the false sense
            // of protection `safety-rules.md` calls worse than none.
            if (onRevoke != null && access.opName != null) {
                val isBusy = busy == app.packageName to access
                TextButton(
                    enabled = !isBusy,
                    onClick = { onRevoke(app, access) },
                ) { Text(if (isBusy) "Taking it away…" else "Take this away") }
            }
        }

        // Minus anything a device-level finding above already explained.
        combinationsToShow(app, findings).forEach {
            Card(colors = CardDefaults.cardColors(containerColor = CautionBackground)) {
                Text(
                    it.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = CautionText,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}
