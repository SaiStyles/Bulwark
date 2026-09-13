package com.bulwark.app.observability

/**
 * Who is allowed to keep working while the phone sleeps.
 *
 * Doze is the thing that makes an idle Android phone idle. An app on the
 * exemption list is outside it, so it can hold wakelocks, run jobs and reach
 * the network while the screen is off. That is a *decision somebody made* -
 * which is the whole point of `capability-research/observability.md`: not what
 * is running now, but what apps arranged for when nobody was watching.
 *
 * Pure. The text arrives from `shizuku/DumpsysAccess`; nothing here touches
 * Android, so all of it is unit-testable (`conventions.md`, Testing).
 *
 * ## The format, measured rather than assumed
 *
 * `dumpsys deviceidle whitelist` prints one row per app, `source,package,uid`:
 *
 *     system-excidle,com.android.providers.calendar,10059
 *     system,com.google.android.gms,10115
 *     user,com.whatsapp,10231
 *
 * 36 rows on the Agni 2, 2026-09-13. **`dumpsys` has no contract** - it is
 * freeform text that differs across OEMs and versions - so a row that does not
 * fit is counted and reported, never dropped quietly and never guessed at.
 */
object DozeExemptions {

    /**
     * Who put the app on the list.
     *
     * Deliberately three values and deliberately shallow. The platform's own
     * distinction between `system` and `system-excidle` is about *which* power
     * restrictions are lifted, and Bulwark does not currently read enough to
     * explain that difference to someone. Saying "this came with the phone" is
     * true of both; inventing a sharper story would be the stranger's-verdict
     * problem `safety-rules.md` retired the removal ratings over.
     */
    enum class Source {
        /** On the list because the phone shipped that way. */
        PREINSTALLED,

        /** On the list because it was added after the fact - by you, or by the app asking. */
        ADDED_LATER,

        /** A source string this build does not recognise. Not a guess. */
        UNRECOGNISED,
    }

    /** One app that is outside Doze. */
    data class Exemption(
        val packageName: String,
        val uid: Int,
        val source: Source,
    )

    /**
     * What one parse produced.
     *
     * [unreadable] is carried rather than swallowed: an OEM that prints a
     * different shape must surface as "Bulwark could not read some of this",
     * because a shorter list presented as complete is a false all-clear.
     */
    data class Reading(
        val exemptions: List<Exemption>,
        val unreadable: Int,
    ) {
        /** The ones somebody added after the phone shipped. */
        val addedLater: List<Exemption> get() = exemptions.filter { it.source == Source.ADDED_LATER }

        /** True when nothing at all could be read - not the same as an empty list. */
        val readNothing: Boolean get() = exemptions.isEmpty() && unreadable > 0
    }

    private const val PREINSTALLED_PREFIX = "system"
    private const val ADDED_LATER_PREFIX = "user"

    /**
     * Parses `source,package,uid` rows, keeping what it understands and
     * counting what it does not.
     *
     * Blank lines are ignored rather than counted as unreadable - a trailing
     * newline is not a parse failure, and treating it as one would put a
     * permanent "could not read 1 line" on an otherwise clean screen.
     */
    fun parse(lines: List<String>): Reading {
        val exemptions = mutableListOf<Exemption>()
        var unreadable = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val parts = line.split(",")
            if (parts.size != 3) {
                unreadable++
                continue
            }
            val packageName = parts[1].trim()
            val uid = parts[2].trim().toIntOrNull()
            if (packageName.isEmpty() || uid == null) {
                unreadable++
                continue
            }
            exemptions += Exemption(
                packageName = packageName,
                uid = uid,
                source = sourceOf(parts[0].trim()),
            )
        }
        return Reading(collapseDuplicates(exemptions), unreadable)
    }

    /**
     * One row per app, however many lists it is on.
     *
     * The platform keeps **two** system allowlists - `system` and
     * `system-excidle` - and a package can sit on both, so `whitelist` prints
     * it twice. Found on the Agni 2 by looking at the card: half the list was
     * doubled and the headline count was inflated to match. A wrong number
     * about someone's phone, on the screen whose entire job is being right.
     *
     * Keyed on package **and** uid, because the same package name under a
     * different uid is a different install and collapsing those would hide one.
     *
     * When an app appears with more than one source, [Source.ADDED_LATER]
     * wins. Both facts are true - it is on a system list and on the user list -
     * and that is the one a person might not already know.
     */
    private fun collapseDuplicates(rows: List<Exemption>): List<Exemption> {
        val byApp = LinkedHashMap<Pair<String, Int>, Exemption>()
        for (row in rows) {
            val key = row.packageName to row.uid
            val existing = byApp[key]
            byApp[key] = when {
                existing == null -> row
                existing.source == Source.ADDED_LATER -> existing
                row.source == Source.ADDED_LATER -> row
                // Neither is the interesting case; keep what was seen first
                // rather than letting row order decide silently.
                else -> existing
            }
        }
        return byApp.values.sortedBy { it.packageName }
    }

    /**
     * Maps the platform's source string onto what a person needs to know.
     *
     * Matched by prefix because `system` and `system-excidle` are both the
     * phone's own doing, and a future variant of either should land as
     * "came with the phone" rather than as unrecognised. Anything else is
     * [Source.UNRECOGNISED] - fail closed, do not guess.
     */
    private fun sourceOf(field: String): Source = when {
        field.startsWith(PREINSTALLED_PREFIX) -> Source.PREINSTALLED
        field.startsWith(ADDED_LATER_PREFIX) -> Source.ADDED_LATER
        else -> Source.UNRECOGNISED
    }

    /**
     * The sentence the card leads with.
     *
     * States the count and stops. It deliberately does **not** say these apps
     * are draining the battery or misbehaving: an exemption is a capability,
     * and plenty of them are ordinary - a messaging app that has to receive
     * messages is the obvious case. `design.md` rule 7, name what a thing is.
     */
    fun headline(reading: Reading): String {
        val n = reading.exemptions.size
        if (n == 0) return "No app on this phone is exempt from sleeping."
        val apps = if (n == 1) "1 app is" else "$n apps are"
        return "$apps allowed to keep working while your phone sleeps."
    }

    /**
     * The line under the headline, or null when there is nothing to add.
     *
     * Says what Bulwark can and cannot do about it in the same breath. Bulwark
     * reads this list; it does not edit it - removing an exemption is a write
     * to `deviceidle`, and `DumpsysAccess` is a closed set of reads on purpose.
     * Offering a fix the app does not have is the false-sense-of-protection
     * failure `safety-rules.md` calls worse than none.
     */
    fun detail(reading: Reading): String? {
        if (reading.exemptions.isEmpty()) return null
        val added = reading.addedLater.size
        val origin = when {
            added == 0 -> "All of them came with the phone."
            added == reading.exemptions.size -> "All of them were added after it shipped."
            added == 1 -> "One of them was added after the phone shipped."
            else -> "$added of them were added after the phone shipped."
        }
        return "$origin Bulwark can show you this list, and can switch an app " +
            "off or block its internet - it cannot take an app off the list."
    }

    /** Said out loud when rows did not parse. Null when every row read cleanly. */
    fun unreadableNotice(reading: Reading): String? {
        if (reading.unreadable == 0) return null
        val rows = if (reading.unreadable == 1) "1 line" else "${reading.unreadable} lines"
        return "Bulwark could not read $rows of this phone's answer, so the " +
            "list above may be short. This is a limit of Bulwark, not a finding " +
            "about your phone."
    }
}
