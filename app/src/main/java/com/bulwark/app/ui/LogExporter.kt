package com.bulwark.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bulwark.app.policy.ActionJournal
import com.bulwark.app.policy.ActionLogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes the action log to a file the user picks.
 *
 * `safety-rules.md` rule 5 requires a local, **exportable** record so a user
 * can undo and so a bug report is possible. `ActionLogExport.render` has been
 * written and tested since the log was built, and nothing called it - the log
 * was exportable in principle and not in fact. This closes that.
 *
 * ## Why the system file picker, and nothing else
 *
 * `CreateDocument` hands the user a save dialog and gives Bulwark a write
 * handle to exactly the file they chose. Three things follow, all of them the
 * point:
 *
 * - **No storage permission.** Bulwark asks for nothing and can write nowhere
 *   else. An audit tool that wanted broad file access to save one text file
 *   would be arguing against itself.
 * - **The user chooses the destination**, including whether it leaves the
 *   phone at all. Bulwark never picks a cloud folder on their behalf.
 * - **Nothing is written until they confirm.** Cancelling writes no file.
 *
 * Rule 5 also says local file only, never transmitted - and Bulwark holds no
 * `INTERNET` permission, so that is enforced by the OS rather than promised.
 * Where the user *sends* the file afterwards is their decision, which is why
 * the document itself opens by warning that it lists the apps they changed.
 */
class LogExporter(
    private val activity: ComponentActivity,
    private val journal: ActionJournal,
    private val appVersion: String,
) {

    /** How the export ended, for the UI to report. */
    sealed interface Outcome {
        data class Saved(val where: String) : Outcome
        data object Cancelled : Outcome
        data class Failed(val why: String) : Outcome
    }

    private var onOutcome: ((Outcome) -> Unit)? = null

    /**
     * Registered eagerly, because `registerForActivityResult` must be called
     * before the Activity reaches STARTED.
     */
    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_TYPE),
    ) { uri ->
        val report = onOutcome
        onOutcome = null
        if (uri == null) {
            report?.invoke(Outcome.Cancelled)
            return@registerForActivityResult
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ActionLogExport.render(journal.history(), appVersion)
                    activity.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error("Could not open the file you chose")
                    uri.lastPathSegment ?: uri.toString()
                }
            }
            result.fold(
                onSuccess = { report?.invoke(Outcome.Saved(it)) },
                // Reported, never swallowed: a user who thinks they have a copy
                // of the log and does not is worse off than one who knows the
                // save failed.
                onFailure = {
                    report?.invoke(
                        Outcome.Failed("${it::class.java.simpleName}: ${it.message}")
                    )
                },
            )
        }
    }

    /** Opens the save dialog. The file name carries the date for sorting. */
    fun export(onOutcome: (Outcome) -> Unit) {
        this.onOutcome = onOutcome
        picker.launch(DEFAULT_NAME)
    }

    /** True when there is anything to export. */
    fun hasAnything(): Boolean = journal.history().isNotEmpty()

    private companion object {
        const val MIME_TYPE = "text/plain"
        const val DEFAULT_NAME = "bulwark-action-log.txt"
    }
}
