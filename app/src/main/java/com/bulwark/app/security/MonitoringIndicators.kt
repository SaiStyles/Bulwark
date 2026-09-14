package com.bulwark.app.security

import android.content.Context
import org.json.JSONArray

/** What a list claims an app is. Never collapsed - see [MonitoringFinding.kinds]. */
enum class MonitoringKind { STALKERWARE, WATCHWARE }

/** How an installed app was recognised. Both is stronger than either. */
enum class MatchSignal {
    /** Exact, case-sensitive package name. */
    PACKAGE_NAME,

    /** SHA-1 of the signing certificate - survives a rename. */
    SIGNING_CERTIFICATE,
}

/** One installed app, reduced to what matching needs. Null certificate means unread. */
data class InstalledApp(
    val packageName: String,
    val signingCertificateSha1: String?,
)

/** One entry of the bundled list. */
data class MonitoringIndicator(
    val name: String,
    val kind: MonitoringKind,
    val packages: Set<String>,
    /** Lower-case hex, 40 characters. */
    val certificates: Set<String>,
)

/**
 * One installed app the list recognised.
 *
 * [kinds] is a **set** because two packages in the 2026-09-14 snapshot are
 * claimed by both lists - `org.findmykids.app` among them, which is a real
 * family-tracking app. Collapsing that to one label would either accuse an
 * ordinary app of being stalkerware or quietly downgrade something dangerous,
 * and neither is a call this code gets to make silently. The screen says the
 * listing is ambiguous instead.
 */
data class MonitoringFinding(
    val packageName: String,
    /** Every entry that matched, sorted, so the row can name them. */
    val names: List<String>,
    val kinds: Set<MonitoringKind>,
    val signals: Set<MatchSignal>,
) {
    /** True when the lists disagree about what this is. */
    val ambiguous: Boolean get() = kinds.size > 1
}

/**
 * Known monitoring software, matched entirely offline.
 *
 * Echap's indicators, bundled at build time - provenance and counts in
 * `assets/stalkerware-iocs-SOURCE.txt`, design in
 * `context/_shared/capability-research/stalkerware.md`. The constraints on what
 * may be *said* about a finding live in `_shared/threat-model.md` and are not
 * restated here.
 *
 * ## Two signals, and why the second one matters
 *
 * Package name catches the lazy case. Monitoring software renames itself
 * routinely, so the **signing certificate** is what actually survives, and an
 * app matching on certificate alone is the interesting one.
 *
 * ## Matching rules, each one load-bearing
 *
 * - **Package names are compared exactly and case-sensitively.** Android
 *   package names are case-sensitive and upstream depends on it: the snapshot
 *   lists `app.EasyLogger` and `app.Easylogger` as separate entries because
 *   both exist. Lower-casing - the reflex - would merge them and match a
 *   package that is not installed.
 * - **Certificates are compared lower-case.** Upstream writes them upper-case
 *   and Bulwark hashes to lower-case hex, so a case-sensitive comparison here
 *   would match nothing at all while looking entirely correct.
 * - **No substring, prefix or fuzzy matching, ever.** `com.soh` is a real
 *   entry seven characters long; a substring rule would match half a phone.
 * - **One finding per installed package**, carrying every entry that matched -
 *   five packages and one certificate appear in more than one entry.
 */
class MonitoringIndicators(val indicators: List<MonitoringIndicator>) {

    val size: Int get() = indicators.size

    private val byPackage: Map<String, List<MonitoringIndicator>> =
        indicators.flatMap { ind -> ind.packages.map { it to ind } }
            .groupBy({ it.first }, { it.second })

    private val byCertificate: Map<String, List<MonitoringIndicator>> =
        indicators.flatMap { ind -> ind.certificates.map { it to ind } }
            .groupBy({ it.first }, { it.second })

    /**
     * Every installed app the list recognises.
     *
     * Ordered by package name so the screen is stable between reads: a list
     * that reshuffles on every refresh is one a person cannot re-find a row in.
     */
    fun match(installed: List<InstalledApp>): List<MonitoringFinding> =
        installed.mapNotNull { app ->
            val byName = byPackage[app.packageName].orEmpty()
            val certificate = app.signingCertificateSha1?.lowercase()
            val byCert = certificate?.let { byCertificate[it] }.orEmpty()
            if (byName.isEmpty() && byCert.isEmpty()) return@mapNotNull null

            val signals = buildSet {
                if (byName.isNotEmpty()) add(MatchSignal.PACKAGE_NAME)
                if (byCert.isNotEmpty()) add(MatchSignal.SIGNING_CERTIFICATE)
            }
            val matched = (byName + byCert).distinct()
            MonitoringFinding(
                packageName = app.packageName,
                names = matched.map { it.name }.distinct().sorted(),
                kinds = matched.map { it.kind }.toSet(),
                signals = signals,
            )
        }.sortedBy { it.packageName }

    companion object {
        private const val ASSET = "stalkerware-iocs.json"

        /** Snapshot date of the bundled list. Show it; never imply it is current. */
        const val SNAPSHOT = "2026-09-14"

        /**
         * Upstream entries at [SNAPSHOT], and how many carry a signal Bulwark
         * can match on.
         *
         * The gap is the point. Sixteen entries carry neither a package name
         * nor a certificate, so nothing here can recognise them, and a screen
         * that implies otherwise is lying by omission.
         */
        const val UPSTREAM_ENTRIES = 174
        const val MATCHABLE_ENTRIES = 158

        @Volatile
        private var cached: MonitoringIndicators? = null

        /**
         * The bundled list, parsed once per process.
         *
         * **Returns null when it cannot be read, and callers must not treat
         * that as "nothing found".** An empty list and an unreadable list look
         * identical on screen unless the code keeps them apart, and the wrong
         * one of those two is the most dangerous sentence this feature can
         * produce. Nothing here falls back to an empty list.
         *
         * Holds no `Context`. Blocking on first call - keep it off the main
         * thread.
         */
        fun load(context: Context): MonitoringIndicators? =
            cached ?: synchronized(this) {
                cached ?: runCatching { parse(context) }.getOrNull()?.also { cached = it }
            }

        private fun parse(context: Context): MonitoringIndicators {
            val json = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            val root = JSONArray(json)
            val entries = ArrayList<MonitoringIndicator>(root.length())

            for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val kind = when (o.optString("t")) {
                    "stalkerware" -> MonitoringKind.STALKERWARE
                    "watchware" -> MonitoringKind.WATCHWARE
                    // An unknown type is dropped rather than guessed. Guessing
                    // would put an entry under a label somebody reads as an
                    // accusation.
                    else -> continue
                }
                val packages = o.optJSONArray("p").toStringSet()
                val certificates = o.optJSONArray("c").toStringSet { it.lowercase() }
                if (packages.isEmpty() && certificates.isEmpty()) continue

                entries += MonitoringIndicator(
                    name = o.optString("n").ifBlank { "Unnamed entry" },
                    kind = kind,
                    packages = packages,
                    certificates = certificates,
                )
            }
            if (entries.isEmpty()) error("$ASSET parsed to nothing")
            return MonitoringIndicators(entries)
        }

        private fun JSONArray?.toStringSet(transform: (String) -> String = { it }): Set<String> {
            if (this == null) return emptySet()
            val out = LinkedHashSet<String>(length())
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let { out += transform(it) }
            }
            return out
        }
    }
}
