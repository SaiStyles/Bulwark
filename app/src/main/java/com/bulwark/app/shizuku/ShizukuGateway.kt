package com.bulwark.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** What Bulwark currently is and is not allowed to do. */
sealed interface ShizukuState {
    /** Shizuku is not installed, or is installed but not started. */
    data object Unavailable : ShizukuState

    /** Alive, but the user has not granted us access yet. */
    data object PermissionRequired : ShizukuState

    data object Connecting : ShizukuState

    /** Privileged calls are available. */
    data class Connected(val service: IPrivilegedService) : ShizukuState

    data class Failed(val reason: String) : ShizukuState
}

/**
 * The single choke point for privileged access.
 *
 * `context/_shared/conventions.md` requires every privileged call to pass
 * through here, so the safety rules are enforceable in one place rather than
 * at every call site.
 *
 * Two rules from `safety-rules.md` shape this class:
 *
 * - **Fail closed (rule 6).** Liveness and permission are re-checked
 *   immediately before use, never cached from screen load. Shizuku can die at
 *   any moment — a reboot, or the user stopping it.
 * - **Shizuku is optional at rest.** Bulwark must stay usable with Shizuku
 *   dead: already-applied changes persist on their own, so the app shows
 *   state and disables *changes*, rather than refusing to open.
 */
class ShizukuGateway(private val appContext: Context) {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.Unavailable)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        // Never queue privileged work to run "when it comes back" — rule 6.
        _state.value = ShizukuState.Unavailable
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
            else _state.value = ShizukuState.PermissionRequired
        }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    /** Re-reads live state. Cheap, and safe to call as often as you like. */
    fun refresh() {
        if (!isAlive()) {
            _state.value = ShizukuState.Unavailable
            return
        }
        if (!hasPermission()) {
            _state.value = ShizukuState.PermissionRequired
            return
        }
        if (_state.value !is ShizukuState.Connected) bind()
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!isAlive()) {
            _state.value = ShizukuState.Unavailable
            return
        }
        if (hasPermission()) bind() else Shizuku.requestPermission(requestCode)
    }

    private fun isAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        // Shizuku not installed at all throws rather than returning false.
        false
    }

    private fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IPrivilegedService.Stub.asInterface(it) }
            _state.value = if (service != null && binder.pingBinder()) {
                ShizukuState.Connected(service)
            } else {
                ShizukuState.Failed("Privileged service bound but is not responding.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _state.value = ShizukuState.Unavailable
        }
    }

    private fun bind() {
        _state.value = ShizukuState.Connecting
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            _state.value = ShizukuState.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, PrivilegedService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .version(SERVICE_VERSION)
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 4001

        /** Bump when the AIDL changes, so Shizuku restarts a stale process. */
        const val SERVICE_VERSION = 1
    }
}
