package com.bulwark.app.observability

/**
 * When apps used the microphone, camera and precise location - and whether
 * they were in front of you at the time.
 *
 * This is the sharpest thing in `capability-research/observability.md`. Android
 * records every access to a sensitive app op along with **the state the app was
 * in when it happened**, and `bg` / `cch` mean the app was not on your screen.
 * "This app can use the microphone" is a capability; "this app used the
 * microphone on 13 Sept at 21:36, while it was in the background" is a fact
 * about what it did.
 *
 * Pure. The text arrives from `shizuku/DumpsysAccess`.
 *
 * ## The format, measured rather than assumed
 *
 * `dumpsys appops --op RECORD_AUDIO`, on the Agni 2, 2026-09-13:
 *
 *     Package com.android.chrome:
 *       RECORD_AUDIO (allow):
 *         null=[
 *           Access: [top-s] 2024-08-31 09:54:40.691 (-743d…) duration=+3m53s278ms
 *           Access: [bg-s] 2024-08-31 09:58:33.969 (-743d…) duration=+5ms
 *           Reject: [top-s]2026-06-11 14:03:37.200 (-94d…)
 *
 * Three things worth knowing, all of them found by reading real output:
 *
 * - **Filter at the source.** The unfiltered dump is 62,998 lines and 2.29 MB;
 *   `--op RECORD_AUDIO` is 6,364 lines and 149 KB. Same records, a fifteenth
 *   of the bytes, and it keeps a 2 MB string off a cheap phone's heap.
 * - **`Reject:` has no space after the bracket** where `Access:` does. A
 *   regex written from the `Access` line alone silently mis-parses it.
 * - **The flag before the dash is the app's state**, not the op's: `top`,
 *   `fg`, `fgsvc`, `bg`, `cch`, `pers`. Only `bg` and `cch` mean "not in front
 *   of you", and that distinction is the entire point of this screen.
 */
object AppOpLedger {

    /**
     * The three ops this reads, in plain words.
     *
     * Deliberately three. `observability.md` warns that this would be the most
     * sensitive screen in the app, and a ledger of everything an app ever
     * touched is a surveillance tool pointed at the phone's owner. These are
     * the three a person can reason about and act on.
     */
    enum class Op(val opName: String, val plain: String) {
        MICROPHONE("RECORD_AUDIO", "the microphone"),
        CAMERA("CAMERA", "the camera"),
        PRECISE_LOCATION("FINE_LOCATION", "your precise location"),
    }

    /**
     * The state the app was in when it used the op.
     *
     * [NOT_IN_FRONT_OF_YOU] is `bg` and `cch` - background and cached. Those
     * are the platform's own words for "this process was not what you were
     * looking at", which is why this file can make that claim without guessing.
     */
    enum class AppState {
        IN_FRONT_OF_YOU,
        NOT_IN_FRONT_OF_YOU,

        /** A state string this build does not recognise. Never guessed at. */
        UNRECOGNISED,
    }

    /** One recorded use of one op by one app. */
    data class Use(
        val packageName: String,
        val op: Op,
        val state: AppState,
        /**
         * `yyyy-MM-dd HH:mm:ss.SSS`, exactly as the platform printed it.
         *
         * Kept as text on purpose. The format sorts correctly as a string -
         * fixed-width, most significant first - so nothing here needs a date
         * library or an API-level guard to order it, and the value shown to a
         * person is the one the phone actually said.
         */
        val at: String,
    )

    /** What one parse produced. */
    data class Reading(
        val uses: List<Use>,
        val unreadable: Int,
    ) {
        /** Uses where the app was not on screen. The point of the exercise. */
        val whileNotLooking: List<Use>
            get() = uses.filter { it.state == AppState.NOT_IN_FRONT_OF_YOU }

        val readNothing: Boolean get() = uses.isEmpty() && unreadable > 0
    }

    /** One app's use of one op, rolled up. */
    data class Summary(
        val packageName: String,
        val op: Op,
        val times: Int,
        /** The most recent, which is the one a person reads first. */
        val mostRecent: String,
    )

    private val PACKAGE = Regex("""^Package (\S+):$""")

    /**
     * `Access: [bg-s] 2026-09-13 21:36:06.258 (…)`, and the `Reject:` variant
     * that omits the space after the bracket.
     *
     * Only `Access` is kept. A `Reject` is the platform refusing the app, which
     * is a different fact and not one this card claims to report - counting it
     * as a use would say an app did something it was stopped from doing.
     */
    private val ACCESS = Regex(
        """^Access:\s*\[([a-z]+)-[a-z]+]\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""",
    )

    private val NOT_IN_FRONT = setOf("bg", "cch")
    private val IN_FRONT = setOf("top", "fg", "fgsvc", "pers")

