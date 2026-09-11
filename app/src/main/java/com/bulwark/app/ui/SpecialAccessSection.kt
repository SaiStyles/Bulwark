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
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.Attention
import com.bulwark.app.permissions.AuditSummary
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

            // Only states the user-installed count when it is actually known.
            // Saying "2 you installed yourself" about the launcher, because
            // unknown defaulted to false, was the first hardware bug here.
            Text(
                buildString {
                    append("${summary.appsWithAnyAccess} apps hold at least one of these")
                    if (summary.userInstalledWithAccess > 0) {
                        append(" — ${summary.userInstalledWithAccess} you installed yourself")
                    }
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.appsToLookAt > 0) {
                Text(
                    "${summary.appsToLookAt} hold a combination worth looking at.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Degrading honestly: a partial audit presented as complete is the
            // false sense of protection safety-rules.md calls worse than none.
            if (unavailable.isNotEmpty()) {
                Text(
                    "Bulwark could not check: " + unavailable.joinToString("; ") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = Incomplete,
                )
            }

            apps.forEach { AccessRow(it) }
        }
    }
}

@Composable
private fun AccessRow(app: AppAccess) {
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

        when (app.isSystem) {
            true -> Text("Came with the phone.", style = MaterialTheme.typography.labelSmall)
            false -> Text("You installed this.", style = MaterialTheme.typography.labelSmall)
            // Said out loud rather than guessed either way.
            null -> Text(
                "Bulwark cannot tell whether this came with the phone.",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        app.accesses.sortedBy { it.name }.forEach {
            Text("• ${it.plainMeaning}", style = MaterialTheme.typography.bodySmall)
        }

        app.combinations().forEach {
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
