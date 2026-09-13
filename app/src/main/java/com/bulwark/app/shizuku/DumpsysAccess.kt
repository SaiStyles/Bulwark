package com.bulwark.app.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.FileInputStream

/**
 * Reads system service dumps at uid 2000. **Read-only by construction.**
 *
 * `android.permission.DUMP` is `signatureOrSystem`, so no third-party app can
 * read `dumpsys` with any permission a user is able to grant. Shell holds it,
 * so through Shizuku Bulwark can read a class of system state nothing on the
 * Play Store can show - what apps did when nobody was watching. The capability
 * and its ranking are in `context/_shared/capability-research/observability.md`.
 *
 * ## Why there is no shell here
 *
 * Bulwark does not exec `dumpsys`. `Shizuku.newProcess` is `private` in the
 * API and reflecting into a *library's* private method has no compatibility
 * contract at all (`DoneForNow.kt` records that decision for the settings
 * write). So this goes the same way everything else does: a binder at uid
 * 2000, and in this case the `DUMP` transaction every Binder already answers.
 *
 * That also means `CommandSafety`'s first defence holds unchanged - there is
 * no command line for anything to be interpreted by.
 *
 * ## The guardrails, decided before this was written
 *
 * 1. **The arguments are a closed set, never caller-supplied.** This is the
 *    important one. `dumpsys deviceidle` is not a read - it is a *command
 *    surface*, and `whitelist +<pkg>`, `force-idle` and `step` all change
 *    device state. An API taking free-form args would be a state-change path
 *    wearing a reader's name. [Dump] is that closed set, and every entry in it
 *    was checked to be a pure read.
 * 2. **The reader runs while the dump is being written.** A dump goes into a
 *    pipe, and a pipe's buffer is 64 KiB. `appops` is 62k lines. Writing that
 *    into a pipe nobody is draining blocks the *system server's* dump thread
 *    forever, so reading afterwards is not merely slow - it deadlocks, and it
 *    deadlocks something that is not ours.
 * 3. **Bounded, and honest about the bound.** A dump that exceeds [maxBytes]
 *    stops and says so. Truncated output that looks complete is the shape of
 *    error `safety-rules.md` cares about most.
 * 4. **Failure is "could not tell", never "nothing found".** `dumpsys` is
 *    freeform text with no contract across OEMs or versions, so unreadable
 *    output is a fact about our reach, not about the phone.
 *
 * ## Threading
 *
 * A blocking binder transaction to another process, plus a pipe read. Never
 * call from the main thread.
 */
object DumpsysAccess {

    /**
     * The dumps Bulwark is allowed to ask for.
     *
     * Every one is a pure read. Adding an entry means checking that its
     * arguments cannot change state - `dumpsys <service> -h` lists the
     * subcommands, and several services mix reads and writes behind one name.
     */
    enum class Dump(val service: String, val args: List<String>) {
        /**
         * Apps exempted from Doze - they keep running while the phone sleeps.
         *
         * `whitelist` with no operand prints. `whitelist +pkg` and `-pkg`
         * *modify*, which is exactly why this list is closed.
         */
        DOZE_WHITELIST("deviceidle", listOf("whitelist")),

        /**
         * Every recorded use of the microphone, and the state the app was in.
         *
         * `--op` is a **dump option**, not a command - `dumpsys appops -h`
         * lists it alongside `-h` and `--package`, and none of them write.
         *
         * Filtered at the source because the unfiltered dump is 62,998 lines
         * and 2.29 MB, against 6,364 lines and 149 KB for one op. Same records,
         * a fifteenth of the bytes, and 2 MB that never has to exist as a
         * string on a cheap phone.
         */
        MICROPHONE_USES("appops", listOf("--op", "RECORD_AUDIO")),

        /** As [MICROPHONE_USES], for the camera. */
        CAMERA_USES("appops", listOf("--op", "CAMERA")),

        /** As [MICROPHONE_USES], for precise location. */
        PRECISE_LOCATION_USES("appops", listOf("--op", "FINE_LOCATION")),

        /**
         * Which apps woke the phone, and how often.
         *
         * No arguments: `dumpsys alarm` ignores `-h` and prints its whole
         * state, so there is nothing to filter with. 1,505 lines and 117 KB,
         * which is small enough to read whole.
         */
        ALARM_WAKEUPS("alarm", emptyList()),

