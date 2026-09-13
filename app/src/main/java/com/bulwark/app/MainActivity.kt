package com.bulwark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.bulwark.app.ui.AppsScreen
import com.bulwark.app.ui.AuditScreen
import com.bulwark.app.ui.ChangesScreen
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.bulwark.app.policy.ActionJournal
import com.bulwark.app.policy.PackageActions
import com.bulwark.app.shizuku.CriticalRoles
import com.bulwark.app.shizuku.PackageRemoval
import com.bulwark.app.policy.FirewallActions
import com.bulwark.app.policy.PermissionActions
import com.bulwark.app.policy.SqliteActionLog
import com.bulwark.app.security.StrictModePolicy
import com.bulwark.app.security.WindowHardening
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.ShizukuGateway
import com.bulwark.app.ui.ActionRunner
import com.bulwark.app.ui.LogExporter
import com.bulwark.app.ui.theme.BulwarkTheme
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var gateway: ShizukuGateway
    private lateinit var runner: ActionRunner
    private lateinit var exporter: LogExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StrictModePolicy.installIfDebuggable(this)
        // Before anything is drawn, and before any touch can land.
        WindowHardening.apply(this)
        // security.md FIXED-11's second layer. It was written and never
        // called, which left the file describing a live control that was not
        // running - the state that file itself calls the worst possible one
        // for a security control. Authentication is still the real defence.
        WindowHardening.hideFromAutomation(this)
        enableEdgeToEdge()
        gateway = ShizukuGateway(applicationContext)

        // Registered here rather than lazily: ActionRunner registers an
        // activity-result launcher, and that must happen before STARTED.
        val journal = ActionJournal(SqliteActionLog(applicationContext))
        runner = ActionRunner(
            activity = this,
            actions = PackageActions(
                journal, packageName,
                removal = object : PackageActions.Removal {
                    // The escalation's binder work. Bound here rather than
                    // defaulted inside PackageActions because it needs a
                    // Context for the result channel, and the policy layer
                    // stays free of Android for its tests.
                    override fun uninstall(
                        packageName: String,
                        userId: Int,
                        callingPackage: String,
                    ) = PackageRemoval.uninstall(
                        applicationContext, packageName, userId, callingPackage,
                    )

                    override fun installExisting(packageName: String, userId: Int) =
                        PackageRemoval.installExisting(packageName, userId)
                },
            ),
            journal = journal,
            permissions = PermissionActions(journal),
            // System-ness from our own PackageManager, not the privileged one:
            // the firewall is the layer that must work with Shizuku dead.
            firewall = FirewallActions(
                journal,
                { name ->
                    runCatching {
                        val flags = packageManager.getApplicationInfo(name, 0).flags
                        PrivilegedPackages.isSystemFlags(flags)
                    }.getOrDefault(true) // Unknown means protected. Rule 6.
                },
                // Asked of the phone, not guessed from the name. Needs no
                // privilege, which matters here: the firewall is the layer
                // that must still be right with Shizuku dead.
                { name ->
                    runCatching {
                        val jobs = CriticalRoles.read(applicationContext).jobsFor(name)
                        CriticalRoles.Job.IMS in jobs || CriticalRoles.Job.DIALER in jobs
                    }.getOrDefault(true) // Unknown means protected. Rule 6.
                },
            ),
        )
        // From PackageManager rather than BuildConfig: no extra build feature,
        // and it reports the version actually installed, which is what a bug
        // report needs.
        exporter = LogExporter(this, journal, installedVersion())

        setContent {
            BulwarkTheme {
                val state by gateway.state.collectAsState()
                // Two destinations, because they answer different questions -
                // `_shared/design.md` rule 1. What is on this phone, and what
                // have I changed. The second was previously a button at the
                // bottom of the first, which is how "which apps are blocked?"
                // became unanswerable.
                var destination by rememberSaveable { mutableStateOf(Destination.APPS) }
                var changesReload by rememberSaveable { mutableIntStateOf(0) }
                // One Scaffold, one snackbar. Both screens report the same way.
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                // The package list's search and filter live here, not on the
                // screen that draws them. Apps and Audit became separate
                // composables on 2026-09-13, so a screen that leaves the
                // composition takes its `remember` with it - and a typed search
                // silently emptying itself because someone glanced at Audit is
                // a regression the split must not introduce. rememberSaveable,
                // so it also survives the process being killed.
                var query by rememberSaveable { mutableStateOf("") }
                var onlyOffered by rememberSaveable { mutableStateOf(false) }

                // Blocks Bulwark holds are re-applied when the app is opened,
                // once, whichever destination the user lands on.
                //
                // This used to live in the phone screen's firewall effect. That
                // was safe while one composable drew both Apps and Audit; after
                // the split it would have meant the rules were only re-applied
                // if the user happened to open Audit, and a firewall that
                // enforces on one tab is not a firewall. `layers/03-firewall.md`
                // has the reasoning for re-applying at all.
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (runner.blockedNetworkApps().isNotEmpty()) runner.syncFirewall()
                        }
                    }
                }

                // The audit is the one destination whose content endangers the
                // person holding the phone if it is seen over their shoulder -
                // `_shared/threat-model.md` records that discovery can escalate
                // abuse. It is marked while it is on screen and unmarked when
                // it is left, so the package list stays screenshotable for
                // someone asking a forum for help. security.md OPEN-1.
                LaunchedEffect(destination) {
                    WindowHardening.setSensitive(
                        this@MainActivity,
                        destination == Destination.AUDIT,
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = {},
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    when (destination) {
                        Destination.APPS -> AppsScreen(
                            state = state,
                            runner = runner,
                            snackbar = snackbar,
                            query = query,
                            onQueryChange = { query = it },
                            onlyOffered = onlyOffered,
                            onOnlyOfferedChange = { onlyOffered = it },
                            modifier = Modifier.padding(innerPadding),
                        )
                        Destination.AUDIT -> AuditScreen(
                            state = state,
                            runner = runner,
                            snackbar = snackbar,
                            modifier = Modifier.padding(innerPadding),
                        )
                        Destination.CHANGES -> ChangesScreen(
                            runner = runner,
                            exporter = exporter,
                            // Re-read every time the tab is opened: the list is
                            // derived from the log, and an action taken on the
                            // other screen must show here without a restart.
                            reloadKey = changesReload,
                            onOutcome = { outcome ->
                                val text = when (outcome) {
                                    is ActionRunner.Outcome.Done -> outcome.message
                                    is ActionRunner.Outcome.Refused -> outcome.why
                                    is ActionRunner.Outcome.Failed -> outcome.why
                                    ActionRunner.Outcome.Cancelled -> null
                                }
                                if (outcome is ActionRunner.Outcome.Done) changesReload++
                                text?.let { t ->
                                    val bad = outcome is ActionRunner.Outcome.Failed ||
                                        outcome is ActionRunner.Outcome.Refused
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            message = t,
                                            withDismissAction = bad,
                                            duration = if (bad) {
                                                SnackbarDuration.Indefinite
                                            } else {
                                                SnackbarDuration.Long
                                            },
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    /**
     * The screens, and the questions they answer.
     *
     * Three, not one, because a screen that answers two questions becomes a
     * feed and a feed has no hierarchy (`_shared/design.md` rule 1).
     *
     * Three in the code as well as on screen, since 2026-09-13. Apps and Audit
     * were one composable with a `tab` parameter for a day - what a person saw
     * was split, the state behind it was not - and `design.md` records why that
     * retreat was deliberate: the failure mode of threading those values apart
     * is a card that renders **empty** rather than erroring, and there was no
     * detector for it. `AuditContentTest` and `AppsContentTest` are that
     * detector, so the code caught up with the screens.
     */
    private enum class Destination(val label: String) {
        /** Find an app and change it. */
        APPS("Apps"),

        /** What is true about this phone: access, permissions, the firewall. */
        AUDIT("Audit"),

        /** What Bulwark changed, and taking it back. */
        CHANGES("Changes"),
    }

    /** The running build's version name, or a marker if it cannot be read. */
    private fun installedVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    override fun onStart() {
        super.onStart()
        gateway.start()
    }

    /**
     * Shizuku is commonly started *while Bulwark is in the background* — the
     * user leaves, starts it, comes back. Re-reading here rather than trusting
     * the state from launch is what makes that flow work.
     */
    override fun onResume() {
        super.onResume()
        gateway.refresh()
    }

    override fun onStop() {
        gateway.stop()
        super.onStop()
    }
}
