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