    /**
     * Parses one `--op`-filtered dump.
     *
     * [op] is passed in rather than read back out of the text: the dump was
     * requested for exactly one op, so trusting the request is both simpler and
     * safer than re-deriving it from a header that OEMs are free to reword.
     *
     * Lines that are neither a package header nor an access record are skipped
     * silently - the dump is mostly mode listings and this is not trying to
     * read them. Only a line that *looks* like an access and does not parse is
     * counted as unreadable.
     */
    fun parse(lines: List<String>, op: Op): Reading {
        val uses = mutableListOf<Use>()
        var unreadable = 0
        var packageName: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val header = PACKAGE.find(line)
            if (header != null) {
                packageName = header.groupValues[1]
                continue
            }

            if (!line.startsWith("Access:")) continue

            val match = ACCESS.find(line)
            val owner = packageName
            if (match == null || owner == null) {
                // Looked like a record and was not one, or arrived before any
                // package header. Either way Bulwark cannot attribute it, and
                // an unattributed access is worse than no access.
                unreadable++
                continue
            }
            uses += Use(
                packageName = owner,
                op = op,
                state = stateOf(match.groupValues[1]),
                at = match.groupValues[2],
            )
        }
        return Reading(uses, unreadable)
    }

    private fun stateOf(flag: String): AppState = when (flag) {
        in NOT_IN_FRONT -> AppState.NOT_IN_FRONT_OF_YOU
        in IN_FRONT -> AppState.IN_FRONT_OF_YOU
        else -> AppState.UNRECOGNISED
    }

    /**
     * Rolls several readings up into one row per app per op, most recent first.
     *
     * Only uses where the app was **not** in front of you. Everything else is
     * the phone working as a person expects - you opened the camera app and it
     * used the camera - and putting that on this card would bury the line that
     * matters in a hundred that do not.
     */
    fun summarise(readings: List<Reading>): List<Summary> =
        readings
            .flatMap { it.whileNotLooking }
            .groupBy { it.packageName to it.op }
            .map { (key, uses) ->
                Summary(
                    packageName = key.first,
                    op = key.second,
                    times = uses.size,
                    mostRecent = uses.maxOf { it.at },
                )
            }
            .sortedWith(compareByDescending<Summary> { it.mostRecent }.thenBy { it.packageName })

    /**
     * The card's headline.
     *
     * States the count and stops - `design.md` rule 7. It does not say these
     * apps are spying: a music app recording audio in the background, or a
     * navigation app holding location, is doing the job it was installed for.
     * What Bulwark knows is that it happened, and that Android does not show
     * you this.
     */
    fun headline(summaries: List<Summary>): String {
        if (summaries.isEmpty()) {
            return "No app used the microphone, camera or your precise location " +
                "while it was out of sight."
        }
        val apps = summaries.map { it.packageName }.distinct().size
        val subject = if (apps == 1) "1 app" else "$apps apps"
        return "$subject used the microphone, camera or your precise location " +
            "while they were not in front of you."
    }

    /** The line under the headline. Null when there is nothing to qualify. */
    fun detail(summaries: List<Summary>): String? {
        if (summaries.isEmpty()) return null
        return "Android records this and does not show it to you. It is not by " +
            "itself wrong - a music app or a navigation app has a reason. " +
            "Bulwark reports what happened and leaves the judgement to you."
    }

    /** One row, in a sentence. */
    fun line(summary: Summary): String {
        val times = if (summary.times == 1) "once" else "${summary.times} times"
        return "${summary.packageName} used ${summary.op.plain} $times, " +
            "most recently ${forReading(summary.mostRecent)}"
    }

    /**
     * The timestamp as a person reads it: `2026-09-13 21:31`.
     *
     * The platform prints milliseconds and the model keeps them - they are
     * what the phone said, and they order the records exactly. But
     * `2026-09-13 21:31:22.041` on a card is three digits of noise that no
     * reader can use, and `design.md` rule 6 is that the sentence is the
     * element. Trimmed for display only; nothing downstream reads this back.
     *
     * Anything not in the expected shape is passed through untouched rather
     * than mangled - a different OEM format should look odd, not wrong.
     */
    fun forReading(timestamp: String): String =
        if (timestamp.length >= 16 && timestamp[10] == ' ') timestamp.take(16) else timestamp

    /** Said out loud when records did not parse. Null when everything read. */
    fun unreadableNotice(readings: List<Reading>): String? {
        val total = readings.sumOf { it.unreadable }
        if (total == 0) return null
        val records = if (total == 1) "1 record" else "$total records"
        return "Bulwark could not read $records in this phone's answer, so the " +
            "list above may be short. That is a limit of Bulwark, not a finding " +
            "about your phone."
    }
}
