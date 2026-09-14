package com.bulwark.app.permissions

/**
 * The accesses that matter more than the ones on the permissions screen.
 *
 * Android surfaces camera, microphone and location prominently. These sit
 * several taps deep under Settings -> Apps -> Special app access, and most
 * people have never opened it - yet each one is strictly more powerful than
 * the mic.
 *
 * `context/layers/02-permissions.md` carries the research. This file is the
 * *interpretation*: what each access permits in plain language, and which
 * combinations are worse than their parts. It is pure Kotlin on purpose, so
 * every judgement below is unit-tested without a device.
 */
enum class Access {
    /**
     * Full UI read **and control**. The most abused vector in Android malware,
     * and the one that could drive Bulwark itself
     * (`context/_shared/security.md` FIXED-11).
     */
    ACCESSIBILITY,

    /**
     * Reads every notification. MITRE ATT&CK documents live families using it
     * to steal one-time passwords before the user sees them.
     */
    NOTIFICATION_LISTENER,

    /** Can lock and wipe. Stalkerware uses it to resist uninstall. */
    DEVICE_ADMIN,

    /** Overlay attacks, tapjacking, fake login screens drawn over real ones. */
    DRAW_OVER_APPS,

    /** Which apps you open, and when. A behavioural profile. */
    USAGE_ACCESS,

    /** Whole-storage read on Android 11+, bypassing scoped storage. */
    ALL_FILES,

    /** The sideload-more-malware pivot. */
    INSTALL_UNKNOWN_APPS,
    ;

    /**
     * The app op behind this access, or null when it is not one.
     *
     * Four of the seven live in AppOps; accessibility, notification listening
     * and device admin are enrolments elsewhere and have no op. Null is the
     * honest answer for those, and it is what stops the UI offering to revoke
     * something Bulwark has no mechanism for.
     *
     * **The one home for this mapping.** `shizuku/AppOpsAccess` reads these
     * ops and `policy/SpecialAccessActions` writes them; both take the name
     * from here so the two cannot drift.
     *
     * Literal strings rather than `AppOpsManager.OPSTR_*` for two reasons: two
     * of the four have no public constant at all, and the *string* is the
     * stable part of the API - it is the int codes that get reordered between
     * releases, which is why every caller resolves those at runtime.
     */
    val opName: String?
        get() = when (this) {
            DRAW_OVER_APPS -> "android:system_alert_window"
            USAGE_ACCESS -> "android:get_usage_stats"
            ALL_FILES -> "android:manage_external_storage"
            INSTALL_UNKNOWN_APPS -> "android:request_install_packages"
            ACCESSIBILITY, NOTIFICATION_LISTENER, DEVICE_ADMIN -> null
        }

    /**
     * What this lets an app do, said to a person rather than to a developer.
     *
     * Present tense and concrete. "Can read your screen" is a fact someone can
     * act on; "holds BIND_ACCESSIBILITY_SERVICE" is not.
     */
    val plainMeaning: String
        get() = when (this) {
            ACCESSIBILITY ->
                "Can read everything on your screen and tap things for you."
            NOTIFICATION_LISTENER ->
                "Can read every notification, including login codes, before you see them."
            DEVICE_ADMIN ->
                "Can lock or wipe this phone, and can stop itself being uninstalled."
            DRAW_OVER_APPS ->
                "Can draw on top of other apps, including over what you are tapping."
            USAGE_ACCESS ->
                "Can see which apps you open and when."
            ALL_FILES ->
                "Can read every file on your storage, not just its own."
            INSTALL_UNKNOWN_APPS ->
                "Can install other apps."
        }

    /** Short label for a badge. */
    val shortLabel: String
        get() = when (this) {
            ACCESSIBILITY -> "Screen control"
            NOTIFICATION_LISTENER -> "Reads notifications"
            DEVICE_ADMIN -> "Device admin"
            DRAW_OVER_APPS -> "Draws over apps"
            USAGE_ACCESS -> "App usage"
            ALL_FILES -> "All files"
            INSTALL_UNKNOWN_APPS -> "Installs apps"
        }
}

/** One app and everything it currently holds. */
data class AppAccess(
    val packageName: String,
    val accesses: Set<Access>,
    /**
     * Whether it came with the phone. **Null means we could not tell.**
     *
     * Three-valued on purpose. The first hardware run of this audit reported
     * "2 apps — 2 you installed yourself" about the launcher and Android Auto,
     * because without Shizuku the system-package list came back empty and
     * absent was read as false. That is the most alarming possible reading of
     * the data, stated falsely, and `safety-rules.md` forbids exactly that.
     *
     * Unknown is now a value rather than a default, so it can be counted
     * separately and said out loud instead of collapsing into an accusation.
     */
    val isSystem: Boolean?,
)

/**
 * What the screen says about where an app came from.
 *
 * A pure function rather than a `when` inside the composable, because this is
 * the sentence that went wrong first: without Shizuku the system list came back
 * empty, absent read as false, and the row told the user they had installed the
 * launcher themselves.
 *
 * Copy decisions belong where they can be tested. The composable renders the
 * string; it does not choose it.
 */
