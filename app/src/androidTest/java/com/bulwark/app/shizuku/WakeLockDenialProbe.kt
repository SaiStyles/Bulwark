package com.bulwark.app.shizuku

import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/**
 * **A spike, not a guarantee.** What the platform actually does when
 * `android:wake_lock` is taken away.
 *
 * `HiddenSwitches` names `WAKE_LOCK` as a candidate and refuses to ship it
 * because "what breaks is a genuine unknown". This is the experiment that
 * answers it, and it is deliberately pointed at **Bulwark's own package**:
 * the question is what denial does to an app that holds a lock, and Bulwark is
 * the one app on any device whose consent is not in doubt.
 *
 * Two things have to be true before this op could ever be offered:
 *
 * 1. **Denial must be inert.** If `acquire()` throws under `MODE_IGNORED`,
 *    revoking this op crashes apps rather than restraining them, and
 *    `safety-rules.md` ends the discussion there.
 * 2. **Denial must actually suppress the lock**, or the switch is theatre.
 *
 * The second cannot be answered from inside the process - `isHeld` is a
 * client-side flag and stays true either way. So the probe holds a lock for a
 * window in each state and says so in logcat; `dumpsys power` is read from
 * outside during those windows. The log lines are the synchronisation.
 *
 * ## What it measured, stock Android 15, 2026-09-14
 *
 * **(1) holds: denial is inert.** `acquire()` threw nothing, and that is now
 * asserted rather than noted.
 *
 * **(2) does not hold, and that settles it.** With the op at `MODE_IGNORED`
 * the platform recorded a `Reject:` in the op ledger and then kept the wake
 * lock anyway - `Wake Locks: size=1`, `mWakeLockSummary=0x1`,
 * `mHoldingWakeLockSuspendBlocker=true`, byte-identical to the allowed window.
 * `android:wake_lock` is an **accounting** op: `PowerManagerService` notes it
 * for battery attribution and does not gate on the answer.
 *
 * So taking it away tells a person their phone can no longer be kept awake by
 * that app, while the app keeps it awake. That is the definition of the
 * overclaim `threat-model.md` forbids, and it is why `HiddenSwitches` does not
 * and should not carry this op.
 *
 * Kept because the question will be asked again, and because whether an OEM
 * enforces what AOSP only accounts for is exactly an n=1 question - 16 seconds
 * on the next device answers it.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockDenialProbe {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun whatDenyingWakeLockActuallyDoes() {
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access.",
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
        )
        assumeTrue(
            "SKIPPED: this denies an app op on this package. Run it deliberately " +
                "with -e bulwark.destructive true.",
            InstrumentationRegistry.getArguments().getString("bulwark.destructive") == "true",
        )

        val power = context.getSystemService(PowerManager::class.java)
        val self = context.packageName
        val uid = Process.myUid()
        val code = AppOpsWriter.opCode(WAKE_LOCK)

        val beforePackage = AppOpsWriter.packageMode(code, uid, self)
        val beforeUid = AppOpsWriter.uidMode(code, uid)
        Log.i(TAG, "$TAG entries before: package=$beforePackage uid=$beforeUid")

        try {
            // Control first. Without it, "no lock in dumpsys" after the denial
            // proves nothing - it could mean the probe never worked at all.
            hold(power, "allowed")

            AppOpsWriter.setPackageMode(code, uid, self, AppOpsWriter.MODE_IGNORED)
            val effective = AppOpsWriter.mode(code, uid, self)
            Log.i(TAG, "$TAG mode after write: $effective (1 = ignored)")

            // The safety question. A throw here is the whole answer.
            val threw = runCatching { hold(power, "denied") }.exceptionOrNull()
            Log.i(TAG, "$TAG acquire under denial threw: ${threw?.javaClass?.name ?: "nothing"}")

            // Asserted, so this stops being a note and starts being a check.
            // If some future platform makes a denied `acquire()` throw, revoking
            // this op would crash apps rather than restrain them, and anything
            // built on the measurement below would have to be withdrawn.
            assertNull(
                "acquire() threw under MODE_IGNORED, so denying this op is no " +
                    "longer inert and nothing may be built on it: $threw",
                threw,
            )
        } finally {
            // Both doors back exactly as found - and only where they moved,
            // which is the rule the clipboard round trip earned.
            if (AppOpsWriter.packageMode(code, uid, self) != beforePackage) {
                AppOpsWriter.setPackageMode(code, uid, self, beforePackage)
            }
            if (AppOpsWriter.uidMode(code, uid) != beforeUid) {
                AppOpsWriter.setUidMode(code, uid, beforeUid)
            }
            Log.i(
                TAG,
                "$TAG entries after restore: package=${AppOpsWriter.packageMode(code, uid, self)} " +
                    "uid=${AppOpsWriter.uidMode(code, uid)}",
            )
        }
    }

    /** Holds a real partial wake lock for [WINDOW_MS], announcing both edges. */
    private fun hold(power: PowerManager, phase: String) {
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
        Log.i(TAG, "$TAG window open: $phase")
        try {
            lock.acquire(WINDOW_MS)
            Log.i(TAG, "$TAG $phase isHeld=${lock.isHeld}")
            Thread.sleep(WINDOW_MS)
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
            Log.i(TAG, "$TAG window closed: $phase")
        }
    }

    private companion object {
        const val TAG = "BULWARK_PROBE"
        const val WAKE_LOCK = "android:wake_lock"

        /** Distinctive, so `dumpsys power` can be grepped for it unambiguously. */
        const val LOCK_TAG = "bulwark:wakelock-probe"
        const val WINDOW_MS = 8_000L
    }
}
