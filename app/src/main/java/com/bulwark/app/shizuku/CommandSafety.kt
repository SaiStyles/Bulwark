package com.bulwark.app.shizuku

/**
 * Keeps untrusted text out of privileged commands.
 *
 * Everything [PrivilegedService] runs executes as uid 2000. A package name is
 * attacker-influenced input — an app chooses its own — so it must be treated
 * as hostile before it is ever placed near a command line.
 *
 * ## Two defences, because one is not enough
 *
 * 1. **No shell, ever.** `ProcessBuilder(listOf(...))` execs the binary
 *    directly with an argument vector. There is no `sh -c`, so there is no
 *    shell to interpret `;`, `&&`, backticks or `$(...)`. This alone defeats
 *    classic injection.
 * 2. **Validate anyway.** Defence one is a property of how the call is
 *    written, and code gets rewritten. A future contributor reaching for
 *    string interpolation because it was quicker would silently remove it.
 *    Validation fails loudly in that world.
 *
 * The second layer exists precisely because the first is invisible. Nobody
 * reviewing a diff notices that a list became a string.
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
     * check and forget the other. Both live on the uid-2000 side of the
     * binder, below anything that could be compromised above it.
     *
     * @throws IllegalArgumentException if the name is malformed.
     * @throws SecurityException if the package is on the never-touch list.
     */
    fun requireMutable(packageName: String): String {
        requireValidPackageName(packageName)
        ProtectedPackages.reasonFor(packageName)?.let { reason ->
            throw SecurityException("Refusing to modify $packageName. $reason")
        }
        return packageName
    }
}
