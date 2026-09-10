package com.bulwark.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bulwark.app.debloat.PackageVisibilityProbe
import com.bulwark.app.debloat.VisibilityResult
import com.bulwark.app.shizuku.ShizukuGateway
import com.bulwark.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostic screen for the v0.1 spike. Not product UI — it is deleted once
 * `context/layers/01-debloat.md` question C has an answer, which is why its
 * strings are inline rather than in resources.
 *
 * Read-only. It counts packages. It removes nothing.
 */
@Composable
fun SpikeScreen(
    state: ShizukuState,
    gateway: ShizukuGateway,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<VisibilityResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Bulwark — visibility spike", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Counts installed packages twice: once as this app under targetSdk 37 " +
                "filtering, once through Shizuku as uid 2000. The difference decides " +
                "whether Bulwark ever needs QUERY_ALL_PACKAGES.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card { Column(Modifier.padding(14.dp)) { Text(describe(state)) } }

        // Half the spike answer, and it needs no privilege at all. Shown
        // unconditionally so a broken Shizuku path cannot hide it.
        val appCount = remember { PackageVisibilityProbe(context).appVisibleCount() }
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Packages visible to Bulwark itself: $appCount")
                Text(
                    "No Shizuku involved. Compare against `pm list packages` " +
                        "run as shell - the difference is the answer.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        when (state) {
            is ShizukuState.Unavailable ->
                Text(
                    "Start Shizuku, then reopen this screen. Nothing here works without it — " +
                        "and nothing here changes your phone either way.",
                    style = MaterialTheme.typography.bodySmall,
                )

            is ShizukuState.PermissionRequired ->
                Button(onClick = { gateway.requestPermission() }) { Text("Grant Bulwark access") }

            is ShizukuState.Idle, is ShizukuState.Connected ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Shizuku is available. Privileged calls go through its binder " +
                            "directly - no separate service process, which is what the " +
                            "MediaTek user-service failure forced and what turned out to " +
                            "be simpler anyway.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !running,
                        onClick = {
                            running = true
                            error = null
                            scope.launch {
                                try {
                                    result = withContext(Dispatchers.IO) {
                                        PackageVisibilityProbe(context).run()
                                    }
                                } catch (t: Throwable) {
                                    error = "${t::class.java.simpleName}: ${t.message}"
                                } finally {
                                    running = false
                                }
                            }
                        },
                    ) { Text(if (running) "Counting…" else "Run the probe") }
                }

            else -> Unit
        }

        error?.let {
            Card { Column(Modifier.padding(14.dp)) { Text("Probe failed\n\n$it") } }
        }

        result?.let { r ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Visible to Bulwark: ${r.appVisible.size}")
                    Text("Visible to uid 2000: ${r.privilegedVisible.size}")
                    Text("Hidden from the app: ${r.hiddenFromApp.size}")
                    Text("Already removed for this user: ${r.removedForThisUser.size}")
                    Text("")
                    Text(r.verdict, style = MaterialTheme.typography.bodyMedium)
                    if (r.hiddenFromApp.isNotEmpty()) {
                        Text("")
                        Text("Examples hidden from the app:")
                        r.hiddenFromApp.take(15).forEach { Text("  $it") }
                    }
                }
            }
        }
    }
}

private fun describe(state: ShizukuState): String = when (state) {
    is ShizukuState.Unavailable -> "Shizuku: not running"
    is ShizukuState.PermissionRequired -> "Shizuku: running, access not granted"
    is ShizukuState.Idle -> "Shizuku: available, not connected (resting)"
    is ShizukuState.Connecting -> "Shizuku: connecting…"
    is ShizukuState.Connected -> "Shizuku: connected as uid 2000"
    is ShizukuState.Failed -> "Shizuku: failed — ${state.reason}"
}
