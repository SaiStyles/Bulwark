package com.bulwark.app.debloat

import com.bulwark.app.shizuku.ProtectedPackages

/**
 * Whether Bulwark will offer to remove a package, and why not when it will not.
 *
 * A user told "no" without a reason goes looking for a tool that just says yes,
 * so every refusal carries an explanation.
 */
sealed interface Verdict {

    /** Both guards allow it. [rating] decides how loudly the UI warns. */
    data class Offered(val rating: RemovalRating) : Verdict

    /** Our own never-remove list refuses. Structural: telephony, system UI, … */
    data class Protected(val reason: String) : Verdict

    /** The community database marks it Unsafe — bootloops, dead modules. */
    data class TooRisky(val reason: String) : Verdict

    /**
     * Nothing known about it. **Not offered**, and deliberately not treated as
     * safe: silence is not evidence.
     */
    data object Unknown : Verdict
}

/** One installed package, with everything needed to decide and to explain. */
data class CatalogEntry(
    val packageName: String,
    val isSystem: Boolean,
    val verdict: Verdict,
    val description: String?,
    val neededByInstalled: List<String>,
) {
    val isOffered: Boolean get() = verdict is Verdict.Offered
    val rating: RemovalRating
        get() = (verdict as? Verdict.Offered)?.rating ?: RemovalRating.UNKNOWN
}

/**
 * Merges what is installed with what is known, and applies both guards.
 *
 * ## Two guards, and neither is sufficient
 *
 * Measured on the test device 2026-09-10, not assumed:
 *
 * - **`ProtectedPackages`** knows structural categories and catches
 *   OEM-renamed telephony like `com.mediatek.ims`, which per-device community
 *   coverage cannot be relied on for.
 * - **`UadDatabase`** knows 5,372 specific packages — and flagged 23 as Unsafe
 *   that our list allowed, including one whose description is "extremely large
 *   chance of bootlooping". But it covers only 217 of this device's 274 system
 *   packages, because Lava is a small OEM.
 *
 * **A package is offered only if both allow it.** Full reasoning in
 * `context/layers/01-debloat.md`.
 *
 * Read-only. Nothing here removes anything; it decides what may be *offered*.
 */
class PackageCatalog(private val database: UadDatabase) {

    /**
     * @param installed every package visible at uid 2000 — all 370 on the test
     *   device, not the 176 the app can see unaided.
     * @param systemPackages which of those are system packages.
     */
    fun build(
        installed: Collection<String>,
        systemPackages: Set<String>,
    ): List<CatalogEntry> {
        val installedSet = installed.toSet()
        return installed.map { name ->
            val entry = database[name]
            CatalogEntry(
                packageName = name,
                isSystem = name in systemPackages,
                verdict = verdictFor(name, entry),
                description = entry?.description,
                // Only dependents that are actually present. A warning about an
                // app the user does not have is noise, and noise gets ignored.
                neededByInstalled = entry?.neededBy.orEmpty().filter { it in installedSet },
            )
        }.sortedWith(compareBy({ !it.isOffered }, { it.packageName }))
    }

    private fun verdictFor(name: String, entry: UadEntry?): Verdict {
        // Guard one first. It is ours, it is structural, and it is the floor
        // no database entry can raise.
        ProtectedPackages.reasonFor(name)?.let { return Verdict.Protected(it) }

        // Guard two.
        if (entry == null) return Verdict.Unknown

        return when (entry.rating) {
            RemovalRating.UNSAFE -> Verdict.TooRisky(
                entry.description?.lineSequence()?.firstOrNull()?.trim()
                    ?: "Marked unsafe to remove by the community database."
            )
            RemovalRating.UNKNOWN -> Verdict.Unknown
            else -> Verdict.Offered(entry.rating)
        }
    }

    /** Counts for the UI header, so the user can see the shape of their device. */
    fun summarise(entries: List<CatalogEntry>): Summary = Summary(
        total = entries.size,
        offered = entries.count { it.isOffered },
        protected = entries.count { it.verdict is Verdict.Protected },
        tooRisky = entries.count { it.verdict is Verdict.TooRisky },
        unknown = entries.count { it.verdict is Verdict.Unknown },
        recommended = entries.count { it.rating == RemovalRating.RECOMMENDED },
    )

    data class Summary(
        val total: Int,
        val offered: Int,
        val protected: Int,
        val tooRisky: Int,
        val unknown: Int,
        val recommended: Int,
    )
}
