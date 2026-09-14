package com.bulwark.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [rememberResumeTicker], and the off-by-one that would double every read.
 *
 * The ticker exists so a screen re-reads the phone when somebody comes back
 * from Android's own Settings, instead of continuing to state what was true
 * before they left. The whole of it is one integer, and the only thing that can
 * go wrong is *when* it changes:
 *
 * - counting the first `ON_RESUME` re-runs every effect at launch, which on
 *   Audit means a second privileged enumeration, a second `dumpsys` parse and a
 *   second pass of certificate hashing, to display what is already displayed
 * - not counting later ones leaves the bug it was written to fix
 *
 * Both failures are silent, so both are pinned here.
 */
@RunWith(AndroidJUnit4::class)
class ResumeTickerTest {

    @get:Rule
    val compose = createComposeRule()

    /** A lifecycle this test drives, rather than the host's. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun ticker(owner: Owner, onValue: (Int) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                onValue(rememberResumeTicker())
            }
        }
    }

    @Test
    fun theFirstResumeDoesNotCount() {
        val owner = Owner()
        var latest = -1
        ticker(owner) { latest = it }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals(
            "the first resume is the screen appearing, and its effects have " +
                "already run - counting it re-reads everything at launch",
            0,
            latest,
        )
    }

    @Test
    fun comingBackCounts() {
        val owner = Owner()
        var latest = -1
        ticker(owner) { latest = it }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        // Away, and back: exactly what happens when Android's Settings is
        // opened from a card and then dismissed.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals("returning must re-read, or the screen states stale truth", 1, latest)
    }

    @Test
    fun everyReturnCountsSeparately() {
        val owner = Owner()
        var latest = -1
        ticker(owner) { latest = it }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        repeat(3) {
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        }
        compose.waitForIdle()

        assertEquals(3, latest)
    }
}
