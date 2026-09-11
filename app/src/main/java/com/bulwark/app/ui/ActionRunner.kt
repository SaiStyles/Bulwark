package com.bulwark.app.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bulwark.app.policy.ActionJournal
import com.bulwark.app.policy.PackageActions
import com.bulwark.app.permissions.batchRevokePrompt
import com.bulwark.app.permissions.singleRevokePrompt
import com.bulwark.app.permissions.wordsFor
import com.bulwark.app.policy.PermissionActions
import com.bulwark.app.security.DestructiveActionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place authentication is joined to a destructive action.
 *
 * `PackageActions` deliberately does not authenticate: the guard needs an
 * `Activity` and answers through a callback, and dragging Android's window
 * system into the policy layer would make all of that untestable. So the join
 * happens here instead, at the UI edge, and this file is kept small enough to
 * read in full - because "nothing calls disable without authenticating first"
 * is a claim that has to be checkable by looking.
 *
 * ## Why authentication at all
 *
 * `security.md` FIXED-11: a malicious Accessibility service can read Bulwark's
 * screen and synthesise taps on it. Since Bulwark holds shell, anything that
 * can press its buttons holds shell by proxy, and every guardrail below the UI
 * would be satisfied - the taps look like a user who meant it. An in-app "are
 * you sure?" is one more button to press. A prompt that runs in the system
 * process is not.
 *
 * ## Fail closed, and say so
 *
 * No screen lock means no meaningful confirmation is available, so the action
 * does not run and the user is told why rather than being quietly refused. A
 * device with no lock is one where an Accessibility service can already do
 * anything, and pretending otherwise would be the false sense of protection
 * `safety-rules.md` names as worse than none.
 */
