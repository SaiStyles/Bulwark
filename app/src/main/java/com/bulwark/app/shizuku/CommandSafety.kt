package com.bulwark.app.shizuku

import androidx.annotation.VisibleForTesting

/**
 * Keeps untrusted text out of privileged operations.
 *
 * Everything reached through [PrivilegedPackages] executes as uid 2000. A
 * package name is attacker-influenced input — an app chooses its own — so it
 * is treated as hostile before it goes anywhere near a privileged call.
 *
 * ## Two defences, because one is not enough
 *
 * 1. **No shell, ever.** Bulwark does not exec `pm` or `am`. Privileged work
 *    goes through `IPackageManager` over Shizuku's binder, where a package
 *    name is one typed argument in a parcel. There is no command line, so
 *    there is nothing for `;`, `&&`, backticks or `$(...)` to be interpreted
 *    by. This alone defeats classic injection.
 *
 *    (This class was written when the plan was `ProcessBuilder`. The binder
 *    path is stronger and the validation below stayed, which is the right way
 *    round.)
 * 2. **Validate anyway.** Defence one is a property of how the call is
 *    written, and code gets rewritten. A future contributor reaching for a
 *    shell because it was quicker to prototype would silently remove it.
 *    Validation fails loudly in that world.
 *
 * The second layer exists precisely because the first is invisible. Nobody
 * reviewing a diff notices that a parcelled argument became a string.
 */
object CommandSafety {

    /**
     * Android package names, conservatively.
     *
     * Deliberately stricter than the platform: dot-separated segments of
     * letters, digits and underscores. Every character a shell, a path, or an
     * argument parser could treat as special is excluded — including `/`,
     * `..`, spaces, quotes, dashes and NUL.
     *
     * A legitimate package rejected here is a bug report. A malicious one
     * accepted here is a compromised phone.
     */
    private val VALID_PACKAGE = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*$")

    /** Upper bound from the platform; also stops absurd inputs reaching exec. */
    private const val MAX_PACKAGE_LENGTH = 255

    fun isValidPackageName(candidate: String): Boolean =
        candidate.isNotEmpty() &&
            candidate.length <= MAX_PACKAGE_LENGTH &&
            VALID_PACKAGE.matches(candidate)

    /**
     * @throws IllegalArgumentException if [candidate] is not a package name.
     *
     * Throwing rather than returning null is intentional: a caller cannot
     * accidentally ignore it, and `safety-rules.md` rule 6 requires failing
     * closed on ambiguity rather than proceeding with a best guess.
     */
    fun requireValidPackageName(candidate: String): String {
        require(isValidPackageName(candidate)) {
            // Do not echo the input back into logs unbounded.
            "Rejected package name (length ${candidate.length}). " +
                "Expected dot-separated alphanumeric segments."
        }
        return candidate
    }

    /**
     * The single gate every destructive operation passes through.
     *
     * Combines validation with [ProtectedPackages], so a caller cannot get one
     * check and forget the other.
     *
     * **It resolves system-ness itself.** [ProtectedPackages] applies its
     * structural fragment list only to system packages, so that flag decides
     * whether `com.mediatek.ims` is refused or removable. A caller allowed to
     * supply it is a caller allowed to unlock every OEM-renamed telephony
     * package by passing `false`, so this asks the platform instead — one
     * extra binder call per destructive action, which is nothing next to what
     * the action itself costs.
     *
     * @throws IllegalArgumentException if the name is malformed.
     * @throws SecurityException if the package is on the never-touch list.
     */
    fun requireMutable(packageName: String): String {
        requireValidPackageName(packageName)
        return requireMutable(packageName, PrivilegedPackages.isSystemPackage(packageName))
    }

    /**
     * [requireMutable] with system-ness supplied rather than looked up.
     *
     * `internal` on purpose: unit tests need to drive both sides of the flag
     * without a device, and no production caller should be choosing it. The
     * public overload above is the one that ships.
     */
    @VisibleForTesting
    internal fun requireMutable(packageName: String, isSystem: Boolean): String {
        requireValidPackageName(packageName)
        ProtectedPackages.reasonFor(packageName, isSystem)?.let { reason ->
            throw SecurityException("Refusing to modify $packageName. $reason")
        }
        return packageName
    }
}
