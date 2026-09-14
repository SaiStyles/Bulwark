package com.bulwark.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bulwark.app.firewall.AlwaysOn
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.HiddenSwitch
import com.bulwark.app.permissions.HiddenSwitchHolder
import com.bulwark.app.permissions.PermissionAcrossApps
import com.bulwark.app.permissions.PermissionHolding
import com.bulwark.app.permissions.RatFinding
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.summarise
import com.bulwark.app.policy.ActionKind
import com.bulwark.app.policy.ActionRecord
import com.bulwark.app.policy.Phase
import com.bulwark.app.ui.theme.BulwarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detector the screen split waited on.
 *
 * `_shared/design.md` records why Apps and Audit stayed one composable with a
 * `tab` parameter: threading sixteen values into a screen of their own has a
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
                    onRevokeAccess = { _, _ -> },
                    onRevokeSwitch = { _, _ -> },
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
     * The revoke control appears only where there is an app op behind the
     * access. Accessibility, notification listening and device admin are
     * enrolments Android keeps elsewhere - Bulwark can report them and cannot
     * switch them off, and an offer it cannot honour is the false sense of
     * protection `safety-rules.md` calls worse than none.
     */
    @Test
    fun onlyAnAccessWithAnAppOpBehindItIsOfferedAControl() {
        val apps = listOf(
            AppAccess("com.example.overlay", setOf(Access.DRAW_OVER_APPS), isSystem = false),
            AppAccess("com.example.reader", setOf(Access.ACCESSIBILITY), isSystem = false),
        )
        val tapped = mutableListOf<String>()
        compose.setContent {
            BulwarkTheme {
                AuditContent(
                    readings = fullyLoaded().copy(
                        access = apps.audit(),
                        accessSummary = apps.summarise(),
                        // Emptied so the only controls on screen belong to the
                        // card under test - the hidden-switch card draws its own.
                        hiddenSwitches = emptyList(),
                    ),
                    onRevokeAccess = { app, access -> tapped += "${app.packageName}:$access" },
                    onRevokeSwitch = { _, _ -> },
                    onOpenVpnSettings = {},
                    onAllowVpn = {},
                    onOpenShizuku = {},
                    onOpenDeveloperOptions = {},
                    onOpenPermission = {},
                    onRevokeAcross = { _, _ -> },
                )
            }
        }

        // One control, not two: only the overlay app has an op behind it.
        compose.onAllNodesWithText("Take this away").assertCountEquals(1)

        compose.onNodeWithText("Take this away").performClick()
        assertEquals(listOf("com.example.overlay:DRAW_OVER_APPS"), tapped)
    }

    /**
     * The card that exists because Settings has no screen for this. Asserted
     * by the sentence that makes it worth showing, not by its title.
     */
    @Test
    fun theHiddenSwitchCardSaysAndroidHasNoSettingForIt() {
        render(fullyLoaded())

        scrollTo("Android has no setting for this")
        compose.onNodeWithText("Android has no setting for this", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("1 app can read or change your clipboard.").assertIsDisplayed()
    }

    /** It reports; it does not accuse. Most apps hold these for ordinary reasons. */
    @Test
    fun theHiddenSwitchCardRefusesToAccuse() {
        render(fullyLoaded())

        scrollTo("ordinary reason to hold it")
        compose.onNodeWithText("ordinary reason to hold it", substring = true)
            .assertIsDisplayed()
    }

    /** Without Shizuku it says why, rather than showing an empty list. */
    @Test
    fun theHiddenSwitchCardSaysWhyItIsEmptyWithoutShizuku() {
        render(fullyLoaded().copy(shizukuReady = false, hiddenSwitches = null))

        scrollTo("reading who holds one")
        compose.onNodeWithText("reading who holds one", substring = true).assertIsDisplayed()
    }

    /**
     * Nothing holding one is *not* reassurance - every phone has apps that do,
     * so an empty answer means the read is suspect rather than the phone clean.
     */
    @Test
    fun anEmptyHiddenSwitchListIsTreatedAsSuspectNotAsGoodNews() {
        render(fullyLoaded().copy(hiddenSwitches = emptyList()))

        scrollTo("worth a second look")
        compose.onNodeWithText("worth a second look", substring = true).assertIsDisplayed()
    }

    /**
     * **The gap this card shipped with.** It listed eight apps, said "and 56
     * more apps", and offered no way to reach any of them - on the Agni 2, 56
     * of 64 apps were counted and could not be acted on. A count you cannot
     * act on is a report, and this card exists to be a control.
     *
     * Asserts the ninth is genuinely absent first, with the first as a
     * known-present anchor, so it cannot pass against a blank card.
     */
    @Test
    fun everyAppHoldingASwitchCanBeReached() {
        render(fullyLoaded().copy(hiddenSwitches = manyHolders(12)))

        // Anchor: the card rendered and the early rows are there.
        scrollTo("com.example.holder01")
        compose.onNodeWithText("com.example.holder01").assertIsDisplayed()
        // The tail is not reachable yet.
        compose.onAllNodesWithText("com.example.holder12").assertCountEquals(0)

        scrollTo("Show all 12 apps")
        compose.onNodeWithText("Show all 12 apps").performClick()

        scrollTo("com.example.holder12")
        compose.onNodeWithText("com.example.holder12").assertIsDisplayed()
    }

    /** Expanded is a view, not a one-way door. */
    @Test
    fun theExpandedListCanBeCollapsedAgain() {
        render(fullyLoaded().copy(hiddenSwitches = manyHolders(12)))

        scrollTo("Show all 12 apps")
        compose.onNodeWithText("Show all 12 apps").performClick()
        scrollTo("Show fewer")
        compose.onNodeWithText("Show fewer").performClick()

        scrollTo("com.example.holder01")
        compose.onNodeWithText("com.example.holder01").assertIsDisplayed()
        compose.onAllNodesWithText("com.example.holder12").assertCountEquals(0)
    }

    /**
     * A card whose rows all fit says nothing about showing more - an expand
     * control over a complete list is a control that does nothing.
     */
    @Test
    fun aCardThatAlreadyFitsOffersNoExpandControl() {
        render(fullyLoaded().copy(hiddenSwitches = manyHolders(3)))

        scrollTo("com.example.holder03")
        compose.onNodeWithText("com.example.holder03").assertIsDisplayed()
        compose.onAllNodesWithText("Show all", substring = true).assertCountEquals(0)
    }

    /**
     * The two cards on this screen have to answer visibly different questions.
     *
     * Until 2026-09-14 the special-access card read "What apps can do to you"
     * and the permissions card, one card below it, read "What apps can do".
     * Both plain language, both accurate, and side by side they told a person
     * scanning the screen nothing about which was which.
     */
    @Test
    fun theTwoAuditCardsDoNotHaveNearIdenticalTitles() {
        render(fullyLoaded())

        scrollTo("Who holds each permission")
        compose.onNodeWithText("Who holds each permission").assertIsDisplayed()
        // The old title, which differed from the card above by two words.
        compose.onAllNodesWithText("What apps can do").assertCountEquals(0)
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
                    onRevokeAccess = { _, _ -> },
                    onRevokeSwitch = { _, _ -> },
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

    /** [count] apps holding clipboard read, named so order is checkable. */
    private fun manyHolders(count: Int): List<HiddenSwitchHolder> =
        (1..count).map {
            HiddenSwitchHolder(
                packageName = "com.example.holder%02d".format(it),
                switches = setOf(HiddenSwitch.READ_CLIPBOARD),
                isSystem = false,
            )
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
            revoking = null,
            hiddenSwitches = listOf(
                HiddenSwitchHolder(
                    packageName = "com.example.copier",
                    switches = setOf(HiddenSwitch.READ_CLIPBOARD),
                    isSystem = false,
                ),
            ),
            hiddenSwitchesCouldNotTell = null,
            revokingSwitch = null,
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
        )
    }
}
