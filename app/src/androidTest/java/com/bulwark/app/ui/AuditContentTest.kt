package com.bulwark.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bulwark.app.firewall.AlwaysOn
import com.bulwark.app.observability.AppOpLedger
import com.bulwark.app.observability.DozeExemptions
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.PermissionAcrossApps
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.RatFinding
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.summarise
import com.bulwark.app.policy.ActionKind
import com.bulwark.app.policy.ActionRecord
import com.bulwark.app.policy.Phase
import com.bulwark.app.ui.theme.BulwarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detector the screen split waited on.
 *
 * `_shared/design.md` records why Apps and Audit stayed one composable with a
 * `tab` parameter: threading twenty values into a screen of their own has a
 * failure mode of a card that renders **empty** rather than erroring, and with
 * no Compose tests a screenshot was the only thing that could catch it - and
 * only if you already knew what should have been there.
 *
 * So these tests do not check that the audit *looks* right. They check the one
 * thing a screenshot was being used for: that a card which is on screen has
 * something in it, and that a card with nothing to say says why instead of
 * going blank.
 *
 * Every read is supplied as data. Nothing here touches Shizuku, the package
 * manager or the log, so it gives the same answer on a phone with no Shizuku
 * running as on SAI's.
 */
@RunWith(AndroidJUnit4::class)
class AuditContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyAuditCardShowsItsContentWhenEveryReadHasLanded() {
        render(fullyLoaded())

        compose.onNodeWithText("What is true about this phone").assertIsDisplayed()

        // Each assertion names a card's *content*, not its title. A title with
        // an empty body under it is exactly the failure being guarded against,
        // and asserting the title alone would pass straight through it.
        scrollTo("com.example.tracker")
        compose.onNodeWithText("com.example.tracker", substring = true).assertIsDisplayed()

        scrollTo("1 app blocked from the internet.")
        compose.onNodeWithText("1 app blocked from the internet.").assertIsDisplayed()

        // The permission group's plain name, which only appears if the group
        // reached the card.
        scrollTo("Camera")
        compose.onNodeWithText("Camera").assertIsDisplayed()

