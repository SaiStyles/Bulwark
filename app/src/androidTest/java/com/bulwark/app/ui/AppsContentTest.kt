package com.bulwark.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bulwark.app.debloat.CatalogEntry
import com.bulwark.app.debloat.Options
import com.bulwark.app.debloat.PackageCatalog
import com.bulwark.app.debloat.RemovalRating
import com.bulwark.app.firewall.FirewallState
import com.bulwark.app.shizuku.CriticalRoles
import com.bulwark.app.ui.theme.BulwarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The package list's half of the detector. See `AuditContentTest` for why
 * these exist at all.
 *
 * The list has four states that all used to look the same from a screenshot -
 * no Shizuku, read failed, still loading, and a real but empty result - and
 * only one of them is good news. Each of them gets a test here, because "the
 * screen came up blank" is the report this project has already acted on twice.
 *
 * Nothing here can change the phone: [RowActions] is a fake that records the
 * request and touches nothing, which is the only reason a row with live
 * buttons can be rendered in a test at all.
 */
@RunWith(AndroidJUnit4::class)
class AppsContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSummaryFiltersAndRowsAllShowWhenTheReadHasLanded() {
        render(loaded())

        compose.onNodeWithText("What is on this phone").assertIsDisplayed()

        scrollTo("2 packages installed")
        compose.onNodeWithText("2 packages installed").assertIsDisplayed()

        scrollTo("2 shown")
        compose.onNodeWithText("2 shown").assertIsDisplayed()

