package com.bulwark.app.shizuku

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bulwark.app.permissions.Access
import com.bulwark.app.permissions.Revocable
import com.bulwark.app.permissions.revocable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the privilege chain still resolve? **Read-only, and nothing else.**
 *
 * Deliberately shallow. The privilege layer's realistic failure mode is not a
 * logic error - it is an Android update moving or blocking a non-SDK method, at
 * which point every call throws at once. What is worth automating is noticing
 * that on the next platform bump rather than at the next hardware session.
 *
 * Everything here reads. Nothing disables, enables, revokes or grants: those
 * need `safety-rules.md`'s guards and a human who chose the package, and a test
 * suite is neither. That holds for the permission calls too - the suite checks
 * that the revoke path *resolves*, never that it works, because proving it
 * works means changing something on a real phone.
 *
 * ## Running these needs a step the suite cannot take itself
 *
 * **`connectedAndroidTest` fresh-installs Bulwark, and a fresh install drops
 * Shizuku's permission grant.** The first run after any install therefore finds
 * no access and skips - not because Shizuku is down, but because the suite
 * about to test it has just revoked its own access.
 *
 * That cost a deletion to learn. These tests were written, reported "Shizuku is
 * not running" on a phone where it plainly was, were diagnosed as a binder race,
 * and removed. The binder was fine. `firstInstallTime == lastUpdateTime` was
 * the tell. **Third time in one session that the instrument was blamed for the
 * reading** (`lessons.md` lesson 2), and the first time it cost working code.
 *
 * The flow: run the suite, re-grant Bulwark in the Shizuku app, run it again.
 * Clunky and honest. Not automatable - the grant is a deliberate human act,
 * which is the entire point of Shizuku.
 */
