package com.bulwark.app.observability

/**
 * What is asking for your location **right now**, and how precisely.
 *
 * The companion to `AppOpLedger`, and a different question. The ledger says an
 * app *used* your location at a time in the past; this says an app has a
 * standing request open, so it will be told again whenever the phone has a new
 * fix. A live subscription is a different thing from a past read, and Android
 * shows a person neither.
 *
 * Pure. The text arrives from `shizuku/DumpsysAccess`.
 *
 * ## The format, measured on the Agni 2, 2026-09-13
 *
 *     Location Providers:
 *       passive provider:
 *         listeners:
 *           10115/com.google.android.gms[fused_location_provider]/1D38F30C Request[@+24855d… BALANCED, …]
 *           1000/android[SensorNotificationService]/E2A532A2 Request[PASSIVE, minUpdateInterval=+30m…]
 *       network provider:
 *         listeners:
 *           10102/com.google.android.as/D718A796 (COARSE) Request[@+6h0m0s0ms LOW_POWER, …]
 *
 * `dumpsys location` is 631 lines and 70 KB and takes no arguments.
 *
 * **Only the live `listeners:` are read.** The dump also carries an `Event Log`
 * of registrations and releases going back hours. That is a richer history and
 * a much easier thing to get wrong - it is a stream of paired events, and a
 * missed release reads as a request that never stopped. Standing requests are
 * a fact the dump states directly, so that is what this reports.
 */
object LocationRequests {

    /**
     * How precisely an app asked to be located.
     *
     * The platform's own words, not a scale Bulwark invented. [UNRECOGNISED]
     * is a real value: a quality string this build does not know is not
     * quietly downgraded to something reassuring.
     */
    enum class Precision(val plain: String) {
        HIGH("the most precise location available"),
        BALANCED("a fairly precise location"),
        LOW_POWER("a rough location"),
        /** Takes whatever another app has already asked for; asks for nothing itself. */
        PASSIVE("your location only when something else asks for it"),
        UNRECOGNISED("your location"),
    }

    /** One app with a standing request open. */
    data class Request(
        val packageName: String,
        val uid: Int,
        val precision: Precision,
    )

    data class Reading(
        val requests: List<Request>,
        val unreadable: Int,
    ) {
        val readNothing: Boolean get() = requests.isEmpty() && unreadable > 0

        /** The ones actively asking, as opposed to listening in on others. */
        val active: List<Request> get() = requests.filter { it.precision != Precision.PASSIVE }
    }

    private const val LISTENERS = "listeners:"

    /**
     * `10102/com.google.android.as/D718A796 (COARSE) Request[@+6h LOW_POWER, …]`
     *
     * The bracketed part after the package is an optional attribution tag and
     * the `(COARSE)` is optional too, so both are skipped rather than matched.
     */
    private val LISTENER = Regex(
        """^(\d+)/([A-Za-z0-9_.]+)(?:\[[^]]*])?/\w+\s+(?:\([A-Z]+\)\s+)?Request\[(.*)]$""",
    )

    /**
     * Reads the standing requests under every provider's `listeners:`.
     *
     * Bounded to the listener blocks by a simple depth rule rather than by
     * hunting for provider names: a `listeners:` line opens a block, and the
     * next line that is not a listener closes it. Provider names are the part
     * an OEM may add to; the block shape is not.
     */
    fun parse(lines: List<String>): Reading {
        val requests = mutableListOf<Request>()
        var unreadable = 0
        var inListeners = false

        for (raw in lines) {
            val line = raw.trim()

            if (line == LISTENERS) {
                inListeners = true
                continue
            }
            if (!inListeners) continue

            val match = LISTENER.find(line)
            if (match == null) {
                // A line that opens with a uid/package but does not parse is a
                // listener Bulwark failed to read. Anything else is simply the
                // end of the block.
                if (line.firstOrNull()?.isDigit() == true && line.contains("Request[")) {
                    unreadable++
                } else {
                    inListeners = false
                }
                continue
            }
            val uid = match.groupValues[1].toIntOrNull()
            if (uid == null) {
                unreadable++
                continue
            }
            requests += Request(
                packageName = match.groupValues[2],
                uid = uid,
                precision = precisionOf(match.groupValues[3]),
            )
        }
        return Reading(collapse(requests), unreadable)
    }

    /**
     * One row per app, keeping the most precise request it has open.
     *
     * An app commonly registers with several providers at once - the sample
     * above shows `com.google.android.gms` twice under one provider alone.
     * Listing it repeatedly would inflate the count, which is the same bug the
     * Doze card shipped with.
     */
    private fun collapse(requests: List<Request>): List<Request> {
        val byApp = LinkedHashMap<String, Request>()
        for (request in requests) {
            val existing = byApp[request.packageName]
            byApp[request.packageName] =
                if (existing == null || request.precision.ordinal < existing.precision.ordinal) {
                    request
                } else {
                    existing
                }
        }
        return byApp.values.sortedWith(compareBy({ it.precision.ordinal }, { it.packageName }))
    }

    private fun precisionOf(request: String): Precision = when {
        request.contains("HIGH_ACCURACY") -> Precision.HIGH
        request.contains("BALANCED") -> Precision.BALANCED
        request.contains("LOW_POWER") -> Precision.LOW_POWER
        request.contains("PASSIVE") -> Precision.PASSIVE
        else -> Precision.UNRECOGNISED
    }

    /**
     * The headline.
     *
     * Counts the ones actively asking. A passive listener is genuinely
     * different - it costs nothing and asks for nothing - and counting it
     * beside the others would overstate what is happening.
     */
    fun headline(reading: Reading): String {
        val active = reading.active
        if (active.isEmpty()) return "No app has a standing request for your location."
        val apps = if (active.size == 1) "1 app is" else "${active.size} apps are"
        return "$apps asking this phone where it is."
    }

    /** The line under the headline. */
    fun detail(reading: Reading): String? {
        if (reading.requests.isEmpty()) return null
        val passive = reading.requests.size - reading.active.size
        val tail = if (passive == 0) {
            ""
        } else {
            " A further $passive listens in on other apps' requests without asking itself."
        }
        return "A standing request means the app is told again every time the " +
            "phone works out where it is - not that it asked once.$tail"
    }

    /** One row. */
    fun line(request: Request): String =
        "${request.packageName} is asking for ${request.precision.plain}"

    fun unreadableNotice(reading: Reading): String? {
        if (reading.unreadable == 0) return null
        val rows = if (reading.unreadable == 1) "1 request" else "${reading.unreadable} requests"
        return "Bulwark could not read $rows in this phone's answer, so the list " +
            "may be short. That is a limit of Bulwark, not a finding."
    }
}
