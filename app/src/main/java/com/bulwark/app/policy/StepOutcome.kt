package com.bulwark.app.policy

import com.bulwark.app.permissions.wordsFor

/**
 * What happened to one thing inside an operation that touched several.
 *
 * Shared by the two restores and by [PermissionActions.revokeAcrossApps],
 * because all three owe the user the same thing: not a verdict on the
 * operation, but a line per item.
 *
 * `safety-rules.md` rule 1 makes the per-item report a **condition** of the
 * bulk restore being allowed at all. A user told everything was put back when
 * two failed is worse off than one told exactly which two, so this type exists
 * to make the bare boolean impossible to return.
 *
 * Moved out of `PackageActions` on 2026-09-11 when permissions gained the same
 * operation, and renamed when bulk revoke joined them. Two near-identical
 * shapes for one idea is how a UI ends up handling one of them and quietly
 * dropping the other.
 */
data class StepOutcome(
    val packageName: String,
    /** The permission, when this step was about one. Null for whole-app steps. */
    val permission: String?,
    val succeeded: Boolean,
    /** Why it failed, in the platform's words. Null on success. */
    val failure: String? = null,
) {
    /**
     * What to call this step on screen.
     *
     * A failed restore is reported by name, and "com.example.app" alone would
     * be wrong for a permission step - the app is not still changed, one of its
     * permissions is. Plain words for the permission where Bulwark has them, so
     * the failure list reads like the screen the user acted on.
     */
    val describe: String
        get() = when (permission) {
            null -> packageName
            else -> "$packageName (${wordsFor(permission).name})"
        }
}