class ActionRunner(
    private val activity: ComponentActivity,
    private val actions: PackageActions,
    private val journal: ActionJournal,
    /**
     * The permission half of the policy layer.
     *
     * Held only so that [restoreEverything] can put permissions back too. A
     * "put everything back" that silently left revoked permissions revoked
     * would be a promise the app does not keep, which `safety-rules.md` treats
     * as the same class of defect as an overclaimed security property.
     */
    private val permissions: PermissionActions,
) {

    /** How an attempt ended, for the UI to report. */
    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Refused(val why: String) : Outcome
        data class Failed(val why: String) : Outcome
        data object Cancelled : Outcome
    }

    private var pending: (() -> Unit)? = null

    /**
     * Launcher for the API 26-28 keyguard fallback. Registered eagerly at
     * construction because `registerForActivityResult` must be called before
     * the Activity is STARTED; registering lazily at first use throws.
     */
    private val credentialLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        val run = pending
        pending = null
        if (approved) run?.invoke()
    }

    /**
     * Authenticates, then switches [packageName] off.
     *
     * @param reason shown in the system prompt. Names the actual consequence,
     *   never "Confirm" - someone who does not know what they are authorising
     *   is not really authorising it.
     */
    fun disable(
        packageName: String,
        label: String,
        onOutcome: (Outcome) -> Unit,
    ) = authenticated(
        title = "Switch off $label",
        reason = "Switch off $packageName. It stays installed and keeps its " +
            "data, and you can switch it back on from Bulwark.",
        onOutcome = onOutcome,
    ) {
        actions.disable(packageName)
        "Switched off $label. You can undo this."
    }

    /**
     * Authenticates, then switches [packageName] back on.
     *
     * Deliberately not "undo". A row offers the one action that matches its
     * current state, so the label always says what the button does - see
     * `PackageActions.switchBackOn` for what hardware testing taught about
     * the difference.
     */
    fun switchBackOn(
        packageName: String,
        label: String,
        onOutcome: (Outcome) -> Unit,
    ) = authenticated(
        title = "Switch $label back on",
        reason = "Switch $packageName back on, to the state it was in before " +
            "Bulwark changed it.",
        onOutcome = onOutcome,
    ) {
        actions.switchBackOn(packageName)
        "Switched $label back on."
    }

    /**
     * Authenticates once, then puts every changed package back.
     *
     * One authentication for the whole operation, because it is one intent -
     * a condition `safety-rules.md` rule 1 names explicitly for the bulk
     * restore it permits.
     *
     * Reports **per item**. A user told everything was put back when two
     * failed is worse off than one told exactly which two, so the message
     * always names the failures and never says a bare "done".
     */
    fun restoreEverything(onOutcome: (Outcome) -> Unit) = authenticated(
        title = "Put everything back",
        reason = "Undo every change Bulwark has made to this phone, returning " +
            "each app and permission to the state it was in before.",
        onOutcome = onOutcome,
    ) {
        // Both halves, one authentication, because putting everything back is
        // one intent to the person who pressed it. Apps first: a permission
        // grant against an app that is still switched off is the less useful
        // order to fail in.
        val steps = actions.restoreEverything() + permissions.restoreEverything()
        val failed = steps.filterNot { it.succeeded }
        when {
            steps.isEmpty() -> "Bulwark has not changed anything on this phone."
            failed.isEmpty() -> "Put back all ${steps.size} change(s)."
            // A partial restore is a FAILURE report, not a success with a
            // caveat. Routed through the failure path so it does not
            // auto-dismiss like good news - the user has to see which packages
            // are still changed in order to do anything about them.
            else -> throw PartialFailure(
                buildString {
                    append("Put back ${steps.size - failed.size} of ${steps.size}. ")
                    append("These are still changed and need a look: ")
                    append(failed.joinToString(", ") { it.describe })
                    append(".")
                }
            )
        }
    }

    /**
     * Authenticates, then takes one permission away from one app.
     *
     * The per-app view's action. Separate from [revokeAcrossApps] so the
     * system prompt can say "Take Camera away from com.example" rather than
     * describing a batch of one - the prompt is the channel an attacker cannot
     * rewrite, so it should read like the thing the user actually did.
     */
    fun revokePermission(
        packageName: String,
        permission: String,
        onOutcome: (Outcome) -> Unit,
    ) = authenticated(
        title = "Take away ${wordsFor(permission).name}",
        reason = singleRevokePrompt(permission, packageName),
        onOutcome = onOutcome,
    ) {
        permissions.revoke(packageName, permission)
        "Took ${wordsFor(permission).name.lowercase()} away from $packageName."
    }

    /**
     * Authenticates once, then takes one permission away from chosen apps.
     *
     * The second exception to `safety-rules.md` rule 1, and every condition it
     * sets is visible here:
     *
     * - **One permission**, because that is all `PermissionActions` can accept.
     * - **The apps are passed in**, never matched. The screen collects ticks
     *   that start empty; nothing here can widen the list.
     * - **The system prompt names the capability and the count**, built by
     *   `batchRevokePrompt` - the one piece of text in this flow that an
     *   accessibility service cannot rewrite, which is why it is a tested pure
     *   function and not a string assembled here.
     * - **Reports per app.** A batch that stopped after three of ten must say
     *   which three and that seven were untouched, never a bare count.
     *
     * One authentication for the whole batch, because it is one intent - the
     * same reasoning the bulk restore already stands on.
     */
    fun revokeAcrossApps(
        permission: String,
        packageNames: List<String>,
        onOutcome: (Outcome) -> Unit,
    ) = authenticated(
        title = "Take away ${wordsFor(permission).name}",
        reason = batchRevokePrompt(permission, packageNames),
        onOutcome = onOutcome,
    ) {
        val steps = permissions.revokeAcrossApps(permission, packageNames)
        val done = steps.count { it.succeeded }
        val stopped = steps.size < packageNames.distinct().size
        val capability = wordsFor(permission).name.lowercase()

        when {
            // Stopped early: rule 6 fail-closed, unlike the restore. The user
            // must be told what was left alone, or they will assume it applied
            // to everything they chose.
            stopped || done < steps.size -> throw PartialFailure(
                buildString {
                    append("Took $capability from $done of ${packageNames.distinct().size}. ")
                    steps.lastOrNull { !it.succeeded }?.let {
                        append("Stopped at ${it.packageName}: ${it.failure} ")
                    }
                    append("The rest were not changed.")
                }
            )
            else -> "Took $capability from $done app(s)."
        }
    }

    /**
     * An operation that partly worked.
     *
     * Carries a message already written for a person, so [authenticated]
     * reports it verbatim instead of prefixing an exception class name at
     * someone who only wants to know which apps are still switched off.
     */
    private class PartialFailure(override val message: String) : Exception(message)

    /**
     * Runs [work] only after the user has authenticated out of process.
     *
     * [work] returns the message to show. It runs off the main thread: every
     * privileged call is a blocking binder transaction.
     */
    private fun authenticated(
        title: String,
        reason: String,
        onOutcome: (Outcome) -> Unit,
        work: () -> String,
    ) {
        val run: () -> Unit = {
            activity.lifecycleScope.launch {
                try {
                    val message = withContext(Dispatchers.IO) { work() }
                    onOutcome(Outcome.Done(message))
                } catch (partial: PartialFailure) {
                    onOutcome(Outcome.Failed(partial.message))
                } catch (refusal: SecurityException) {
                    onOutcome(Outcome.Refused(refusal.message ?: "Bulwark refused this."))
                } catch (bad: IllegalArgumentException) {
                    onOutcome(Outcome.Refused(bad.message ?: "That package name is not valid."))
                } catch (failure: Throwable) {
                    // Rule 6: report, never retry a destructive operation.
                    onOutcome(
                        Outcome.Failed(
                            "${failure::class.java.simpleName}: ${failure.message}"
                        )
                    )
                }
            }
            Unit
        }

        DestructiveActionGuard.confirm(activity, title, reason) { result ->
            when (result) {
                is DestructiveActionGuard.Result.Authenticated -> run()

                is DestructiveActionGuard.Result.UseCredentialIntent -> {
                    val intent = DestructiveActionGuard
                        .deviceCredentialIntent(activity, title, reason)
                    if (intent == null) {
                        onOutcome(Outcome.Refused(NO_LOCK))
                    } else {
                        pending = run
                        credentialLauncher.launch(intent)
                    }
                }

                is DestructiveActionGuard.Result.NoDeviceSecurity ->
                    onOutcome(Outcome.Refused(NO_LOCK))

                is DestructiveActionGuard.Result.Failed -> onOutcome(Outcome.Cancelled)
            }
        }
    }

    /** Attempts with no recorded outcome, for the interrupted-work banner. */
    fun interrupted() = journal.interrupted()

    private companion object {
        const val NO_LOCK =
            "Set a PIN, pattern or password first. Bulwark asks the system to " +
                "confirm before changing anything, and without a screen lock " +
                "there is nothing for it to ask - which also means another app " +
                "could drive Bulwark without you noticing."
    }
}
