package com.bulwark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PackageListScreen(
                        state = state,
                        runner = runner,
                        exporter = exporter,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
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
