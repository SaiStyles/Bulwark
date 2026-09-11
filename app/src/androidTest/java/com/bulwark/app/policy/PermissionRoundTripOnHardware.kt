package com.bulwark.app.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import com.bulwark.app.shizuku.RuntimePermissionAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one test in this project that **changes the phone**. Run deliberately.
 *
 * Deliberately *not* part of `PrivilegedSmokeTest`, which is read-only and says
 * so: "those need `safety-rules.md`'s guards and a human who chose the package,
 * and a test suite is neither." Both conditions are met here and only here -
 * the guards run because this calls the real [PermissionActions], and a human
 * chose the package.
 *
 * ## The target, and who approved it
 *
 * `com.jio.myjio`, approved by SAI on 2026-09-11 and recorded in
 * `context/devices/lava-agni-2.md`. A carrier app holding ten revocable
 * runtime permissions, on a phone whose owner does not use it.
 *
 * `com.android.egg` - the standing target for everything else - cannot test a
 * revoke at all: its `POST_NOTIFICATIONS` is `SYSTEM_FIXED` and both its
 * storage permissions are already denied. Nothing revocable on it.
 *
 * **It does not run against anything else.** The package is a constant, not a
 * parameter, so this cannot be pointed at an app nobody agreed to.
 *
 * ## What it proves that a unit test cannot
 *
 * The whole stack below the authentication prompt, against a real phone: the
 * guard order, the privileged binder call with the signature this device
 * actually has, the read-back that catches a call the platform ignored, and
 * the real SQLite log. The prompt itself is the one thing left out, because it
 * needs a fingerprint and a test suite does not have one - that is checked by
 * a person using the app.
 *
 * ## It puts the permission back
 *
 * In a `finally`, and then asserts the restore landed. A test that leaves
 * someone's phone changed is not a test, it is a change.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRoundTripOnHardware {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Skipped unless someone asks for it by name:
     *
     *     -e bulwark.destructive true
     *
     * Without this, `connectedAndroidTest` would change a real phone every
     * time anyone ran the suite - which is precisely the thing
     * `safety-rules.md` refuses, since a test runner is not a human who chose
     * the package. The flag *is* that human decision, written down.
     */
    private fun requireDeliberateRun() {
        val asked = InstrumentationRegistry.getArguments()
            .getString("bulwark.destructive") == "true"
        assumeTrue(
            "SKIPPED: this test changes a real phone. Run it deliberately with " +
                "-e bulwark.destructive true, and only against the approved target.",
            asked,
        )
    }

    @Test
    fun revokeAndRestoreOnTheApprovedTarget() {
        requireDeliberateRun()
        val actions = PermissionActions(ActionJournal(SqliteActionLog(context)))

        assertTrue(
            "no permission API resolved on this device; nothing below can run",
            RuntimePermissionAccess.canChangePermissions,
        )
        assertTrue(
            "$TARGET must hold $PERMISSION before this can test taking it away",
            RuntimePermissionAccess.isGranted(TARGET, PERMISSION),
        )

        var revoked = false
        try {
            actions.revoke(TARGET, PERMISSION)
            revoked = true

            // The state, read back from the platform rather than assumed. This
            // is the assertion the whole read-back design exists for: on
            // 2026-09-11 a revoke returned success and changed nothing.
            assertFalse(
                "the platform reported no error but $PERMISSION is still granted",
                RuntimePermissionAccess.isGranted(TARGET, PERMISSION),
            )

            val rows = ActionJournal(SqliteActionLog(context)).history()
                .filter { it.packageName == TARGET && it.permission == PERMISSION }
            assertTrue("the revoke must be recorded", rows.isNotEmpty())
            assertEquals(
                "the last row must be a recorded success",
                Phase.SUCCEEDED, rows.last().phase,
            )
            assertEquals(ActionKind.REVOKE_PERMISSION, rows.last().kind)
        } finally {
            if (revoked) {
                actions.grant(TARGET, PERMISSION)
            }
        }

        assertTrue(
            "the phone must be left as it was found",
            RuntimePermissionAccess.isGranted(TARGET, PERMISSION),
        )
    }

    private companion object {
        /** Approved by SAI, 2026-09-11. Not a parameter, on purpose. */
        const val TARGET = "com.jio.myjio"

        /**
         * Granted and `USER_SET` on this device, so it is a user's own choice
         * being restored rather than a vendor default being overwritten.
         */
        const val PERMISSION = "android.permission.ACCESS_FINE_LOCATION"
    }
}
