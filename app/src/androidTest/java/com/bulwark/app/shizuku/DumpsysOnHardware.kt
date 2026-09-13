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

        // Past the pipe buffer, which is the whole point of this test.
        val bytes = lines.sumOf { it.length + 1 }
        assertTrue(
            "Expected a dump larger than a pipe buffer, got $bytes bytes - if this " +
                "shrank, this test is no longer proving anything about deadlocking",
            bytes > 64 * 1024,
        )

        // Shape, not contents: what is on SAI's phone is SAI's business and
        // changes by the hour. What is pinned is that the structure the parser
        // stands on is still there.
        assertTrue(
            "Expected package headers in the dump",
            lines.any { it.trim().startsWith("Package ") },
        )

        // And it parses into something, rather than into a pile of unreadable
        // rows - the failure mode that would matter on a different OEM.
        val parsed = AppOpLedger.parse(lines, AppOpLedger.Op.MICROPHONE)
        assertTrue(
            "Every record was unreadable, so the format has moved: $parsed",
            !parsed.readNothing,
        )

        assertTrue("Reading 149 KB took ${tookMs}ms, which is too slow for a screen", tookMs < 5_000)
    }
}
