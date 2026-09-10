package com.bulwark.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported file, asserted.
 *
 * This is the artifact a user reads when something broke, and possibly the one
 * they paste into a public forum. Both properties below have been got wrong by
 * real tools: an export nobody can read, and an export that carries more than
 * the user meant to share.
 */
class ActionLogExportTest {

    private fun record(
        id: Long,
        kind: ActionKind,
        phase: Phase,
        pkg: String = "com.oem.bloat",
        attemptId: Long? = null,
        detail: String? = null,
        previousState: Int? = null,
        userId: Int = 0,
    ) = ActionRecord(
        id = id,
        atEpochMillis = 1_757_500_000_000L + id,
        packageName = pkg,
        kind = kind,
        phase = phase,
        userId = userId,
        previousState = previousState,
        attemptId = attemptId,
        detail = detail,
    )

    @Test
    fun `an empty log says so plainly rather than printing a bare header`() {
        val text = ActionLogExport.render(emptyList(), appVersion = "0.1.0")
        assertTrue(text.contains("Nothing has been changed on this device"))
    }

    @Test
    fun `each action appears with a plain-language verb, not an enum name`() {
        val text = ActionLogExport.render(
            listOf(
                record(1, ActionKind.DISABLE, Phase.ATTEMPTED),
                record(2, ActionKind.DISABLE, Phase.SUCCEEDED, attemptId = 1),
            ),
            appVersion = "0.1.0",
        )
        assertTrue("must name the package", text.contains("com.oem.bloat"))
        assertTrue("must read as English", text.contains("switched off"))
        assertFalse("must not leak the enum constant", text.contains("DISABLE"))
    }

    @Test
    fun `interrupted actions are called out at the top, not buried in the list`() {
        // Someone opening this file after their phone misbehaved needs the
        // ambiguous cases first. Buried at entry 400 they may as well not be
        // recorded.
        val text = ActionLogExport.render(
            listOf(
                record(1, ActionKind.DISABLE, Phase.SUCCEEDED, pkg = "com.done", attemptId = 99),
                record(2, ActionKind.UNINSTALL, Phase.ATTEMPTED, pkg = "com.stopped.midway"),
            ),
            appVersion = "0.1.0",
        )
        val warningAt = text.indexOf("INTERRUPTED")
        val listAt = text.indexOf("---")
        assertTrue("must warn about interrupted actions", warningAt >= 0)
        assertTrue("warning must come before the entry list", warningAt < listAt)
        assertTrue(text.contains("com.stopped.midway"))
        assertTrue(
            "must say Bulwark does not know, rather than guessing",
            text.contains("may or may not have applied"),
        )
    }

    @Test
    fun `a failure carries its reason into the export`() {
        val text = ActionLogExport.render(
            listOf(
                record(1, ActionKind.UNINSTALL, Phase.ATTEMPTED),
                record(
                    2, ActionKind.UNINSTALL, Phase.FAILED, attemptId = 1,
                    detail = "IllegalStateException: Shizuku died",
                ),
            ),
            appVersion = "0.1.0",
        )
        assertTrue(text.contains("FAILED"))
        assertTrue(text.contains("Shizuku died"))
    }

    @Test
    fun `the previous enabled-state is exported so an undo can be checked by hand`() {
        val text = ActionLogExport.render(
            listOf(record(1, ActionKind.DISABLE, Phase.ATTEMPTED, previousState = 4)),
            appVersion = "0.1.0",
        )
        assertTrue("must record what the state was: $text", text.contains("was enabled-state 4"))
    }

    @Test
    fun `the header tells the user this file never left the phone`() {
        // Bulwark's central claim. The one artifact that leaves the app is the
        // right place to restate it, and to warn that sharing it discloses
        // which apps they changed.
        val text = ActionLogExport.render(
            listOf(record(1, ActionKind.DISABLE, Phase.SUCCEEDED, attemptId = 9)),
            appVersion = "0.1.0",
        )
        assertTrue(text.contains("never been sent anywhere"))
        assertTrue(text.contains("no internet permission"))
        assertTrue("must warn before sharing", text.contains("Check before sharing"))
    }

    @Test
    fun `the export names the build that wrote it`() {
        val text = ActionLogExport.render(emptyList(), appVersion = "0.4.2-debug")
        assertTrue(text.contains("0.4.2-debug"))
    }

    @Test
    fun `a non-primary user is shown, and the primary one is not noise`() {
        val primary = ActionLogExport.render(
            listOf(record(1, ActionKind.DISABLE, Phase.ATTEMPTED, userId = 0)),
            appVersion = "0.1.0",
        )
        val secondary = ActionLogExport.render(
            listOf(record(1, ActionKind.DISABLE, Phase.ATTEMPTED, userId = 10)),
            appVersion = "0.1.0",
        )
        assertFalse("user 0 is the default and adds nothing", primary.contains("(user 0)"))
        assertTrue("a private-space action must say so", secondary.contains("(user 10)"))
    }

    @Test
    fun `timestamps are UTC so two phones can be compared`() {
        val text = ActionLogExport.render(
            listOf(record(1, ActionKind.DISABLE, Phase.ATTEMPTED)),
            appVersion = "0.1.0",
        )
        assertTrue("must be stamped UTC: $text", text.contains("UTC"))
    }
}