        /**
         * Which apps asked the phone's sensors for data.
         *
         * **No arguments, and here that is a guardrail rather than a
         * limitation.** `sensorservice` accepts `enable`, `disable` and
         * `restrict` as dump arguments, and every one of them *writes* - this
         * is the clearest case in the app of a dump interface that is really a
         * command surface. Passing nothing is the only safe call, and guardrail
         * 1 is why the argument list is not the caller's to choose.
         */
        SENSOR_REGISTRATIONS("sensorservice", emptyList()),

        /**
         * What has a standing request for the phone's location.
         *
         * No arguments; 631 lines and 70 KB.
         */
        LOCATION_REQUESTS("location", emptyList()),
    }

    /** What one dump produced, or why it produced nothing usable. */
    sealed interface Reading {
        /** Lines, with trailing blanks dropped. May legitimately be empty. */
        data class Lines(val lines: List<String>) : Reading

        /**
         * No answer. **Not** the same as an empty dump, and the UI must not
         * render it as "nothing found".
         */
        data class CouldNotTell(val why: String) : Reading
    }

    /**
     * Runs one allowed dump and returns its text.
     *
     * Never throws for an expected failure - Shizuku down, the service absent,
     * the transaction refused - because every one of those is "could not tell"
     * and the caller has to say so rather than guess.
     */
    fun read(
        dump: Dump,
        maxBytes: Int = MAX_BYTES,
        timeoutMs: Long = TIMEOUT_MS,
    ): Reading {
        val binder = try {
            SystemServiceHelper.getSystemService(dump.service)
        } catch (t: Throwable) {
            return Reading.CouldNotTell("Could not reach the ${dump.service} service: ${t.message}")
        } ?: return Reading.CouldNotTell("This phone has no ${dump.service} service.")

        val wrapped = try {
            ShizukuBinderWrapper(binder)
        } catch (t: Throwable) {
            return Reading.CouldNotTell("Shizuku is not available: ${t.message}")
        }

        return try {
            val text = transactDump(wrapped, dump.args, maxBytes, timeoutMs)
            Reading.Lines(text.lines().dropLastWhile { it.isBlank() })
        } catch (t: Throwable) {
            Reading.CouldNotTell("${t::class.java.simpleName}: ${t.message}")
        }
    }

    /**
     * The `DUMP` transaction, sent through Shizuku's wrapper.
     *
     * Sent as a transaction rather than by calling `IBinder.dump()` on purpose.
     * The wrapper earns its privilege by overriding `transact`; anything that
     * reaches the underlying binder by another route would run as *our* uid and
     * be refused, and it would be refused in a way that looks like the phone
     * saying no rather than like us asking wrongly.
     */
    private fun transactDump(
        binder: IBinder,
        args: List<String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): String {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // Started before the transaction, not after. Guardrail 2: the system
        // server blocks writing into a full pipe, so the drain has to already
        // be running by the time the dump starts producing.
        val collected = StringBuilder()
        var truncated = false
        val reader = Thread {
            runCatching {
                FileInputStream(readSide.fileDescriptor).use { stream ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        if (collected.length + n > maxBytes) {
                            truncated = true
                            break
                        }
                        collected.append(String(buffer, 0, n))
                    }
                }
            }
        }
        reader.start()

        val data = Parcel.obtain()
        try {
            data.writeFileDescriptor(writeSide.fileDescriptor)
            data.writeStringArray(args.toTypedArray())
            binder.transact(IBinder.DUMP_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
            // Closing our copy of the write end is what gives the reader EOF.
            // Without it the read below waits for the timeout every time.
            runCatching { writeSide.close() }
        }

        reader.join(timeoutMs)
        runCatching { readSide.close() }

        if (reader.isAlive) {
            error("The dump did not finish in $timeoutMs ms")
        }
        if (truncated) {
            error("The dump was larger than $maxBytes bytes and was not read whole")
        }
        return collected.toString()
    }

    /**
     * 4 MiB. `deviceidle` is a few kilobytes; `appops` was measured at 62k
     * lines, which this comfortably holds, and `jobscheduler` at 41k. The cap
     * is here so a pathological dump fails loudly instead of taking the app
     * down with it.
     */
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val BUFFER_BYTES = 16 * 1024

    /** Generous. A dump that has not finished in this long is not coming. */
    private const val TIMEOUT_MS = 10_000L
}