        // A row, and the description's later lines with it - the lines after
        // the first are the ones that name what breaks.
        scrollTo("com.example.bloat")
        compose.onNodeWithText("com.example.bloat", substring = true).assertIsDisplayed()
    }

    /**
     * Without Shizuku the list is genuinely short, and saying nothing would
     * let someone believe their phone has 176 packages on it.
     */
    @Test
    fun withoutShizukuTheScreenSaysSoRatherThanShowingAShortList() {
        render(loaded().copy(shizukuReady = false))

        compose.onNodeWithText("Start Shizuku to see every package", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("2 packages installed").assertCountEquals(0)
    }

    /** A read that failed is not a phone with nothing on it. */
    @Test
    fun aFailedReadShowsTheErrorInsteadOfAnEmptyList() {
        render(loaded().copy(error = "SecurityException: not allowed"))

        compose.onNodeWithText("Could not read packages.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("2 packages installed").assertCountEquals(0)
    }

    /** Null entries means no read has finished, which is a spinner, not a list. */
    @Test
    fun aReadThatHasNotFinishedShowsNeitherRowsNorASummary() {
        render(loaded().copy(entries = null, summary = null))

        // The anchor first. A test whose only assertion is that something is
        // *absent* passes just as happily when the screen rendered nothing at
        // all - which is the exact failure these tests exist to catch.
        compose.onNodeWithText("What is on this phone").assertIsDisplayed()
        compose.onAllNodesWithText("com.example.bloat", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("2 packages installed").assertCountEquals(0)
    }

    /**
     * One action per row, chosen by current state - never both. A blocked app
     * offers the undo and nothing else, because hardware testing found that two
     * buttons side by side let a second press perform the opposite of its label.
     */
    @Test
    fun aBlockedAppOffersTheUndoAndNotTheBlock() {
        render(
            loaded(
                blocked = setOf("com.example.bloat"),
                firewall = FirewallState.IN_FORCE,
            )
        )

        scrollTo("Blocked from the internet by Bulwark.")
        compose.onNodeWithText("Blocked from the internet by Bulwark.").assertIsDisplayed()
    }

    /**
     * The regression from the first outside test, 2026-09-15.
     *
     * A rule existed and the row said "Blocked from the internet by Bulwark."
     * while consent had never been granted, so nothing on the phone was
     * stopping anything. The rule is a decision; only a live tunnel makes it
     * true, and the row has to know the difference.
     *
     * Asserts the **absence** of the claim as well as the presence of the
     * correction, because the failure was never a missing sentence - it was a
     * present one that was false.
     */
    @Test
    fun aRuleWithoutConsentIsNotCalledBlocked() {
        render(
            loaded(
                blocked = setOf("com.example.bloat"),
                firewall = FirewallState.NEEDS_CONSENT,
            )
        )

        scrollTo("Set to block, but still online")
        compose.onNodeWithText("Set to block, but still online", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("Blocked from the internet by Bulwark.")
            .assertCountEquals(0)
    }

    /** The same, for the state every reboot lands in. */
    @Test
    fun aRuleNothingIsEnforcingIsNotCalledBlocked() {
        render(
            loaded(
                blocked = setOf("com.example.bloat"),
                firewall = FirewallState.NOT_IN_FORCE,
            )
        )

        scrollTo("Set to block, but still online")
        compose.onNodeWithText("Set to block, but still online", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("Blocked from the internet by Bulwark.")
            .assertCountEquals(0)
    }

    @Test
    fun theSearchNarrowsTheListToWhatMatches() {
        render(loaded(), query = "tracker")

        scrollTo("1 shown")
        compose.onNodeWithText("1 shown").assertIsDisplayed()
        compose.onAllNodesWithText("com.example.bloat", substring = true).assertCountEquals(0)
    }

    // -- helpers ------------------------------------------------------------

    private fun render(readings: AppsReadings, query: String = "") {
        compose.setContent {
            BulwarkTheme {
                AppsContent(
                    readings = readings,
                    actions = RecordingActions(),
                    query = query,
                    onQueryChange = {},
                    onOutcome = {},
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(text, substring = true))
    }

    private fun loaded(
        blocked: Set<String> = emptySet(),
        firewall: FirewallState = FirewallState.NOTHING_BLOCKED,
    ) = AppsReadings(
        entries = listOf(
            entry("com.example.bloat"),
            entry("com.example.tracker"),
        ),
        summary = PackageCatalog.Summary(
            total = 2,
            offered = 2,
            refused = 0,
            unknown = 0,
        ),
        error = null,
        // Every job read, none unreadable: the labels are settled rather than
        // still being checked.
        roles = CriticalRoles.Reading(holders = emptyMap(), unreadable = emptySet()),
        blockedApps = blocked,
        firewall = firewall,
        shizukuReady = true,
    )

    private fun entry(name: String) = CatalogEntry(
        packageName = name,
        isSystem = true,
        isEnabled = true,
        rating = RemovalRating.RECOMMENDED,
        options = Options(
            canDisable = true,
            canUninstall = false,
            warning = null,
            refusal = null,
        ),
        description = "A preinstalled app.\nNothing depends on it.",
        neededByInstalled = emptyList(),
    )

    /**
     * A [RowActions] that records and does nothing else.
     *
     * The production implementation is `ActionRunner`, which authenticates
     * outside our process before anything changes. Nothing is being tested
     * about that here - these tests are about what is drawn - and a fake is the
     * only way to draw a row at all, since `ActionRunner` must register its
     * activity-result launchers before the Activity is STARTED and a test's
     * Activity is already resumed.
     */
    private class RecordingActions : RowActions {
        val requested = mutableListOf<String>()

        override fun disable(
            packageName: String,
            label: String,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "disable $packageName"
        }

        override fun switchBackOn(
            packageName: String,
            label: String,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "switchBackOn $packageName"
        }

        override fun uninstall(
            packageName: String,
            label: String,
            canRestore: Boolean,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "uninstall $packageName"
        }

        override fun revokePermission(
            packageName: String,
            permission: String,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "revoke $packageName $permission"
        }

        override fun blockNetwork(
            packageName: String,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "block $packageName"
        }

        override fun allowNetwork(
            packageName: String,
            onOutcome: (ActionRunner.Outcome) -> Unit,
        ) {
            requested += "allow $packageName"
        }
    }
}