        scrollTo("com.example.halfway")
        compose.onNodeWithText("com.example.halfway", substring = true).assertIsDisplayed()
    }

    /**
     * The permissions card is always rendered, so "nothing to show" has to be
     * words rather than a blank. Three different silences - no Shizuku, the
     * read failed, genuinely nothing held - used to look identical to a card
     * that had vanished.
     */
    @Test
    fun thePermissionsCardSaysWhyItIsEmptyRatherThanRenderingBlank() {
        render(fullyLoaded().copy(shizukuReady = false, permissionGroups = null))

        scrollTo("Start Shizuku to see this.")
        compose.onNodeWithText("Start Shizuku to see this.", substring = true).assertIsDisplayed()
    }

    /**
     * Absent and empty are different facts. Until the read lands there is
     * nothing truthful to draw - but silence reads as "nothing to report", so
     * the card's slot says it is still checking rather than showing nothing.
     */
    @Test
    fun theSpecialAccessCardSaysItIsCheckingUntilItsReadLands() {
        render(fullyLoaded().copy(access = null, accessSummary = null))

        // The anchor first. A test whose only assertion is that something is
        // *absent* passes just as happily when the screen rendered nothing at
        // all - which is the exact failure these tests exist to catch, so it
        // has to be ruled out before the absence means anything.
        compose.onNodeWithText("What is true about this phone").assertIsDisplayed()
        compose.onNodeWithText("Checking what apps can do to you…").assertIsDisplayed()
        compose.onAllNodesWithText("What apps can do to you").assertCountEquals(0)
    }

    /**
     * The regression this file failed to catch the first time.
     *
     * The audit read finishes about a second after the screen opens. While the
     * special-access item was emitted only once its data existed, that second
     * arrival **prepended** an item to a `LazyColumn` that had already anchored
     * its scroll to the key below it - so the card rendered correctly, in the
     * right order, off the top of the viewport, where only scrolling up found
     * it. Shipped and found by looking at the phone, not by these tests.
     *
     * Static readings cannot catch that. This drives the transition the real
     * screen makes, and asserts the card is **displayed** afterwards - not
     * merely present, which it always was.
     */
    @Test
    fun theSpecialAccessCardIsVisibleWhenItsReadLandsAfterTheScreenIsDrawn() {
        val readings = mutableStateOf(
            fullyLoaded().copy(access = null, accessSummary = null),
        )
        compose.setContent {
            BulwarkTheme {
                AuditContent(
                    readings = readings.value,
                    onOpenVpnSettings = {},
                    onAllowVpn = {},
                    onOpenShizuku = {},
                    onOpenDeveloperOptions = {},
                    onOpenPermission = {},
                    onRevokeAcross = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Checking what apps can do to you…").assertIsDisplayed()

        // The read lands, exactly as the effect does on the phone.
        readings.value = fullyLoaded()

        compose.onNodeWithText("What apps can do to you").assertIsDisplayed()
    }

    /**
     * The Doze card - the first thing Bulwark shows that no Play Store app
     * can. Asserted by its content, like every other card here.
     */
    @Test
    fun theDozeCardNamesTheAppsThatKeepRunning() {
        render(fullyLoaded())

        scrollTo("keeps running while your phone sleeps")
        compose.onNodeWithText("What keeps running while your phone sleeps").assertIsDisplayed()
        compose.onNodeWithText(
            "1 app is allowed to keep working while your phone sleeps.",
        ).assertIsDisplayed()
    }

    /**
     * Bulwark reads this list and cannot edit it. A card that implied
     * otherwise would be the false sense of protection `safety-rules.md`
     * calls worse than none.
     */
    @Test
    fun theDozeCardNeverImpliesBulwarkCanRemoveAnExemption() {
        render(fullyLoaded())

        scrollTo("cannot take an app off the list")
        compose.onNodeWithText("cannot take an app off the list", substring = true)
            .assertIsDisplayed()
    }

    /** Without Shizuku it says so, rather than showing an empty list. */
    @Test
    fun theDozeCardSaysWhyItIsEmptyWithoutShizuku() {
        render(fullyLoaded().copy(shizukuReady = false, doze = null))

        scrollTo("which apps are exempt from sleeping")
        compose.onNodeWithText("which apps are exempt from sleeping", substring = true)
            .assertIsDisplayed()
    }

    /**
     * The ledger card. Asserted by the sentence a person actually reads, not
     * by its title - a title above an empty body is the failure this file
     * exists to catch.
     */
    @Test
    fun theLedgerCardNamesWhoUsedWhatAndWhen() {
        render(fullyLoaded())

        scrollTo("while you were not looking")
        compose.onNodeWithText("What apps did while you were not looking").assertIsDisplayed()
        compose.onNodeWithText(
            "com.example.tracker used the microphone once, " +
                "most recently 2026-09-13 21:36",
        ).assertIsDisplayed()
    }

    /** It reports; it does not accuse. */
    @Test
    fun theLedgerCardRefusesToAccuse() {
        render(fullyLoaded())

        scrollTo("leaves the judgement to you")
        compose.onNodeWithText("leaves the judgement to you", substring = true)
            .assertIsDisplayed()
    }

    /**
     * An empty ledger is good news and has to read as good news, not as a
     * card that failed to load.
     */
    @Test
    fun anEmptyLedgerSaysNothingHappenedRatherThanGoingBlank() {
        render(fullyLoaded().copy(ledger = emptyList()))

        scrollTo("while it was out of sight")
        compose.onNodeWithText(
            "No app used the microphone, camera or your precise location " +
                "while it was out of sight.",
        ).assertIsDisplayed()
    }

    @Test
    fun theFirewallCardAppearsOnlyWhenARuleExists() {
        render(fullyLoaded().copy(blockedApps = emptySet()))

        compose.onNodeWithText("What is true about this phone").assertIsDisplayed()
        compose.onAllNodesWithText("blocked from the internet.", substring = true)
            .assertCountEquals(0)
    }

    // -- helpers ------------------------------------------------------------

    private fun render(readings: AuditReadings) {
        compose.setContent {
            BulwarkTheme {
                AuditContent(
                    readings = readings,
                    onOpenVpnSettings = {},
                    onAllowVpn = {},
                    onOpenShizuku = {},
                    onOpenDeveloperOptions = {},
                    onOpenPermission = {},
                    onRevokeAcross = { _, _ -> },
                )
            }
        }
    }

    /** Scrolls the audit's list until the node is composed, or fails trying. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(text, substring = true))
    }

    /**
     * A phone where every one of the four reads came back with something.
     *
     * Built through the real `audit()` and `summarise()` rather than a
     * hand-made summary, so a change to what those produce shows up here
     * instead of being papered over by a fixture.
     */
    private fun fullyLoaded(): AuditReadings {
        val apps = listOf(
            AppAccess(
                packageName = "com.example.tracker",
                accesses = setOf(Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
                isSystem = false,
            ),
        )
        return AuditReadings(
            access = apps.audit(),
            accessSummary = apps.summarise(),
            accessUnavailable = emptyList(),
            ratSignals = listOf(
                RatFinding(
                    headline = "Something can watch your screen and hide itself",
                    innocentFirst = "Accessibility is what screen readers use.",
                    whatWouldWorry = "An app you do not recognise holding both.",
                    apps = listOf("com.example.tracker"),
                ),
            ),
            wirelessDebuggingOn = false,
            interrupted = listOf(
                ActionRecord(
                    id = 1,
                    atEpochMillis = 0,
                    packageName = "com.example.halfway",
                    kind = ActionKind.DISABLE,
                    phase = Phase.ATTEMPTED,
                    userId = 0,
                    previousState = null,
                    attemptId = null,
                    detail = null,
                ),
            ),
            permissionGroups = listOf(
                PermissionAcrossApps(
                    permission = "android.permission.CAMERA",
                    holders = listOf(
                        PermissionHolding(
                            packageName = "com.example.tracker",
                            permission = "android.permission.CAMERA",
                            isGranted = true,
                            isRuntime = true,
                            flags = 0,
                        ),
                    ),
                ),
            ),
            permissionsReadFailed = false,
            systemPackages = emptySet(),
            refinedPermissions = setOf("android.permission.CAMERA"),
            blockedApps = setOf("com.example.tracker"),
            firewallConsentNeeded = false,
            aVpnIsUp = true,
            alwaysOn = AlwaysOn.ON,
            lockdown = false,
            shizukuReady = true,
            doze = DozeExemptions.parse(
                listOf("user,com.example.tracker,10231"),
            ),
            dozeCouldNotTell = null,
            ledger = AppOpLedger.summarise(
                listOf(
                    AppOpLedger.parse(
                        listOf(
                            "Package com.example.tracker:",
                            "Access: [bg-s] 2026-09-13 21:36:06.258 (-1h)",
                        ),
                        AppOpLedger.Op.MICROPHONE,
                    ),
                ),
            ),
            ledgerCouldNotTell = null,
        )
    }
}
