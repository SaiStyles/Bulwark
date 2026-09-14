package com.bulwark.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A counter that increments every time the app is returned to.
 *
 * ## The bug this exists for
 *
 * Bulwark reads the phone once when a screen appears and then believes itself.
 * Every control that hands off to Android's own Settings therefore creates a
 * lie: open certificate settings, remove the certificate, come back, and the
 * card still says one is there. Same for wireless debugging in Developer
 * options, and for always-on VPN.
 *
 * That is not a refresh nicety. This app's whole claim is that it says what the
 * phone says rather than what it did - `safety-rules.md` rule 6 exists because
 * a revoke that silently failed once got reported as success. A screen that
 * confidently states last minute's truth is the same defect arriving by a
 * slower route, and it is *most* likely exactly where Bulwark has just sent
 * someone off to change the thing being displayed.
 *
 * ## Why a counter and not a callback
 *
 * Every screen here already re-reads on a `reload` integer it owns. Folding
 * this in means adding one key to effects that already exist, rather than a
 * second refresh mechanism living beside the first - which is how two ways of
 * doing one thing start disagreeing.
 *
 * ## The first resume is skipped, deliberately
 *
 * `ON_RESUME` fires when the screen first appears, and the effects have already
 * run for that composition. Counting it would move the key from 0 to 1 and
 * re-run every read a second time at launch - two privileged enumerations, two
 * `dumpsys` parses and two certificate hashes to show what was already on
 * screen. So the first one is swallowed and only genuine returns count.
 */
@Composable
fun rememberResumeTicker(): Int {
    val owner = LocalLifecycleOwner.current
    var returns by remember { mutableIntStateOf(0) }
    var seenFirstResume by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (seenFirstResume) returns++ else seenFirstResume = true
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    return returns
}
