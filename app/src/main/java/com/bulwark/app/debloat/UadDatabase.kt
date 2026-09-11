package com.bulwark.app.debloat

import android.content.Context
import org.json.JSONObject

/** How dangerous the community database says removing a package is. */
enum class RemovalRating {
    /** Safe for most people. Offered plainly. */
    RECOMMENDED,

    /** Has consequences worth stating. Offered with a warning. */
    ADVANCED,

    /** Only if you know exactly why. Offered, warned, never uninstallable. */
    EXPERT,

    /**
     * Bootloops, broken modules, dead radios.
     *
     * Still *disableable*, with the community's own description quoted at the
     * user, because disable is reversible with `pm enable` and keeps app data.
     * Never uninstallable.
     */
    UNSAFE,

    /**
     * Not in the database at all.
     *
     * **Offered for disable, with the gap stated plainly; never for
     * uninstall.** 57 of the test device's 274 system packages land here
     * because Lava is a small OEM, and they are ordinary vendor software
     * nobody has audited rather than landmines.
     *
     * This used to read "treated as not-offered, not as safe", citing an
     * earlier rule 1 of `context/_shared/safety-rules.md` that said allowlist,
     * never blocklist. Both were replaced on 2026-09-10: the rule is now
     * "offer, do not enforce", and withholding a *reversible* choice from
     * someone about their own phone needs a better reason than "we were not
     * sure". "Nobody documented this" is information to hand over.
     *
     * Silence is still not evidence of safety — which is why it gates
     * uninstall, the one that is not reversible.
     */
    UNKNOWN,
    ;

    companion object {
        fun parse(raw: String?): RemovalRating = when (raw) {
            "Recommended" -> RECOMMENDED
            "Advanced" -> ADVANCED
            "Expert" -> EXPERT
            "Unsafe" -> UNSAFE
            else -> UNKNOWN
        }
    }
}

/**
 * A description with the noise removed and **nothing else**.
 *
 * UAD descriptions are multi-line, and the lines after the first are the ones
 * that say what breaks:
 *
 *     Private Compute Services. On-device behavior analysis
 *     Enables live caption, music recognition and smart replies.
 *     Seems to be a dependency of System Intelligence.
 *     https://play.google.com/...
 *
 * Bulwark used to show only line one, under a green badge. That hid exactly
 * the part a person needs - and SAI caught it by knowing the package better
 * than the summary did. Showing the first line of a description that says
 * "dependency of System Intelligence" on line three is not a truncation, it is
 * a misrepresentation.
 *
 * URLs are dropped because they are unreachable: Bulwark has no `INTERNET`
 * permission and no browser hand-off, so a link is a dead end on screen.
 */
fun String.readableDescription(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("http") }
        .joinToString(" ")

/** What the community database knows about one package. */
data class UadEntry(
    val rating: RemovalRating,
    /** `Aosp`, `Oem`, `Google`, `Carrier`, `Misc` — useful for grouping. */
    val source: String,
    /** Plain-language explanation of what it does and what breaks. */
    val description: String?,
    /** Installed packages that depend on this one. */
    val neededBy: List<String>,
)

/**
 * The Universal Debloater Alliance package database, bundled and read offline.
 *
 * 5,372 entries of community-verified knowledge about what preinstalled
 * packages actually do — years of unglamorous work this project did not do.
 * GPL-3.0, compatible with ours. Provenance in
 * `assets/uad-packages-SOURCE.txt`.
 *
 * **Bundled rather than fetched.** Upstream downloads this at launch; Bulwark
 * holds no `INTERNET` permission and the build fails if one appears
 * (`context/_shared/conventions.md`). So it ships with the APK and is refreshed
 * by rebuilding.
 *
 * **A bundled list rots.** Say when it was built rather than implying it is
 * current, and offer file import before the staleness matters.
 *
 * Parsed with `org.json` from the platform — no new dependency for reading one
 * file (`context/_shared/supply-chain.md`).
 */
class UadDatabase private constructor(private val entries: Map<String, UadEntry>) {

    val size: Int get() = entries.size

    operator fun get(packageName: String): UadEntry? = entries[packageName]

    fun rating(packageName: String): RemovalRating =
        entries[packageName]?.rating ?: RemovalRating.UNKNOWN

    companion object {
        private const val ASSET = "uad-packages.json"

        /** Snapshot date of the bundled data. Show it; do not hide staleness. */
        const val SNAPSHOT = "2026-09-10"

        @Volatile
        private var cached: UadDatabase? = null

        /**
         * The parsed database, read from the asset once per process.
         *
         * ~1 MB of JSON. The previous KDoc told callers to "hold the result"
         * and the one call site did not - it re-parsed on every refresh, so
         * every switch-off re-read a megabyte to answer a question whose answer
         * had not changed. Telling a caller to cache is a wish; caching here is
         * a property (`lessons.md` lesson 1).
         *
         * Safe to hold forever: the parsed data is immutable, bundled in the
         * APK, and cannot change without a new install. **No `Context` is
         * retained** - only the parsed map - so this cannot leak an Activity.
         *
         * Still blocking on first call. Keep it off the main thread.
         */
        fun load(context: Context): UadDatabase =
            cached ?: synchronized(this) {
                cached ?: parse(context).also { cached = it }
            }

        private fun parse(context: Context): UadDatabase {
            val json = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            val root = JSONObject(json)
            val parsed = HashMap<String, UadEntry>(root.length())

            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val o = root.optJSONObject(name) ?: continue
                val neededBy = o.optJSONArray("n")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
                }.orEmpty()

                parsed[name] = UadEntry(
                    rating = RemovalRating.parse(o.optString("r").takeIf { it.isNotEmpty() }),
                    source = o.optString("l"),
                    description = o.optString("d").takeIf { it.isNotEmpty() },
                    neededBy = neededBy,
                )
            }
            return UadDatabase(parsed)
        }

        /** For tests. */
        fun of(entries: Map<String, UadEntry>) = UadDatabase(entries)
    }
}
