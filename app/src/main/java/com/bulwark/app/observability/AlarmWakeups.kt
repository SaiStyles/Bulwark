package com.bulwark.app.observability

/**
 * Which apps wake your phone up, and how often.
 *
 * A wakeup is an alarm that pulls the device out of sleep to run something.
 * It is the most concrete "what did this app do while I was not looking" there
 * is: the phone was off, and an app decided it should not be.
 *
 * Android shows a person none of this. Battery settings show *consumption*,
 * which is the symptom; this is the decision that caused it.
 *
 * Pure. The text arrives from `shizuku/DumpsysAccess`.
 *
 * ## The format, measured on the Agni 2, 2026-09-13
 *
 *     Alarm Stats:
 *     1000:com.pri.screenoff.killer +1s173ms running, 45 wakeups:
 *       +868ms 35 wakes 35 alarms, last -1h23m54s308ms:
 *         *walarm*:pri.intent.action.screen_off_three_min
 *     1000:android +13s250ms running, 211 wakeups:
 *
 * `dumpsys alarm` is 1,505 lines and 117 KB, and takes **no arguments** - it
 * ignores `-h` and prints the whole state. So this reads it whole, which at
 * that size is fine, and picks out the one line per app that carries the count.
 *
 * The indented lines under each app name the individual alarm tags. They are
 * not read: `*walarm*:pri.intent.action.screen_off_three_min` is an internal
 * string that means nothing to the person holding the phone, and printing it
 * would be the kind of detail that looks like insight and is not.
 */
object AlarmWakeups {

    /** One app, and the number of times it woke the phone. */
    data class Waker(
        val packageName: String,
        /**
         * As the phone printed it: `1000` for system uids, `u0a115` for apps.
         *
         * Kept as text rather than decoded. `u0a115` means user 0, app 115,
         * and turning that into 10115 is arithmetic Bulwark does not need and
         * would have to keep right across multi-user devices. Nothing reads
         * this yet; it is carried so a row can be identified if something ever
         * does.
         */
        val uid: String,
        val wakeups: Int,
    )

    data class Reading(
        val wakers: List<Waker>,
        val unreadable: Int,
    ) {
        val readNothing: Boolean get() = wakers.isEmpty() && unreadable > 0

        /** Total wakeups across every app, which is the number people feel. */
        val total: Int get() = wakers.sumOf { it.wakeups }
    }

    /**
     * `1000:android +13s250ms running, 211 wakeups:` and
     * `u0a115:com.google.android.gms +13s156ms running, 453 wakeups:`
     *
     * **The uid is not a number.** It is numeric for system uids and Android's
     * `u0a<n>` shorthand for app uids, which is most of them. The first
     * version of this regex required digits, read the four system rows, and
     * silently lost Play Services at 453 wakeups and SystemUI at 263 - caught
     * on the Agni 2 only because the card said out loud that it could not read
     * 13 lines. The honesty rule found the bug the parser hid.
     *
     * Anchored on the `N wakeups:` tail rather than on the `Alarm Stats:`
     * heading above it. Section headings are the part OEMs reword; this line
     * has a shape specific enough to match on its own.
     */
    private val WAKER = Regex("""^(\S+):(\S+)\s.*?,\s(\d+)\swakeups:$""")

    fun parse(lines: List<String>): Reading {
        val wakers = mutableListOf<Waker>()
        var unreadable = 0

        for (raw in lines) {
            val line = raw.trim()
            // Cheap guard first: this runs over 1,500 lines and only a handful
            // are candidates, so the regex should not see most of them.
            if (!line.endsWith("wakeups:")) continue

            val match = WAKER.find(line)
            if (match == null) {
                unreadable++
                continue
            }
            val count = match.groupValues[3].toIntOrNull()
            if (count == null) {
                unreadable++
                continue
            }
            wakers += Waker(
                packageName = match.groupValues[2],
                uid = match.groupValues[1],
                wakeups = count,
            )
        }
        // Most wakeups first: the list is read top-down and the top is the
        // answer to "what is keeping my phone awake".
        return Reading(wakers.sortedByDescending { it.wakeups }, unreadable)
    }

    /**
     * The headline.
     *
     * Counts and stops. It does not say the phone has a problem: a messaging
     * app that wakes to deliver messages is doing its job, and the alarm stats
     * reset when the phone reboots, so a big number may only mean a long
     * uptime. Saying which of those it is would be a guess.
     */
    fun headline(reading: Reading): String {
        if (reading.wakers.isEmpty()) return "Nothing has woken this phone since it last restarted."
        val apps = if (reading.wakers.size == 1) "1 app has" else "${reading.wakers.size} apps have"
        val times = if (reading.total == 1) "once" else "${reading.total} times"
        return "$apps woken this phone $times since it last restarted."
    }

    /** One row. */
    fun line(waker: Waker): String {
        val times = if (waker.wakeups == 1) "once" else "${waker.wakeups} times"
        return "${waker.packageName} woke the phone $times"
    }

    /** Null when every candidate line read cleanly. */
    fun unreadableNotice(reading: Reading): String? {
        if (reading.unreadable == 0) return null
        val rows = if (reading.unreadable == 1) "1 line" else "${reading.unreadable} lines"
        return "Bulwark could not read $rows of this phone's alarm record, so " +
            "the list may be short. That is a limit of Bulwark, not a finding."
    }
}