@RunWith(AndroidJUnit4::class)
class PrivilegedSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Waits briefly for the binder, then skips with the reason spelled out.
     *
     * The wait is cheap insurance against a genuine race - Shizuku delivers its
     * binder asynchronously during app start - but the common cause is the
     * fresh-install grant loss above, and the message says so rather than
     * leaving the next person to guess at it the way I did.
     *
     * A skip surfaces as a **failure** in this runner, which is the right
     * outcome: a privilege suite reporting green without ever reaching the
     * privilege layer is worse than one going red and saying why.
     */
    private fun requireShizuku() {
        val gateway = ShizukuGateway(context)
        // start() registers the sticky binder-received listener, which is what
        // MainActivity does. refresh() alone only asks.
        gateway.start()
        try {
            val deadline = System.currentTimeMillis() + BINDER_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                gateway.refresh()
                if (gateway.canActNow()) return
                Thread.sleep(POLL_MS)
            }
        } finally {
            gateway.stop()
        }
        assumeTrue(
            "SKIPPED: no Shizuku access after ${BINDER_WAIT_MS}ms. The usual cause " +
                "is NOT that Shizuku is down - it is that this run fresh-installed " +
                "Bulwark, which drops Shizuku's permission grant. Re-grant Bulwark " +
                "in the Shizuku app and run again. The privilege layer was not " +
                "exercised; do not read this suite as passing.",
            false,
        )
    }

    @Test
    fun enumerationReachesPackagesTheAppCannotSeeAlone() {
        requireShizuku()

        val privileged = PrivilegedPackages.list()
        val ownView = context.packageManager.getInstalledPackages(0).size

        assertTrue("privileged enumeration returned nothing", privileged.isNotEmpty())
        // The whole reason this layer exists. If these ever converge, either
        // filtering stopped applying or the privileged path quietly degraded to
        // the app's own view - and the second would be invisible without this.
        assertTrue(
            "privileged view (${privileged.size}) should exceed the app's own ($ownView)",
            privileged.size > ownView,
        )
    }

    @Test
    fun detailedEnumerationClassifiesSystemPackages() {
        requireShizuku()

        val detailed = PrivilegedPackages.listDetailed()
        assertTrue(detailed.isNotEmpty())
        assertTrue("some packages must be system", detailed.any { it.isSystem })
        assertTrue("and some must not", detailed.any { !it.isSystem })
        // isEnabled decides which single action a row offers; all-false would
        // mean every row offering "switch back on" for a working phone.
        assertTrue("most packages should read as enabled", detailed.count { it.isEnabled } > 1)
    }

    @Test
    fun theEnabledStateOfAKnownPackageIsReadable() {
        requireShizuku()

        // Bulwark itself: always present, and reading its own state changes
        // nothing anywhere.
        val state = PackageState.get(context.packageName)
        assertTrue(
            "unexpected enabled-state $state",
            state in listOf(
                PackageState.DEFAULT,
                PackageState.ENABLED,
                PackageState.DISABLED,
                PackageState.DISABLED_USER,
                PackageState.DISABLED_UNTIL_USED,
            ),
        )
    }

    @Test
    fun systemPackageLookupAgreesWithEnumeration() {
        requireShizuku()

        // isSystemPackage is what CommandSafety uses to decide whether the
        // never-remove fragment list applies, so a disagreement here means the
        // guard consulting different facts than the catalog shows.
        val detailed = PrivilegedPackages.listDetailed()
        val aSystemPackage = detailed.first { it.isSystem }.packageName
        assertTrue(
            "$aSystemPackage read as system by enumeration but not by lookup",
            PrivilegedPackages.isSystemPackage(aSystemPackage),
        )
    }

    @Test
    fun appOpsEnumerationResolvesThroughTheBypass() {
        requireShizuku()

        // The chain that needed two attempts: the call cleared the wrapper
        // first try and reading OpEntry.getOp() off the result did not, because
        // the bypass had been applied to the call and not to the accessors on
        // what it returned.
        AppOpsAccess.holders().forEach { (pkg, accesses) ->
            assertTrue("empty package name in app-ops result", pkg.isNotBlank())
            assertTrue("$pkg mapped to no access", accesses.isNotEmpty())
            assertTrue(
                "$pkg mapped to an access app-ops cannot report",
                accesses.all {
                    it in setOf(
                        Access.DRAW_OVER_APPS,
                        Access.USAGE_ACCESS,
                        Access.ALL_FILES,
                        Access.INSTALL_UNKNOWN_APPS,
                    )
                },
            )
        }
    }

    @Test
    fun theAuditRunsAndReportsWhatItCouldNotRead() {
        requireShizuku()

        val result = SpecialAccessReader.read(context, privileged = true)
        // With access, nothing should be unavailable. If something is, the
        // message must say so rather than the audit quietly coming back short.
        assertTrue(
            "sources failed with Shizuku available: ${result.unavailable}",
            result.unavailable.isEmpty(),
        )
    }

    @Test
    fun thePermissionApiResolvesToAKnownShape() {
        // Deliberately **not** behind requireShizuku. Resolution reads class
        // metadata rather than the binder, so this still answers on a run that
        // has just dropped its own grant - and it is the check that decides
        // whether Bulwark offers a revoke control at all.
        val flavour = RuntimePermissionAccess.flavour()
        assertTrue(
            "no permission API Bulwark recognises on this device (API " +
                "${android.os.Build.VERSION.SDK_INT}). Revoke will be offered " +
                "nowhere until the shape is added, which is the intended " +
                "failure - but it means the layer is off on this phone.",
            flavour != null,
        )
        assertTrue(RuntimePermissionAccess.canChangePermissions)
    }

    @Test
    fun ourOwnPermissionsReadBackWithTheirFlags() {
        requireShizuku()

        // Bulwark's own package: always present, and reading it changes
        // nothing. This is what exercises the resolved signature against the
        // real binder - getPermissionFlags is called for every granted row.
        val holdings = RuntimePermissionAccess.holdings(
            context.packageName, context.packageManager,
        )

        assertTrue("Bulwark requests permissions; none came back", holdings.isNotEmpty())
        val biometric = holdings.firstOrNull {
            it.permission == "android.permission.USE_BIOMETRIC"
        }
        assertTrue("USE_BIOMETRIC should be among our own requests", biometric != null)
        // Normal permission, granted at install. Bulwark must read it as
        // something it cannot revoke - if this ever reads as offerable, the
        // protection-level check has stopped working and the app is drawing
        // buttons that cannot do anything.
        assertTrue(
            "USE_BIOMETRIC must not read as revocable",
            !biometric!!.revocable().isOffered,
        )
        assertEquals(Revocable.NOT_RUNTIME, biometric.revocable())
    }

    private companion object {
        const val BINDER_WAIT_MS = 5_000L
        const val POLL_MS = 250L
    }
}
