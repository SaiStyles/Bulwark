package com.bulwark.app.debloat

import com.bulwark.app.shizuku.ProtectedPackages

/**
 * What Bulwark offers for one package, and what it says first.
 *
 * **We give options; we do not enforce.** The only outright refusals are things
 * that break the user's route back - see [com.bulwark.app.shizuku.ProtectedPackages].
 * Everything else is offered with whatever is honestly known about it, and the
 * person who owns the phone decides.
 */
data class Options(
    /** Reversible with `pm enable`, data kept. The default action. */
    val canDisable: Boolean,
    /** The escalation. Verified-safe packages only, per safety rule 3. */
    val canUninstall: Boolean,
    /** Shown before acting. Not a refusal - information. */
    val warning: String?,
    /** Set only when nothing is offered at all. */
    val refusal: String?,
) {
    val isRefused: Boolean get() = refusal != null
}

/** One installed package, with everything needed to act and to explain. */
data class CatalogEntry(
    val packageName: String,
    val isSystem: Boolean,
    val rating: RemovalRating,
    val options: Options,
    val description: String?,
    val neededByInstalled: List<String>,
) {
    val isOffered: Boolean get() = options.canDisable || options.canUninstall
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
 * ## Offer, do not enforce
 *
 * The guards decide what is *offered* and what is *said first* - not what the
 * user is permitted to want. Only things that break the route back are refused
 * outright. Everything else is presented with what is honestly known about it,
 * including "nobody knows", and the person who owns the phone decides.
 *
 * Uninstall is gated harder than disable because the two are not the same bet:
 * disable is reversible with `pm enable` and keeps app data, so it is the
 * default (`safety-rules.md` rule 3).
 *
 * Read-only. Nothing here changes anything; it decides what may be offered.
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
                rating = entry?.rating ?: RemovalRating.UNKNOWN,
                options = optionsFor(name, entry),
                description = entry?.description,
                // Only dependents that are actually present. A warning about an
                // app the user does not have is noise, and noise gets ignored.
                neededByInstalled = entry?.neededBy.orEmpty().filter { it in installedSet },
            )
        }.sortedWith(compareBy({ !it.isOffered }, { it.packageName }))
    }

    private fun optionsFor(name: String, entry: UadEntry?): Options {
        // Hard floor first. Nothing below can raise it.
        ProtectedPackages.reasonFor(name)?.let {
            return Options(canDisable = false, canUninstall = false, warning = null, refusal = it)
        }

        val caution = ProtectedPackages.cautionFor(name)
        val rating = entry?.rating ?: RemovalRating.UNKNOWN

        // Uninstall is the escalation: only where the package is documented and
        // rated safe enough. Everything else that is allowed at all can be
        // disabled, which is reversible with `pm enable` and keeps app data.
        val canUninstall = rating == RemovalRating.RECOMMENDED || rating == RemovalRating.ADVANCED

        val warning = when {
            caution != null -> caution
            rating == RemovalRating.UNKNOWN ->
                "Nobody has documented this package. It may be specific to your " +
                    "phone. Turning it off is reversible, but do it one at a time " +
                    "so you can tell what changed."
            rating == RemovalRating.UNSAFE ->
                entry?.description?.lineSequence()?.firstOrNull()?.trim()
                    ?.let { "Known to cause problems: $it" }
                    ?: "The community database marks this unsafe to remove."
            rating == RemovalRating.EXPERT ->
                "Only turn this off if you know what it does."
            else -> null
        }

        return Options(
            canDisable = true,
            canUninstall = canUninstall,
            warning = warning,
            refusal = null,
        )
    }

    /** Counts for the UI header, so the user can see the shape of their device. */
    fun summarise(entries: List<CatalogEntry>): Summary = Summary(
        total = entries.size,
        offered = entries.count { it.isOffered },
        refused = entries.count { it.options.isRefused },
        uninstallable = entries.count { it.options.canUninstall },
        unknown = entries.count { it.rating == RemovalRating.UNKNOWN && !it.options.isRefused },
        recommended = entries.count { it.rating == RemovalRating.RECOMMENDED },
    )

    data class Summary(
        val total: Int,
        val offered: Int,
        val refused: Int,
        val uninstallable: Int,
        val unknown: Int,
        val recommended: Int,
    )
}
