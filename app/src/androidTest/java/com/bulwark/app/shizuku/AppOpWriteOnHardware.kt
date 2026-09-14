package com.bulwark.app.shizuku

import android.app.AppOpsManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
 * `com.android.egg` is the Android easter egg, pre-approved for testing. The
 * op is `VIBRATE`, which is about as inert as an op gets. The value is read
 * first and put back afterwards, and the restore is asserted rather than hoped
 * for.
 *
 * ## Why not `SYSTEM_ALERT_WINDOW`, which is what the layer actually wants
 *
 * It was the first choice and it produced a **false negative**, 2026-09-14:
 * `setMode` returned without throwing and `checkOperation` still read
 * `MODE_DEFAULT`. That looks exactly like "app ops cannot be written through
 * our binder", and it is not.
 *
 * `cmd appops set com.android.egg SYSTEM_ALERT_WINDOW ignore` - **shell
 * itself, no Bulwark involved** - does the same nothing, while `VIBRATE` on
 * the same package sets and reads back cleanly. So the refusal belongs to that
 * op on that package, not to the mechanism. The egg is a `SYSTEM` app that
 * never requests the overlay permission, and the platform does not persist an
 * overlay mode for a package with no claim to one.
 *
 * The instrument was very nearly blamed for the reading, again
 * (`_shared/lessons/instrument.md`). **The discriminator was asking the phone
 * the same question a different way** - if shell cannot do it either, it was
 * never ours. Whether the four special-access ops are writable *on packages
 * that hold them* is a separate question and is not answered here.
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
        /** Pre-approved for testing. */
        const val TARGET = "com.android.egg"

        /**
         * There is no `OPSTR_VIBRATE`, so the platform's own op string is used
         * directly - the same approach `AppOpsAccess` takes for the two special
         * accesses with no public constant. The *string* is the stable part;
         * the int codes are what get reordered between releases.
         */
        const val VIBRATE = "android:vibrate"

        /**
         * The one special access any pre-approved app actually holds on this
         * phone: `com.jio.myjio` has all-files access, read 2026-09-14.
         *
         * The other three read `default` on both approved apps, most with a
         * `rejectTime` - they asked and were refused, which is not the same as
         * holding it and is not a useful target for a write.
         */
        const val ALL_FILES = "android:manage_external_storage"
        const val JIO = "com.jio.myjio"

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
        val code = opCode(VIBRATE)
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

    /**
     * The question the layer actually needs answered: can a **special access**
     * be written on an app that genuinely holds one?
     *
     * `VIBRATE` on the easter egg proves the mechanism and nothing else. This
     * is a real app, a real capability, and the op Bulwark would want to take
     * away - so it is the first result that means anything for the feature.
     *
     * ## The trap this is written around
     *
     * The phone reports jio's all-files access as **`Uid mode:`**, not as a
     * package mode. `setMode` sets the *package* entry, and a package entry
     * does not necessarily override a uid-level one - so a package write could
     * land, read back unchanged, and look exactly like the false negative
     * `SYSTEM_ALERT_WINDOW` already produced. `setUidMode` is the other door,
     * and this tries the package first and then the uid, reporting which one
     * moved the answer.
     *
     * Restores in a `finally`, through both doors, and the restore is checked
     * afterwards. If this ever leaves a mark, the manual undo is
     * `cmd appops set --uid com.jio.myjio MANAGE_EXTERNAL_STORAGE allow`.
     */
    @Test
    fun aSpecialAccessCanBeWrittenOnAnAppThatHoldsIt() {
        requireShizuku()
        requireDeliberateRun()

        val service = appOpsService()
        val code = opCode(ALL_FILES)

        // Absent target is a skip, not a failure. It crashed here with an
        // opaque "Could not read the uid" on stock Android 15, 2026-09-14:
        // the app-op path was fine, only the phone-specific package was
        // missing. A test that cannot tell "this platform is broken" from
        // "this package is not installed" reports the wrong one of the two.
        val jioUid = runCatching { privilegedUidOf(JIO) }.getOrNull()
        assumeTrue("SKIPPED: $JIO is not on this device", jioUid != null)
        val uid = jioUid!!

        val before = checkOperation(service, code, uid, JIO)
        assumeTrue(
            "SKIPPED: $JIO does not hold all-files access on this phone " +
                "(mode=$before), so there is nothing to take away.",
            before == AppOpsManager.MODE_ALLOWED,
        )

        var afterPackageWrite: Int? = null
        var afterUidWrite: Int? = null
        try {
            setMode(service, code, uid, JIO, AppOpsManager.MODE_IGNORED)
            afterPackageWrite = checkOperation(service, code, uid, JIO)

            if (afterPackageWrite == before) {
                // The package door did nothing. Try the uid door before
                // concluding anything - see the class note.
                setUidMode(service, code, uid, AppOpsManager.MODE_IGNORED)
                afterUidWrite = checkOperation(service, code, uid, JIO)
            }
        } finally {
            // Both doors, always. This is a real app and a real capability.
            //
            // The **uid** door goes back to what was read; the **package** door
            // goes back to MODE_DEFAULT, which is "no package override" - not
            // to `before`. `checkOperation` reports the *effective* mode, and
            // jio's effective allow comes from the uid entry while its package
            // entry was unset. Restoring the effective value through the
            // package door leaves an explicit `allow` where the phone had
            // `default`: same access, different state, and this test does not
            // get to leave even that behind. Found by diffing an independent
            // `cmd appops get` either side of the first run.
            runCatching { setUidMode(service, code, uid, before) }
            runCatching { setMode(service, code, uid, JIO, AppOpsManager.MODE_DEFAULT) }
        }

        val restored = checkOperation(service, code, uid, JIO)
        assertEquals(
            "all-files access was NOT put back on $JIO - restore by hand with: " +
                "cmd appops set --uid $JIO MANAGE_EXTERNAL_STORAGE allow",
            before,
            restored,
        )

        val landed = afterUidWrite ?: afterPackageWrite
        assertEquals(
            "Neither door moved it. before=$before package=$afterPackageWrite " +
                "uid=$afterUidWrite",
            AppOpsManager.MODE_IGNORED,
            landed,
        )

        // **Which door mattered, asserted rather than assumed.** This is the
        // part the feature has to know. `checkOperation` resolves the uid entry
        // first and only falls through to the package entry when the uid one is
        // MODE_DEFAULT - so for an op the phone reports as a `Uid mode:`, the
        // package door writes something real and changes nothing anyone can
        // observe. Exactly the shape of the false negative already recorded
        // above, one level deeper.
        assertEquals(
            "The package door moved a uid-mode op, so the note below is wrong " +
                "and the feature can use setMode alone. package=$afterPackageWrite",
            before,
            afterPackageWrite,
        )
        assertEquals(
            "The uid door is what landed it, and the feature needs setUidMode.",
            AppOpsManager.MODE_IGNORED,
            afterUidWrite,
        )
    }

    /**
     * **Reading the uid entry, against a known case of each kind.**
     *
     * The check that would have caught the bug this file's first version
     * shipped: `checkOperation(code, uid, null)` answered `MODE_IGNORED` for
     * every app, so `doorFor` said `UID` universally and a package-held op took
     * the wide door.
     *
     * A single app could not have exposed that - it needs one of each, and the
     * Agni 2 has both. `com.brave.browser` holds install-unknown-apps at
     * package level and prints no `Uid mode:` line at all. `com.binance.dev`
     * was given a uid entry by Bulwark itself on 2026-09-14.
     *
     * Skips rather than asserting when the phone is not in that state, because
     * this pins a mechanism and must not fail for being run somewhere else.
     */
    @Test
    fun theUidEntryIsReadSeparatelyFromThePackageEntry() {
        requireShizuku()

        val overlay = AppOpsWriter.opCode("android:system_alert_window")
        val install = AppOpsWriter.opCode("android:request_install_packages")

        val braveUid = runCatching { AppOpsWriter.uidOf("com.brave.browser") }.getOrNull()
        assumeTrue("SKIPPED: com.brave.browser is not on this phone", braveUid != null)

        // Package-held: no uid entry, so the uid read must say DEFAULT and the
        // door must be the narrow one.
        assumeTrue(
            "SKIPPED: brave does not hold install-unknown-apps here",
            AppOpsWriter.mode(install, braveUid!!, "com.brave.browser") == AppOpsWriter.MODE_ALLOWED,
        )
        assertEquals(
            "a package-held op must read as having no uid entry",
            AppOpsWriter.MODE_DEFAULT,
            AppOpsWriter.uidMode(install, braveUid),
        )
        assertEquals(
            AppOpsWriter.Door.PACKAGE,
            AppOpsWriter.doorFor(install, braveUid),
        )

        val binanceUid = runCatching { AppOpsWriter.uidOf("com.binance.dev") }.getOrNull()
        assumeTrue("SKIPPED: com.binance.dev is not on this phone", binanceUid != null)

        // Uid-held: Bulwark put this entry there, so the uid read must see it.
        val binanceUidMode = AppOpsWriter.uidMode(overlay, binanceUid!!)
        assumeTrue(
            "SKIPPED: binance has no uid entry for overlay on this phone",
            binanceUidMode != AppOpsWriter.MODE_DEFAULT,
        )
        assertEquals(
            "a uid entry that exists must be read, not reported as default",
            AppOpsWriter.MODE_IGNORED,
            binanceUidMode,
        )
        assertEquals(AppOpsWriter.Door.UID, AppOpsWriter.doorFor(overlay, binanceUid))
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

    /** The uid-level door, for ops the phone reports as a `Uid mode:`. */
    private fun setUidMode(service: Any, code: Int, uid: Int, mode: Int) {
        PrivilegedBinder.invokeHidden(
            Class.forName(APP_OPS_INTERFACE), service, "setUidMode", code, uid, mode,
        )
    }
}
