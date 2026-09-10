package com.bulwark.app.debloat

import android.content.Context
import com.bulwark.app.shizuku.IPrivilegedService

/**
 * Answer to spike question C in `context/layers/01-debloat.md`.
 *
 * Bulwark targets SDK 37, so the platform filters which installed packages the
 * app may see. `QUERY_ALL_PACKAGES` would lift that, but it is a
 * policy-sensitive permission and a bad look in a privacy tool.
 *
 * The escape we are testing: package-visibility filtering is applied against
 * the **calling uid**, and Shizuku calls run as uid 2000. If the privileged
 * count comes back higher than the app's own, filtering does not reach our
 * privileged path and Bulwark never has to request that permission.
 *
 * Entirely read-only.
 */
data class VisibilityResult(
    /** What Bulwark sees on its own, under targetSdk 37 filtering. */
    val appVisible: Set<String>,
    /** What a uid-2000 caller sees. */
    val privilegedVisible: Set<String>,
    /** Includes packages uninstalled for this user but still on /system. */
    val privilegedIncludingUninstalled: Set<String>,
) {
    /** Packages the privileged path can see and the app cannot. The answer. */
    val hiddenFromApp: List<String> = (privilegedVisible - appVisible).sorted()

    /** Packages already removed for this user — restorable, and the debloat undo surface. */
    val removedForThisUser: List<String> =
        (privilegedIncludingUninstalled - privilegedVisible).sorted()

    val filteringIsBypassed: Boolean get() = hiddenFromApp.isNotEmpty()

    val verdict: String
        get() = when {
            privilegedVisible.isEmpty() ->
                "Privileged enumeration returned nothing. The probe failed rather than the theory."
            filteringIsBypassed ->
                "Filtering does NOT reach the privileged path. " +
                    "${hiddenFromApp.size} packages are visible to uid 2000 and hidden from the app. " +
                    "Bulwark does not need QUERY_ALL_PACKAGES."
            else ->
                "No difference: both paths see ${appVisible.size}. " +
                    "Either this device does not filter us, or filtering applies to both. " +
                    "Inconclusive — do not conclude we can skip QUERY_ALL_PACKAGES."
        }
}

class PackageVisibilityProbe(private val context: Context) {

    /** @throws Exception if the privileged call fails; the caller reports it rather than hiding it. */
    fun run(service: IPrivilegedService): VisibilityResult {
        val appVisible = context.packageManager
            .getInstalledPackages(0)
            .map { it.packageName }
            .toSet()

        val privileged = service.listPackages(false).toSet()
        val withUninstalled = service.listPackages(true).toSet()

        return VisibilityResult(
            appVisible = appVisible,
            privilegedVisible = privileged,
            privilegedIncludingUninstalled = withUninstalled,
        )
    }
}
