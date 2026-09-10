package com.bulwark.app.shizuku

import java.io.BufferedReader
import kotlin.system.exitProcess

/**
 * The privileged half of Bulwark. Shizuku launches this class in its own
 * process running as uid 2000 (shell), so everything here executes with
 * ADB-level authority rather than the app's own.
 *
 * Nothing in this class may be destructive without a tested undo
 * (`context/_shared/safety-rules.md` rule 3). Today it only reads.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    /** Called by Shizuku when the connection is torn down. */
    override fun destroy() {
        exitProcess(0)
    }

    /**
     * Enumerates installed packages by shelling out to `pm`.
     *
     * `pm` is used rather than a direct `IPackageManager` binder call because
     * it needs no hidden-API access, which keeps the spike honest: if this
     * sees more packages than the app's own PackageManager, the difference is
     * caused by the calling uid and nothing else.
     *
     * @param includeUninstalled pass `-u` to include packages uninstalled for
     *   the current user but still present on the system partition. Those are
     *   exactly the packages a previous debloat could restore.
     */
    override fun listPackages(includeUninstalled: Boolean): List<String> {
        val command = buildList {
            add("pm"); add("list"); add("packages")
            if (includeUninstalled) add("-u")
        }
        return runCommand(command).mapNotNull { line ->
            // `pm list packages` emits "package:com.example.thing"
            line.removePrefix("package:").trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The mandatory path for any command that CHANGES device state.
     *
     * No such command exists yet, and none should until spike question C in
     * `context/layers/01-debloat.md` returns a number. This exists first, on
     * purpose: `context/CONTEXT.md` puts "Guardrail" at step 2 of the layer
     * workflow and "Implement" at step 3, because a guardrail written after
     * the feature is written to accommodate it.
     *
     * Every future mutating method routes through here, so the never-remove
     * list and input validation cannot be forgotten at a call site. Both
     * checks run on the uid-2000 side of the binder, below anything that
     * could be compromised above it.
     *
     * @throws IllegalArgumentException on a malformed package name.
     * @throws SecurityException if the package is permanently protected.
     */
    @Suppress("unused") // Used once a mutating operation is added. Do not delete.
    private fun runMutatingCommand(
        packageName: String,
        buildCommand: (String) -> List<String>,
    ): List<String> {
        val safe = CommandSafety.requireMutable(packageName)
        return runCommand(buildCommand(safe))
    }

    /**
     * Executes directly with an argument vector. **No shell.**
     *
     * There is deliberately no `sh -c` here: without a shell there is no
     * metacharacter interpretation, so `;`, `&&`, backticks and `$(...)` in
     * an argument are inert text. See `CommandSafety` for why validation
     * still happens on top of that.
     */
    private fun runCommand(command: List<String>): List<String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return try {
            val output = process.inputStream.bufferedReader().use(BufferedReader::readLines)
            process.waitFor()
            output
        } finally {
            process.destroy()
        }
    }
}
