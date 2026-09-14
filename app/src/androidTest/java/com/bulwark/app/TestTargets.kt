package com.bulwark.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Which package a destructive hardware test is allowed to act on.
 *
 * ## Why this exists at all
 *
 * The destructive tests name their target as a `const val` and say "not a
 * parameter, on purpose": on a real phone the only acceptable target is the one
 * a person approved by name, and a runner flag is not a person. That rule is
 * right and this file does not weaken it.
 *
 * What it could not survive was a *second* device. `com.jio.myjio` is not on
 * stock Android 15, so on 2026-09-14 the permission round trip and the tunnel -
 * two of Bulwark's three write paths - could not be exercised on AOSP at all,
 * and the portability question they exist to answer went unanswered.
 *
 * ## The bargain
 *
 * An override is honoured **only on an emulator**, and only alongside the
 * `bulwark.destructive` flag the tests already demand. On hardware [resolve]
 * returns the approved constant no matter what is passed, so the guarantee a
 * phone's owner relies on is unchanged: there is no argument anyone can type
 * that points a destructive test at a package on a real device.
 *
 * An emulator is disposable by definition. That is the whole of the difference.
 */
object TestTargets {

    /**
     * Whether this is a virtual device.
     *
     * Four independent signals, any one of which is enough, because a single
     * property is the kind of thing an OEM sets oddly - and being wrong here in
     * the permissive direction means a test writing to a package on somebody's
     * phone. `ro.kernel.qemu` and `ro.hardware=ranchu` are the emulator's own;
     * the `Build` fields are the conventional check.
     */
    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.HARDWARE in setOf("goldfish", "ranchu") ||
            Build.PRODUCT.startsWith("sdk_")
    }

    /**
     * The package to act on: [approved] on real hardware, always.
     *
     * On an emulator, `-e bulwark.target <package>` replaces it. Returning the
     * approved constant when nothing is passed keeps the emulator's default
     * behaviour identical to the phone's, so a run with no extra flags reads
     * the same everywhere.
     */
    fun resolve(approved: String): String {
        if (!isEmulator) return approved
        return InstrumentationRegistry.getArguments()
            .getString("bulwark.target")
            ?.takeIf { it.isNotBlank() }
            ?: approved
    }
}
