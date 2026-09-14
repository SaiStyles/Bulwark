package com.bulwark.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bulwark.app.firewall.AlwaysOn
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.AppAccess
import com.bulwark.app.permissions.HiddenSwitch
import com.bulwark.app.permissions.HiddenSwitchHolder
import com.bulwark.app.permissions.audit
import com.bulwark.app.permissions.summarise
import com.bulwark.app.security.MatchSignal
import com.bulwark.app.security.MonitoringFinding
import com.bulwark.app.security.MonitoringKind
import com.bulwark.app.ui.theme.BulwarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the screens that cannot be photographed, so they can be looked at.
 *
 * **Audit and Activity carry `FLAG_SECURE`**, deliberately - a timestamped
 * record of who used the microphone is exactly what somebody reading over a
 * shoulder must not get. The cost is that `adb exec-out screencap` returns
 * black for them, so the two screens most in need of design review are the two
 * nobody can see.
 *
 * The way out is not to weaken the flag for convenience. Compose tests render
 * in an ordinary test activity that was never marked sensitive, and the thing
 * under review is the *content* composable anyway - the same function the real
 * screen calls. So this renders it with fixture data and writes a PNG.
 *
 * Not a screenshot test: nothing here asserts, and there are no goldens to
 * drift. It is a camera pointed at a room with no windows. Run it, pull the
 * files, look at them:
 *
 *     adb shell am instrument -w -e class \
 *       com.bulwark.app.ui.ScreenshotHarness \
 *       com.bulwark.app.test/androidx.test.runner.AndroidJUnitRunner
 *     adb exec-out run-as com.bulwark.app cat files/shots/audit-found.png > audit.png
 *
 * Fixtures, never the device's real state: these land in a file that leaves
 * the phone, and `threat-model.md` is unambiguous about what a file naming
 * someone's apps can cost them.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotHarness {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun auditWithAFinding() {
        compose.setContent {
            BulwarkTheme {
                AuditContent(
                    readings = readings(),
                    onRevokeAccess = { _, _ -> },
                    onRevokeSwitch = { _, _ -> },
                    onOpenVpnSettings = {},
                    onOpenCertificateSettings = {},
                    onAllowVpn = {},
                    onOpenShizuku = {},
                    onOpenDeveloperOptions = {},
                    onOpenPermission = {},
                    onRevokeAcross = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        capture("audit-found")
    }

    @Test
    fun auditOnACleanPhone() {
        compose.setContent {
            BulwarkTheme {
                AuditContent(
                    readings = readings(clean = true),
                    onRevokeAccess = { _, _ -> },
                    onRevokeSwitch = { _, _ -> },
                    onOpenVpnSettings = {},
                    onOpenCertificateSettings = {},
                    onAllowVpn = {},
                    onOpenShizuku = {},
                    onOpenDeveloperOptions = {},
                    onOpenPermission = {},
                    onRevokeAcross = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        capture("audit-clean")
    }

    /** Invented apps, invented findings. Nothing from the device it runs on. */
    private fun readings(clean: Boolean = false): AuditReadings {
        val apps = listOf(
            AppAccess(
                packageName = "com.example.tracker",
                accesses = setOf(Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
                isSystem = false,
            ),
            AppAccess(
                packageName = "com.example.notes",
                accesses = setOf(Access.ALL_FILES),
                isSystem = false,
            ),
        )
        return AuditReadings(
            access = apps.audit(),
            accessSummary = apps.summarise(),
            accessUnavailable = emptyList(),
            ratSignals = emptyList(),
            revoking = null,
            hiddenSwitches = listOf(
                HiddenSwitchHolder(
                    packageName = "com.example.messenger",
                    switches = setOf(HiddenSwitch.READ_CLIPBOARD, HiddenSwitch.WRITE_CLIPBOARD),
                    isSystem = false,
                ),
                HiddenSwitchHolder(
                    packageName = "com.example.browser",
                    switches = setOf(HiddenSwitch.READ_CLIPBOARD),
                    isSystem = false,
                ),
            ),
            hiddenSwitchesCouldNotTell = null,
            revokingSwitch = null,
            addedCertificates = emptyList(),
            monitoring = if (clean) {
                emptyList()
            } else {
                listOf(
                    MonitoringFinding(
                        packageName = "com.example.systemservice",
                        names = listOf("ExampleSpy"),
                        kinds = setOf(MonitoringKind.STALKERWARE),
                        signals = setOf(MatchSignal.SIGNING_CERTIFICATE),
                    ),
                )
            },
            monitoringChecked = true,
            wirelessDebuggingOn = false,
            interrupted = emptyList(),
            permissionGroups = emptyList(),
            permissionsReadFailed = false,
            systemPackages = emptySet(),
            refinedPermissions = emptySet(),
            blockedApps = emptySet(),
            firewallConsentNeeded = false,
            aVpnIsUp = false,
            alwaysOn = AlwaysOn.CANNOT_TELL,
            lockdown = false,
            shizukuReady = true,
        )
    }
}
