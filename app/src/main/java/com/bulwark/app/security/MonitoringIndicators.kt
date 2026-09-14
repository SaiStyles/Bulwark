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

/**
 * The card's headline.
 *
 * **A match, never a verdict.** What Bulwark knows is that a package name or a
 * signing key appears in a public list. It does not know who installed it, why,
 * or whether the person holding the phone chose it - and `threat-model.md`
 * forbids writing as though it does.
 */
fun monitoringHeadline(findings: List<MonitoringFinding>): String {
    if (findings.isEmpty()) {
        return "No app on this phone matches a known monitoring tool."
    }
    val apps = if (findings.size == 1) "1 app" else "${findings.size} apps"
    return "$apps on this phone match a known monitoring tool."
}

/** The line under the headline. Null when there is nothing to qualify. */
fun monitoringDetail(findings: List<MonitoringFinding>): String? {
    if (findings.isEmpty()) return null
    val its = if (findings.size == 1) "Its package name or signing key appears" else
        "Their package names or signing keys appear"
    // "whether you chose it" belongs to the ambiguity note, where it is the
    // actual question. Saying it twice on one card made the sentence furniture.
    return "$its in a public list of monitoring software. That is a match, not a " +
        "verdict: it does not say who put it there, or why."
}

/**
 * How one app was recognised, said so the reader learns something from it.
 *
 * The certificate case earns its own sentence because it is the interesting
 * one: it means renaming the app did not hide it.
 */
fun MonitoringFinding.recognisedBy(): String = when {
    signals == setOf(MatchSignal.SIGNING_CERTIFICATE) ->
        "Recognised by its signing key, so renaming it did not hide it."
    signals.contains(MatchSignal.SIGNING_CERTIFICATE) ->
        "Recognised by its package name and its signing key."
    else -> "Recognised by its package name."
}

/**
 * Said only when the lists disagree.
 *
 * Does not resolve the disagreement, because the disagreement is real:
 * `org.findmykids.app` is a genuine family-tracking product that also appears
 * as monitoring software, and both of those are true at once. The question a
 * person can answer and Bulwark cannot is whether they chose it.
 */
fun MonitoringFinding.ambiguityNote(): String? {
    if (!ambiguous) return null
    return "Two lists disagree about this one: it appears both as monitoring " +
        "software and as a family-tracking app. Both can be true of the same " +
        "product - the question is whether you chose it."
}

/**
 * The quiet line on Audit when nothing matched.
 *
 * **Redirects rather than reassures.** The failure this exists to prevent is a
 * person reading "nothing found" as "I am safe". A list only finds what is on
 * it, and monitoring software renames itself for a living - so the sentence
 * points at the behavioural evidence on the rest of the screen, which is what
 * actually catches something nobody has catalogued.
 *
 * **Counts only what can be matched.** The snapshot carries 174 entries and 16
 * of them publish no package name and no certificate; claiming those would be
 * the overclaim. Saying 158 tells the truth without a footnote nobody reads.
 */
fun monitoringFooter(): String =
    "Checked against ${MonitoringIndicators.MATCHABLE_ENTRIES} known monitoring " +
        "tools, by package name and by signing key. Last updated " +
        "${MonitoringIndicators.SNAPSHOT}. It cannot find one that has been " +
        "renamed and re-signed, or one nobody has catalogued yet - what finds " +
        "those is the rest of this screen."

/** Said when the list itself could not be read. Never "nothing found". */
const val MONITORING_UNREADABLE: String =
    "Bulwark could not read its list of known monitoring tools, so it has not " +
        "checked. This is not the same as finding nothing."

/**
 * Shown before any action is offered, and this is the whole of why the feature
 * is shaped the way it is.
 *
 * Covers both ways monitoring software arrives without assuming either, because
 * a match cannot tell them apart. Cites the Coalition rather than paraphrasing
 * advice this project is not qualified to give, and the address is **text, not
 * a link**: a browser leaves history on a phone somebody else may reach.
 */
const val MONITORING_SAFETY_NOTE: String =
    "Removing it can tell whoever installed it that you know. If that is " +
        "someone with access to you, that moment is the dangerous one - " +
        "escalation after discovery is a documented pattern. If it arrived some " +
        "other way, the risk is lower, but the caution costs you nothing.\n\n" +
        "There are people who plan for this with you: the Coalition Against " +
        "Stalkerware, stopstalkerware.org"
