package com.bulwark.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** What Bulwark is currently allowed to do. */
sealed interface ShizukuState {
    /** Shizuku is not installed, or installed but not started. */
    data object Unavailable : ShizukuState

    /** Alive, but the user has not granted Bulwark access. */
    data object PermissionRequired : ShizukuState

    /** Privileged calls will work. */
    data object Ready : ShizukuState
}

/**
 * The single choke point for privileged access.
 *
 * `context/_shared/conventions.md` requires every privileged call to pass
 * through here so the safety rules are enforceable in one place rather than at
 * every call site.
 *
 * ## Why there is no "connect" step any more
 *
 * There used to be one, because privileged work ran in a Shizuku *user
 * service* - a separate uid-2000 process that had to be bound and released.
 * That path is dead on MediaTek hardware, which is this project's target
 * (`context/_shared/app-architecture.md`).
 *
 * `ShizukuBinderWrapper` needs no process of its own. Each call wraps a system
 * binder and Shizuku forwards that single transaction with its uid. So there is
 * nothing to hold and nothing to leak: privilege is taken per-call and given
 * back when the call returns.
 *
 * That is a better answer to "least privilege, shortest duration"
 * (`context/_shared/security.md` FIXED-9) than the release-after-use lifecycle
 * it replaced, and it arrived by being forced rather than by being designed.
 *
 * ## Fail closed
 *
 * `safety-rules.md` rule 6: liveness and permission are re-read immediately
 * before use, never cached from screen load. Shizuku can die at any moment.
 */
class ShizukuGateway(@Suppress("unused") private val appContext: Context) {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.Unavailable)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        // Never queue privileged work to run "when it comes back".
        _state.value = ShizukuState.Unavailable
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

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

    /** Re-reads live state. Cheap; call it as often as you like. */
    fun refresh() {
        _state.value = when {
            !isAlive() -> ShizukuState.Unavailable
            !hasPermission() -> ShizukuState.PermissionRequired
            else -> ShizukuState.Ready
        }
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!isAlive()) {
            _state.value = ShizukuState.Unavailable
            return
        }
        if (hasPermission()) refresh() else Shizuku.requestPermission(requestCode)
    }

    /** True only if a privileged call would work *right now*. */
    fun canActNow(): Boolean = isAlive() && hasPermission()

    /**
     * Stops the Shizuku **server**, at the user's request.
     *
     * Not to be confused with [stop], which only detaches this object's
     * listeners and leaves the server running. This one ends the privileged
     * session for every app on the phone, which is why nothing calls it except
     * a person pressing a button that says so.
     *
     * **Reversible, and cheaply**: the pairing survives, so starting Shizuku
     * again is one tap - provided wireless debugging is still on. That proviso
     * is the whole reason `DoneForNow` states the cost of switching debugging
     * off separately.
     *
     * ## Two things lint is right about, and one it cannot see
     *
     * `Shizuku.exit()` is `@RestrictTo(LIBRARY_GROUP_PREFIX)` - public, but the
     * library says it is for Shizuku's own manager, not for clients. The
     * suppression is deliberate and the risk is accepted narrowly: a future
     * Shizuku may remove it, `runCatching` returns false, and the button
     * reports that nothing changed. Graceful, and honest about it.
     *
     * The thing lint cannot see is bigger: **this stops the server for every
     * app on the phone**, not just Bulwark. Anything else the person uses
     * Shizuku for loses access too. That is what they asked for when they
     * pressed a button that says "Stop Shizuku", but it has to be *said* -
     * `DoneForNow` says it.
     *
     * Returns false if there was nothing to stop or the call was refused, and
     * the caller must re-read state rather than assume either way. `exit()`
     * returns void, so success here means "the request went out without
     * throwing", not "the server is gone" - the screen confirms by re-reading,
     * the same way every other action in this app does.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    fun shutDownServer(): Boolean {
        if (!canActNow()) return false
        return runCatching { Shizuku.exit() }.isSuccess
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

    private companion object {
        const val PERMISSION_REQUEST_CODE = 4001
    }
}
