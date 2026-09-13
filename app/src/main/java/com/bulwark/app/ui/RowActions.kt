package com.bulwark.app.ui

/**
 * What a package row can ask for, named as an interface so a row can be
 * rendered without an Activity.
 *
 * `ActionRunner` is the only production implementation and it is the one that
 * joins authentication to a destructive action. Nothing here weakens that: the
 * interface carries the *request*, and every implementation of it is still
 * responsible for the guard. This exists for one reason - a Compose test cannot
 * build an `ActionRunner`, because `registerForActivityResult` has to happen
 * before the Activity is STARTED and a test's Activity is already resumed by
 * the time the test body runs.
 *
 * Without this seam the package list cannot be rendered in a test at all, and
 * `_shared/design.md` records what that cost: the screen split was held back
 * because a card that renders **empty** rather than erroring had no detector
 * but a screenshot.
 *
 * Six methods, not the whole of `ActionRunner`. A row asks for exactly these.
 */
interface RowActions {

    fun disable(packageName: String, label: String, onOutcome: (ActionRunner.Outcome) -> Unit)

    fun switchBackOn(packageName: String, label: String, onOutcome: (ActionRunner.Outcome) -> Unit)

    fun uninstall(
        packageName: String,
        label: String,
        canRestore: Boolean,
        onOutcome: (ActionRunner.Outcome) -> Unit,
    )

    fun revokePermission(
        packageName: String,
        permission: String,
        onOutcome: (ActionRunner.Outcome) -> Unit,
    )

    fun blockNetwork(packageName: String, onOutcome: (ActionRunner.Outcome) -> Unit)

    fun allowNetwork(packageName: String, onOutcome: (ActionRunner.Outcome) -> Unit)
}
