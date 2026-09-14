package com.bulwark.app.policy

import com.bulwark.app.permissions.wordsFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The action log as text a person can read.
 *
 * `safety-rules.md` rule 5: exportable so a user can undo and so a bug report
 * is possible. Both readers matter and they want different things - the user
 * wants "what did this app do to my phone", the maintainer wants enough to
 * diagnose - so this is one plain-text document that serves both rather than a
 * machine format with a viewer nobody wrote.
 *
 * ## Plain text, not JSON
 *
 * The user of a debloat tool that broke something needs to read this on the
 * phone that broke, possibly in a text field in a forum post. Plain text
 * survives that. JSON would be tidier for a parser we do not have.
 *
 * ## What is deliberately not in here
 *
 * No device identifiers, no serial, no IMEI, no account, no installed-app
 * inventory beyond the packages Bulwark actually acted on. `threat-model.md`
 * treats an exported file as something that may end up in a public forum, and
 * a log that quietly carries a device fingerprint into a bug report is a
 * privacy tool leaking on its user's behalf.
 *
 * Failure detail is bounded at the point it is recorded (`ActionJournal`), not
 * here, so nothing unbounded ever reaches storage in the first place.
 *
 * Pure function of its input: no `Context`, no file system, no clock beyond
 * what it is handed. That is what makes the format assertable in a unit test.
 */
object ActionLogExport {

    /** UTC so two exports from different phones can be compared directly. */
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss 'UTC'"

    /**
     * Renders [records] as the exported document.
     *
     * @param appVersion shown in the header so a bug report says which build
     *   wrote the log without the reader having to ask.
     */
    fun render(records: List<ActionRecord>, appVersion: String): String = buildString {
        appendLine("Bulwark action log")
        appendLine("==================")
        appendLine()
        appendLine("Version:   $appVersion")
        appendLine("Entries:   ${records.size}")
        appendLine("Timestamps are UTC.")
        appendLine()
        appendLine(
            "This file was written on your phone and has never been sent anywhere. " +
                "Bulwark holds no internet permission. Check before sharing it: it " +
                "lists the apps you changed."
        )
        appendLine()

        val interrupted = records.unfinished()
        if (interrupted.isNotEmpty()) {
            appendLine("INTERRUPTED - ${interrupted.size} action(s) with no recorded outcome.")
            appendLine("Bulwark was stopped mid-change. These may or may not have applied;")
            appendLine("it does not know, and it will not guess. Check each one by hand:")
            interrupted.forEach { appendLine("  - ${it.kind.humanVerb} ${it.subject}") }
            appendLine()
        }

        if (records.isEmpty()) {
            appendLine("Nothing has been changed on this device.")
            return@buildString
        }

        appendLine("---")
        records.forEach { appendLine(it.line()) }
    }

    private fun ActionRecord.line(): String = buildString {
        append(format(atEpochMillis))
        append("  ")
        append(phase.label.padEnd(PHASE_WIDTH))
        append("  ")
        append(kind.humanVerb.padEnd(VERB_WIDTH))
        append("  ")
        append(subject)
        if (userId != 0) append("  (user $userId)")
        previousState?.let { append("  [was enabled-state $it]") }
        previousUidState?.let { append("  [uid op mode was $it]") }
        detail?.let { append("  - $it") }
    }

    /**
     * What the action was done to: an app, or one permission of one app.
     *
     * Plain words plus the raw permission name, because this file has two
     * readers with different needs - the user wants to know their microphone
     * came back, the person reading the bug report wants the constant.
     */
    private val ActionRecord.subject: String
        get() = when {
            permission != null -> "$packageName  ${wordsFor(permission).name} [$permission]"
            // The raw op string and nothing else. `wordsFor` knows runtime
            // permissions and would print nonsense for `android:…`, which is
            // why an op has its own field rather than borrowing that one.
            appOp != null -> "$packageName  [$appOp]"
            else -> packageName
        }

    private fun format(epochMillis: Long): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    private const val PHASE_WIDTH = 9
    private const val VERB_WIDTH = 16
}

/** Plain-language verb. The log is read by people, not only by us. */
internal val ActionKind.humanVerb: String
    get() = when (this) {
        ActionKind.DISABLE -> "switched off"
        ActionKind.ENABLE -> "switched back on"
        ActionKind.UNINSTALL -> "removed"
        ActionKind.INSTALL_EXISTING -> "restored"
        // Said about a permission, so the sentence reads "took away
        // com.example  Microphone" rather than naming a mechanism.
        ActionKind.REVOKE_PERMISSION -> "took away"
        ActionKind.GRANT_PERMISSION -> "gave back"
        // Said about a capability Android never prompted for, so the verb
        // names the act rather than the mechanism.
        ActionKind.REVOKE_SPECIAL_ACCESS -> "took access from"
        ActionKind.GRANT_SPECIAL_ACCESS -> "gave access back"
        // Said as what the user asked for, not as what the tunnel did. These
        // record an intent; whether it was in force at any given moment is a
        // different question and the log must not imply it answers that one.
        ActionKind.BLOCK_NETWORK -> "blocked online"
        ActionKind.ALLOW_NETWORK -> "allowed online"
    }

/** Reads as a status, not as an enum constant. */
internal val Phase.label: String
    get() = when (this) {
        Phase.ATTEMPTED -> "started"
        Phase.SUCCEEDED -> "done"
        Phase.FAILED -> "FAILED"
    }
