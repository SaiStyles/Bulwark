package com.bulwark.app.policy

import com.bulwark.app.shizuku.PackageState
import com.bulwark.app.shizuku.PrivilegedPackages

/**
 * Asks the phone which packages this user has switched off.
 *
 * ## Why the device is asked at all
 *
 * The action log is app-private. Uninstalling Bulwark or clearing its data
 * destroys it, and on 2026-09-12 that happened on the test device: the log
 * went and the packages did not. Anything derived from the log alone would
 * have reported a phone with nothing changed on it.
 *
 * So the log is history and attribution; **this is the state**. It is also
 * what lets Bulwark notice a switch-off undone in Settings, and a switch-off
 * performed by some other Shizuku tool.
 *
 * ## Why it is not one call
 *
 * `ApplicationInfo.enabled` is a boolean and comes free with the enumeration,
 * but it cannot tell `DISABLED_USER` (3) from `DISABLED` (2). That difference
 * decides whether a row may be offered an undo at all: 3 is what a person
 * chose and Bulwark writes, 2 is what the system or an app set for itself, and
 * "putting back" the latter would be Bulwark making a change while calling it
 * an undo.
 *
 * So the cheap boolean is a **prefilter** and the exact state is paid for only
 * where it came back off - five calls on the Agni 2 rather than three hundred
 * and seventy. Same shape as the permission screen's flag read.
 */
class DeviceDisabledReader(
    private val listing: Listing = PlatformListing,
    private val states: States = PlatformStates,
) {

    /** The enumeration, behind a seam: package name to "is it currently on". */
    fun interface Listing {
        fun installed(userId: Int): List<Pair<String, Boolean>>
    }

    /** The precise per-package state, behind a seam. */
    fun interface States {
        fun get(packageName: String, userId: Int): Int
    }

    /**
     * @return null when the enumeration itself failed - "we could not tell",
     *   which the screen says out loud rather than rendering as "nothing is
     *   switched off". An empty result means the read worked and found none.
     */
    fun read(userId: Int = 0): DeviceDisabled? {
        val all = runCatching { listing.installed(userId) }.getOrNull() ?: return null
        val off = all.filterNot { it.second }.map { it.first }
        var complete = true
        val userDisabled = mutableSetOf<String>()
        off.forEach { name ->
            val state = runCatching { states.get(name, userId) }.getOrNull()
            when {
                // One unreadable package does not sink the read, but it does
                // mean absence stops being evidence - rule 6, and the flag
                // travels with the result rather than being dropped here.
                state == null -> complete = false
                state == PackageState.DISABLED_USER -> userDisabled.add(name)
            }
        }
        return DeviceDisabled(userDisabled, complete)
    }

    private object PlatformListing : Listing {
        override fun installed(userId: Int): List<Pair<String, Boolean>> =
            PrivilegedPackages.listDetailed(userId = userId)
                .map { it.packageName to it.isEnabled }
    }

    private object PlatformStates : States {
        override fun get(packageName: String, userId: Int): Int =
            PackageState.get(packageName, userId)
    }
}
