package com.bulwark.app.shizuku

import android.app.AppOpsManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * **Can an app op be written through our binder path?**
 *
 * The question `layers/02-permissions/views.md` has carried since the layer was
 * designed, and the one that decides whether special access can *act* or only
 * *describe*. Reading who holds overlay, usage access, all-files and
 * install-unknown-apps has worked since 2026-09-11; changing one has never been
 * tried.
 *
 * ## Why this lives in the test source set and not in `main`
 *
 * A deliberate choice, and the important part of this file.
 *
 * `safety-rules.md` requires every state change to go through
 * `security/DestructiveActionGuard` for out-of-process authentication and
 * `policy/ActionJournal` so it can be undone. **This has neither**, because it
 * is a measurement rather than a feature. Putting `setMode` into `main` to
 * answer a question would leave an unguarded write in the production tree, and
 * `shizuku/AppOpsAccess.kt` would stop being able to say "entirely read-only" -
 * a claim currently worth something.
 *
 * So the plumbing is duplicated here, deliberately and once. When special
 * access ships an action, it gets built properly through the policy layer, and
 * this file's job is finished.
 *
 * ## What it does to the phone
 *
 * One op, on one pre-approved package, restored in a `finally`.
 *
 * `com.android.egg` is the Android easter egg. It holds no overlay op - the
 * phone reports "Default mode: default" - and it has no use for one, so
 * setting `SYSTEM_ALERT_WINDOW` to `MODE_IGNORED` changes nothing anybody
 * relies on. The value is read first and put back afterwards, and the restore
 * is asserted rather than hoped for.
 *
 * **A no-op write would prove nothing.** Writing the value it already has
 * could "succeed" without anything happening, so this writes a *different*
 * mode and reads it back - `safety-rules.md` rule 6, which exists because
 * `pm revoke` on a `SYSTEM_FIXED` permission once returned no error and changed
 * nothing.
 *
 * Gated behind `-e bulwark.destructive true`, like every other test here that
 * touches the device.
 */
@RunWith(AndroidJUnit4::class)
class AppOpWriteOnHardware {

    private companion object {
        /** Pre-approved for testing, and holds no overlay op to begin with. */
        const val TARGET = "com.android.egg"

        const val APP_OPS_INTERFACE = "com.android.internal.app.IAppOpsService"
        const val APP_OPS_STUB = "com.android.internal.app.IAppOpsService\$Stub"
    }

    private fun requireShizuku() {
        val ready = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access. " +
                "A fresh install drops the grant - re-grant and run again.",
            ready,
        )
    }

    private fun requireDeliberateRun() {
        assumeTrue(
            "SKIPPED: this writes an app op on a real package. Run it deliberately " +
                "with -e bulwark.destructive true.",
            InstrumentationRegistry.getArguments().getString("bulwark.destructive") == "true",
        )
    }

    @Test
    fun anAppOpCanBeWrittenAndReadBackAndPutBack() {
        requireShizuku()
        requireDeliberateRun()

        val service = appOpsService()
        val code = opCode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
        val uid = privilegedUidOf(TARGET)

        val before = checkOperation(service, code, uid, TARGET)
        // Change to something it is not, so the read-back means something.
        val target =
            if (before == AppOpsManager.MODE_IGNORED) AppOpsManager.MODE_ALLOWED
            else AppOpsManager.MODE_IGNORED

        var applied: Int? = null
        try {
            setMode(service, code, uid, TARGET, target)
            applied = checkOperation(service, code, uid, TARGET)
        } finally {
            // Always, even if the assertions below never run. The phone is
            // SAI's and this test does not get to leave a mark on it.
            setMode(service, code, uid, TARGET, before)
        }

        val restored = checkOperation(service, code, uid, TARGET)

        // Reported as one message: if the write is refused, *what the phone did
        // instead* is the finding, and separate assertions would throw it away.
        assertEquals(
            "setMode did not land. before=$before asked=$target readBack=$applied",
            target,
            applied,
        )
        assertNotEquals("The write changed nothing at all", before, applied)
        assertEquals("The op was not put back as it was found", before, restored)
    }

    // -- the plumbing, duplicated here on purpose (see the class note) --------

    private fun appOpsService(): Any {
        val binder = SystemServiceHelper.getSystemService("appops")
            ?: error("App ops service is unavailable")
        return PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_STUB), null, "asInterface", ShizukuBinderWrapper(binder),
        ) ?: error("IAppOpsService.Stub.asInterface returned null")
    }

    /**
     * Op name to the int code this platform uses.
     *
     * Never hardcoded: the ints have been reordered between releases, so a
     * literal would silently address the **wrong op** on another version -
     * which for a *write* is far worse than for the read this borrows from.
     */
    private fun opCode(name: String): Int =
        PrivilegedBinder.invokeHidden(
            Class.forName("android.app.AppOpsManager"), null, "strOpToOp", name,
        ) as? Int ?: error("This platform does not know the op $name")

    /**
     * The package's uid, asked of the privileged PackageManager.
     *
     * Bulwark declares no `QUERY_ALL_PACKAGES` and no `<queries>`, so its own
     * `PackageManager` cannot see `com.android.egg` under targetSdk 37 at all.
     * The privileged one can. Two arities because the flags argument became a
     * `long` in later releases.
     */
    private fun privilegedUidOf(packageName: String): Int {
        val pm = PrivilegedBinder.packageManager()
        val uid = runCatching {
            PrivilegedBinder.callPackageManager(pm, "getPackageUid", packageName, 0L, 0)
        }.getOrNull() ?: runCatching {
            PrivilegedBinder.callPackageManager(pm, "getPackageUid", packageName, 0, 0)
        }.getOrNull()
        return (uid as? Int)?.takeIf { it > 0 }
            ?: error("Could not read the uid of $packageName")
    }

    private fun checkOperation(service: Any, code: Int, uid: Int, packageName: String): Int =
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service, "checkOperation", code, uid, packageName,
        ) as? Int ?: error("checkOperation returned nothing")

    private fun setMode(service: Any, code: Int, uid: Int, packageName: String, mode: Int) {
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service, "setMode", code, uid, packageName, mode,
        )
    }
}
