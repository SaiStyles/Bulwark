package com.bulwark.app.observability

/**
 * Which apps asked the phone's sensors for data, and when.
 *
 * The accelerometer is not a permission. Nothing asks you about it, nothing
 * appears in Settings, and an app can read it whenever it likes - which is why
 * it has a long history of being used to infer what a microphone permission
 * would have been refused for. An app registering the accelerometer while it
 * is not on screen is the plainest example of the thing this whole capability
 * is named after.
 *
 * Pure. The text arrives from `shizuku/DumpsysAccess`.
 *
 * ## The format, measured on the Agni 2, 2026-09-13
 *
 * Two sections of one dump, and both are needed. The handle is a number:
 *
 *     Sensor List:
 *     0x00000001) ACCELEROMETER  | MTK  | ver: 1 | type: android.sensor.accelerometer(1) …
 *
 * and the registrations refer to it:
 *
 *     Previous Registrations:
 *     23:00:37 + 0x0000000b pid=13735 uid=10115 package=com.example.app samplingPeriod=20000us
 *     23:00:39 - 0x0000000a pid=13735 uid=10115 package=com.example.app
 *
 * `+` is a registration and `-` is the matching release. Only `+` is read: a
 * release is the app stopping, which is not the fact being reported.
 *
 * **Better than the research note assumed.** `observability.md` expected to
 * have to map uid to package ourselves; the dump prints `package=` inline, so
 * nothing has to be cross-referenced against the package list.
 *
 * **The timestamps have no date** - `23:00:37`, a clock time and nothing more.
 * This is a small ring buffer of recent events, so the copy says "recently"
 * and shows the clock, and never implies a day it cannot know.
 *
 * **`package=` is what the app called itself**, not a verified package name.
 * The Agni 2 prints `package=com.facebook.ads.redexgen.X.Tn` - a class, not an
 * installed package - because the field carries the string the caller passed.
 * Reported as-is: it is what the phone said, and substituting a "corrected"
 * name would be inventing an attribution the dump does not make. A row that
 * does not look like a package name is itself worth seeing.
 */
object SensorRegistrations {

    /** One app's use of one sensor. */
    data class Registration(
        val packageName: String,
        /** The sensor's name as the phone reports it, e.g. `ACCELEROMETER`. */
        val sensor: String,
        /** Clock time, `HH:mm:ss`. No date is available - see the class note. */
        val at: String,
    )

    data class Reading(
        val registrations: List<Registration>,
        val unreadable: Int,
    ) {
        val readNothing: Boolean get() = registrations.isEmpty() && unreadable > 0
    }

    /** One app, one sensor, rolled up. */
    data class Summary(
        val packageName: String,
        val sensor: String,
        val times: Int,
        val mostRecent: String,
    )

    /** `0x00000001) ACCELEROMETER  | MTK | …` */
    private val SENSOR = Regex("""^(0x[0-9a-fA-F]+)\)\s+(\S+)""")

    /** `23:00:37 + 0x0000000b pid=13735 uid=10115 package=com.example.app …` */
    private val REGISTRATION = Regex(
        """^(\d{2}:\d{2}:\d{2})\s+([+-])\s+(0x[0-9a-fA-F]+)\s+pid=\s*\d+\s+uid=\s*\d+\s+package=(\S+)""",
    )

    /**
     * Reads both sections of one `dumpsys sensorservice`.
     *
     * The names are gathered first because the registrations below refer to
     * them by handle. A handle with no name is still reported - as the handle -
     * rather than dropped: an unnamed sensor is worse copy, but a silently
     * missing row is a worse fact.
     */
    fun parse(lines: List<String>): Reading {
        val names = mutableMapOf<String, String>()
        for (raw in lines) {
            val match = SENSOR.find(raw.trim()) ?: continue
            names[match.groupValues[1].lowercase()] = match.groupValues[2]
        }

        val registrations = mutableListOf<Registration>()
        var unreadable = 0

        for (raw in lines) {
            val line = raw.trim()
            // Cheap guard: a registration line always names a package.
            if (!line.contains(" package=")) continue

            val match = REGISTRATION.find(line)
            if (match == null) {
                unreadable++
                continue
            }
            // `-` is the app releasing the sensor. Not a use, so not reported.
            if (match.groupValues[2] != "+") continue

            val handle = match.groupValues[3].lowercase()
            registrations += Registration(
                packageName = match.groupValues[4],
                sensor = names[handle] ?: handle,
                at = match.groupValues[1],
            )
        }
        return Reading(registrations, unreadable)
    }

    /** One row per app per sensor, most recent first. */
    fun summarise(reading: Reading): List<Summary> =
        reading.registrations
            .groupBy { it.packageName to it.sensor }
            .map { (key, uses) ->
                Summary(
                    packageName = key.first,
                    sensor = key.second,
                    times = uses.size,
                    mostRecent = uses.maxOf { it.at },
                )
            }
            .sortedWith(compareByDescending<Summary> { it.mostRecent }.thenBy { it.packageName })

    /**
     * The headline.
     *
     * Says what a sensor registration is, because almost nobody knows: it is
     * the one thing here that needs a sentence of teaching rather than a count.
     */
    fun headline(summaries: List<Summary>): String {
        if (summaries.isEmpty()) {
            return "Nothing in this phone's recent record asked its sensors for data."
        }
        val apps = summaries.map { it.packageName }.distinct().size
        val subject = if (apps == 1) "1 app" else "$apps apps"
        return "$subject recently asked this phone's sensors for data."
    }

    /** The line under the headline. */
    fun detail(summaries: List<Summary>): String? {
        if (summaries.isEmpty()) return null
        return "Sensors are not a permission - nothing asks you, and Settings " +
            "shows you nothing. An app can read movement whenever it likes. " +
            "Many names below are libraries inside apps, or the system's own " +
            "listeners, because the phone records whatever the caller named " +
            "itself. This is a short recent record, not a full history, and " +
            "the times are clock times without a date."
    }

    /** One row, in plain words. Sensor names are the phone's own. */
    fun line(summary: Summary): String {
        val sensor = summary.sensor.lowercase().replace('_', ' ')
        val times = if (summary.times == 1) "once" else "${summary.times} times"
        return "${summary.packageName} read the $sensor $times, most recently at ${summary.mostRecent}"
    }

    fun unreadableNotice(reading: Reading): String? {
        if (reading.unreadable == 0) return null
        val rows = if (reading.unreadable == 1) "1 line" else "${reading.unreadable} lines"
        return "Bulwark could not read $rows of this phone's sensor record, so " +
            "the list may be short. That is a limit of Bulwark, not a finding."
    }
}
