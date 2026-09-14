package com.bulwark.app.shizuku

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bulwark.app.observability.AppOpLedger
import rikka.shizuku.Shizuku

/**
 * Does the `DUMP` transaction actually get through Shizuku on this phone?
 *
 * **The spike, committed.** `observability.md` measured the six sources by
 * running `dumpsys` from a PC over adb, which proves the *data* exists and
 * proves nothing about whether Bulwark can reach it: adb is a shell, and
 * `DoneForNow.kt` records that Bulwark has no shell and will not grow one.
 * The open question was always the mechanism, and lesson 2 says a check nobody
 * has watched is not a check.
 *
 * **Read-only, and nothing else** - same contract as `PrivilegedSmokeTest`.
 * `DumpsysAccess.Dump` is a closed set of pure reads, which is the guardrail
 * that makes a test like this safe to run against someone's phone at all.
 *
 * Skips rather than fails when Shizuku is not granted, because a fresh install
 * drops the grant - see `PrivilegedSmokeTest` for why that cost a deletion to
 * learn.
 */
@RunWith(AndroidJUnit4::class)
class DumpsysOnHardware {

    private fun requireShizuku() {
        val ready = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        assumeTrue(
            "SKIPPED: Shizuku is not running, or has not granted Bulwark access. " +
                "A fresh install drops the grant - re-grant in the Shizuku app and run again.",
            ready,
        )
    }

    @Test
    fun theDozeWhitelistCanBeReadThroughOurBinder() {
        requireShizuku()

        val reading = DumpsysAccess.read(DumpsysAccess.Dump.DOZE_WHITELIST)

        // Reported in full on failure: if the transaction is refused, the
        // reason is the finding, and a bare assertion would throw it away.
        assertTrue(
            "Expected lines, got: $reading",
            reading is DumpsysAccess.Reading.Lines,
        )
        val lines = (reading as DumpsysAccess.Reading.Lines).lines
        assertTrue("The whitelist came back empty, which this phone is not", lines.isNotEmpty())

        // Shape, not contents. The phone's own exemptions are SAI's business
        // and will change; what this pins is that the format is the CSV the
        // research note measured, so the parser has something to stand on.
        val looksLikeCsv = lines.all { it.count { c -> c == ',' } >= 2 }
        assertTrue("Expected `source,package,uid` rows, got: ${lines.take(3)}", looksLikeCsv)
    }

    /**
     * The read that could actually deadlock.
     *
     * `deviceidle whitelist` is a few kilobytes - well inside a pipe's 64 KiB
     * buffer, so it would pass even if `DumpsysAccess` drained the pipe *after*
     * the transaction instead of during it. `--op RECORD_AUDIO` is about 149 KB
     * on this phone, comfortably past that buffer, so this is the first read
     * that proves the concurrent drain is real. If the reader were started late
     * this test would hang rather than fail, which is why it is bounded.
     *
     * Also the first read big enough to say whether this is fast enough to sit
     * behind a screen at all - `observability.md` says to measure that before
     * promising one.
     */
    @Test
    fun theMicrophoneLedgerIsReadWholeWithoutDeadlocking() {
        requireShizuku()

        val started = System.currentTimeMillis()
        val reading = DumpsysAccess.read(DumpsysAccess.Dump.MICROPHONE_USES)
        val tookMs = System.currentTimeMillis() - started

        assertTrue("Expected lines, got: $reading", reading is DumpsysAccess.Reading.Lines)
        val lines = (reading as DumpsysAccess.Reading.Lines).lines


        // Shape, not contents: what is on SAI's phone is SAI's business and
        // changes by the hour. What is pinned is that the structure the parser
        // stands on is still there.
        //
        // `Uid N:` blocks are the part every device has - stock Android 15
        // emits 432 of them for an op no app has ever used - so this is the
        // assertion that travels.
        assertTrue(
            "This is not an appops dump at all: ${lines.take(3)}",
            lines.any { it.trim().startsWith("Current AppOps Service state") } ||
                lines.any { it.trim().startsWith("Uid ") },
        )

        // A dump that parses into nothing readable is always a failure, on any
        // device: `readNothing` means rows were seen and none could be read,
        // which is the format having moved. Zero package blocks is a different
        // answer - it is "nothing has used the microphone here", which is true
        // on a fresh emulator and which `readNothing` deliberately does not
        // claim.
        val parsed = AppOpLedger.parse(lines, AppOpLedger.Op.MICROPHONE)
        assertTrue(
            "Every record was unreadable, so the format has moved: $parsed",
            !parsed.readNothing,
        )

        assertTrue("Reading the dump took ${tookMs}ms, which is too slow for a screen", tookMs < 5_000)

        // -- premises about the device, not results about the code -----------
        //
        // Both of these are last on purpose: every assertion above has already
        // run by the time either is reached, so a skip here still means the
        // read, the shape, the parse and the timing were all proven.

        // The parser proof proper needs at least one `Package` block to chew
        // on, and whether one exists is a property of the device's history.
        // Stock Android 15, 2026-09-14: zero for RECORD_AUDIO and CAMERA, two
        // for COARSE_LOCATION - same format, nothing recorded against it. So
        // this is an assumption, and the format check above is the assertion.
        assumeTrue(
            "SKIPPED (the read, the shape and the parse all passed): no app on " +
                "this device has a package-level microphone entry, so there is " +
                "no row for the parser to prove itself on.",
            lines.any { it.trim().startsWith("Package ") },
        )

        // Past the pipe buffer - and **last on purpose**, because it is a
        // premise about the device, not a result about the code.
        //
        // How big this dump is depends on how many apps have touched the
        // microphone, which is a property of the phone. On stock Android 15
        // with a fresh image it was 50 KB, inside the buffer, and asserting
        // turned "this device cannot prove the drain" into "the drain is
        // broken" - a different claim, and a false one (2026-09-14).
        //
        // Everything above has already run by the time this is reached, so the
        // reading, the shape and the parse are still proven on a small dump;
        // only the deadlock proof needs a device with enough history. On the
        // Agni 2 it is ~149 KB and this holds.
        val bytes = lines.sumOf { it.length + 1 }
        assumeTrue(
            "SKIPPED (everything except the deadlock proof passed): this device's " +
                "microphone dump is $bytes bytes, inside a pipe's 64 KiB buffer, " +
                "so it cannot show the concurrent drain. The drain is NOT " +
                "verified here.",
            bytes > 64 * 1024,
        )
    }
}