val AppAccess.originLabel: String
    get() = when (isSystem) {
        true -> "Came with the phone."
        false -> "You installed this."
        // Said out loud rather than guessed in either direction.
        null -> "Bulwark cannot tell whether this came with the phone."
    }

/**
 * The audit header line.
 *
 * Only claims a user-installed count when one is actually known - `summarise`
 * counts `isSystem == false`, never unknown, and this refuses to print a zero
 * as though it were a finding.
 */
fun AuditSummary.headline(): String = buildString {
    append("$appsWithAnyAccess apps hold at least one of these")
    if (userInstalledWithAccess > 0) {
        append(" — $userInstalledWithAccess you installed yourself")
    }
    append(".")
}

/** The line shown when a source could not be read. Null when all of them were. */
fun unavailableLine(unavailable: List<String>): String? =
    if (unavailable.isEmpty()) null
    else "Bulwark could not check: " + unavailable.joinToString("; ") + "."

/**
 * A combination that is worse than the sum of its parts.
 *
 * **Bulwark does not accuse.** It cannot know whether an app has a good reason
 * for what it holds, and guessing would produce exactly the false confidence
 * `safety-rules.md` forbids. What it *can* say without guessing is that two
 * specific accesses together enable an attack neither enables alone - that is
 * a fact about Android, not a judgement about the app.
 */
data class Combination(
    val accesses: Set<Access>,
    val why: String,
)

/**
 * Combinations worth naming, and only ones that are genuinely worse together.
 *
 * Kept short deliberately. A list that flags everything trains people to
 * dismiss it, and `PackageCatalog` already learned that lesson about dependency
 * warnings: noise gets ignored, and then the warning that mattered is ignored
 * too.
 */
private val COMBINATIONS = listOf(
    Combination(
        setOf(Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
        "It can both control your screen and draw over it - so it can act on " +
            "your phone while showing you something else. This pairing is how " +
            "real malware hides what it is doing.",
    ),
    Combination(
        setOf(Access.ACCESSIBILITY, Access.INSTALL_UNKNOWN_APPS),
        "It can tap through an installer by itself. That is enough to install " +
            "other apps without you touching anything.",
    ),
    Combination(
        setOf(Access.NOTIFICATION_LISTENER, Access.ACCESSIBILITY),
        "It can read your login codes and use them, without you seeing either.",
    ),
    Combination(
        setOf(Access.DEVICE_ADMIN, Access.ACCESSIBILITY),
        "It can watch everything you do and resist being uninstalled. That is " +
            "the shape of monitoring software.",
    ),
)

/** Every named combination this app satisfies. Empty for almost every app. */
fun AppAccess.combinations(): List<Combination> =
    COMBINATIONS.filter { accesses.containsAll(it.accesses) }

/**
 * How much of a person's attention this app deserves, and nothing more.
 *
 * Deliberately not "risk". Bulwark cannot rank risk without knowing what the
 * app is for, and a red badge on a legitimate accessibility tool would be a
 * false accusation of exactly the app a disabled user depends on.
 */
enum class Attention {
    /** Holds nothing from this list. Most apps. */
    NONE,

    /** Holds something worth knowing about. */
    WORTH_KNOWING,

    /** Holds a named combination. Not an accusation - a fact about Android. */
    LOOK_AT_THIS,
}

val AppAccess.attention: Attention
    get() = when {
        combinations().isNotEmpty() -> Attention.LOOK_AT_THIS
        accesses.isNotEmpty() -> Attention.WORTH_KNOWING
        else -> Attention.NONE
    }

/**
 * The audit, sorted so the things worth looking at come first.
 *
 * Apps holding nothing are dropped entirely rather than listed as safe. A list
 * of 370 rows where 360 say "nothing" buries the ten that matter, and the
 * user's own phone already tells them what is installed.
 */
fun List<AppAccess>.audit(): List<AppAccess> =
    filter { it.accesses.isNotEmpty() }
        .sortedWith(
            compareByDescending<AppAccess> { it.attention.ordinal }
                // User-installed first, then unknown, then system: a
                // preinstalled launcher holding notification access is how
                // launchers work; a downloaded app doing it is a choice
                // somebody made. Unknown sits between - not accused, not
                // excused.
                .thenBy { it.isSystem?.let { sys -> if (sys) 2 else 0 } ?: 1 }
                .thenByDescending { it.accesses.size }
                .thenBy { it.packageName },
        )

/** Counts for the header, so the shape is visible before the detail. */
data class AuditSummary(
    val appsWithAnyAccess: Int,
    val appsToLookAt: Int,
    /** Known to be user-installed. Never includes the ones we could not tell. */
    val userInstalledWithAccess: Int,
)

fun List<AppAccess>.summarise(): AuditSummary {
    val withAccess = filter { it.accesses.isNotEmpty() }
    return AuditSummary(
        appsWithAnyAccess = withAccess.size,
        appsToLookAt = withAccess.count { it.attention == Attention.LOOK_AT_THIS },
        // `== false` and not `!isSystem`: unknown must not be counted as
        // user-installed. That conflation is the bug this file's isSystem
        // doc records.
        userInstalledWithAccess = withAccess.count { it.isSystem == false },
    )
}
