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
import com.bulwark.app.security.StrictModePolicy
import com.bulwark.app.security.WindowHardening
import com.bulwark.app.shizuku.ShizukuGateway
import com.bulwark.app.ui.SpikeScreen
import com.bulwark.app.ui.theme.BulwarkTheme

class MainActivity : ComponentActivity() {

    private lateinit var gateway: ShizukuGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StrictModePolicy.installIfDebuggable(this)
        // Before anything is drawn, and before any touch can land.
        WindowHardening.apply(this)
        enableEdgeToEdge()
        gateway = ShizukuGateway(applicationContext)

        setContent {
            BulwarkTheme {
                val state by gateway.state.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SpikeScreen(
                        state = state,
                        gateway = gateway,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
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
