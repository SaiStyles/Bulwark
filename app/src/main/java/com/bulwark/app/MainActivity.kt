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
import com.bulwark.app.ui.ChangesScreen
import com.bulwark.app.ui.PhoneTab
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.bulwark.app.policy.ActionJournal
import com.bulwark.app.policy.PackageActions
import com.bulwark.app.policy.FirewallActions
import com.bulwark.app.policy.PermissionActions
import com.bulwark.app.policy.SqliteActionLog
import com.bulwark.app.security.StrictModePolicy
import com.bulwark.app.security.WindowHardening
import com.bulwark.app.shizuku.PrivilegedPackages
import com.bulwark.app.shizuku.ShizukuGateway
import com.bulwark.app.ui.ActionRunner
import com.bulwark.app.ui.LogExporter
import com.bulwark.app.ui.PackageListScreen
import com.bulwark.app.ui.theme.BulwarkTheme

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
            actions = PackageActions(journal, packageName),
            journal = journal,
            permissions = PermissionActions(journal),
            // System-ness from our own PackageManager, not the privileged one:
            // the firewall is the layer that must work with Shizuku dead.
            firewall = FirewallActions(journal) { name ->
                runCatching {
                    val flags = packageManager.getApplicationInfo(name, 0).flags
                    PrivilegedPackages.isSystemFlags(flags)
                }.getOrDefault(true) // Unknown means protected. Rule 6.
            },
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
                        Destination.APPS, Destination.AUDIT -> PackageListScreen(
                            state = state,
                            runner = runner,
                            snackbar = snackbar,
                            tab = if (destination == Destination.AUDIT) {
                                PhoneTab.AUDIT
                            } else {
                                PhoneTab.APPS
                            },
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
     * Two, not one, because a screen that answers two questions becomes a feed
     * and a feed has no hierarchy (`_shared/design.md` rule 1). Kept to two
     * rather than the three that file describes: splitting the phone screen
     * into Apps and Audit is the next step, and doing it in the same change as
     * introducing navigation would have made a failure impossible to localise.
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
