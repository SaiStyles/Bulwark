package com.bulwark.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the Compose test harness run at all on this device?
 *
 * `conventions.md` and `NOW.md` both record that it does not -
 * `createComposeRule()` reporting "No compose hierarchies found" - and the
 * screen split is blocked behind that claim. But the suite that produced the
 * reading was never committed, so nothing in the tree could reproduce it and
 * no later session could watch it fail. A check nobody has watched fail is not
 * a check, and this project has twice fixed an instrument that was reading
 * correctly.
 *
 * So this asserts nothing about Bulwark. It renders one `Text` and looks for
 * it - the smallest hierarchy that can exist. If this passes, the blocker is
 * stale and the split is unblocked. If it fails, the failure is finally
 * committed, reproducible, and someone's to fix.
 */
@RunWith(AndroidJUnit4::class)
class ComposeHarnessTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHarnessCanRenderAndFindOneComposable() {
        compose.setContent { Text("bulwark harness probe") }
        compose.onNodeWithText("bulwark harness probe").assertIsDisplayed()
    }
}
