package com.bulwark.app.permissions

/**
 * Runtime permissions: what an app holds, and whether taking one away is a
 * real offer or a button that lies.
 *
 * The reading and the revoking are privileged and live in `shizuku/`. This
 * file is the *interpretation* - what each permission permits in plain
 * language, which ones Bulwark may offer to remove, and what to say when it
 * may not. Pure Kotlin on purpose: every judgement here is unit-tested without
 * a device, and `conventions.md` keeps copy out of composables for the same
 * reason.
 *
 * ## The finding this file exists to respect
 *
 * On 2026-09-11 a `pm revoke` against a `SYSTEM_FIXED` permission returned no
 * error and changed nothing. An app that offered that revoke would tell
 * someone their camera access was gone while the camera stayed open, and write
 * it into the log as a success. So offerability is decided *before* the button
 * is drawn, from flags the platform gives us, and [Revocable] carries the
 * reason rather than a bare boolean.
 */

/**
 * One runtime permission as it stands for one app, on one Android user.
 *
 * Everything here comes from the platform. Nothing is inferred: a permission
 * Bulwark could not read is absent from the list rather than present with a
 * guessed value, because "not held" and "not checked" must never look the same
 * on screen.
 */
data class PermissionHolding(
    val packageName: String,
    /** The full name, e.g. `android.permission.CAMERA`. */
    val permission: String,
    val isGranted: Boolean,
    /**
     * Whether this is a *runtime* permission - one the user is meant to be
     * able to answer for. Install-time, normal and signature permissions are
     * fixed at install and cannot be revoked at all.
     *
     * **Null means Bulwark could not tell**, which happens for permissions an
     * app defines itself. Three-valued for the same reason `AppAccess.isSystem`
     * is: the first hardware run of the special-access audit read an absent
     * answer as `false` and told the user they had installed the launcher
     * themselves. Unknown is a value here, not a default.
     */
    val isRuntime: Boolean?,
    /** `getPermissionFlags`, interpreted by [PermissionFlags]. */
    val flags: Int,
    /**
     * Whether this app is on the never-remove list.
     *
     * Filled in by the privileged reader, which is where that list lives. The
     * policy layer refuses these anyway - `CommandSafety` runs on every step -
     * so this does not add a protection. It stops the screen **offering** one
     * it knows will be refused, which is a different failure: a control that
     * can only fail is a promise the app cannot keep, and a batch stops at the
     * first failure, so one such tick halts everything after it.
     */
    val isProtected: Boolean = false,
)

/**
 * The permission flags that decide whether a revoke would do anything.
 *
 * ## Why these are written as numbers
 *
 * `conventions.md` says never to inline a constant the platform defines - and
 * the platform does define these, as `PackageManager.FLAG_PERMISSION_*`. They
 * are `@SystemApi @hide`, so an ordinary app cannot reference them: the choice
 * is a named constant here or a reflective field read that the non-SDK
 * blocklist may refuse, in the middle of deciding whether it is safe to offer
 * someone a destructive button.
 *
 * The distinction that makes this safe, and that makes hardcoding app-op
 * *codes* unsafe: op codes are indexes into a list the platform reorders
 * between releases, while these are bit positions in a mask that is **written
 * into every device's permission state on disk**. Renumbering them would
 * change the meaning of stored flags on every phone in the world, so they do
 * not get renumbered. Unchanged since API 23.
 *
 * Still worth confirming on hardware rather than trusting: the check is that
 * `com.android.egg`'s `POST_NOTIFICATIONS` - which survived a revoke on
 * 2026-09-11 - reads as [isFixed] here. A value that has drifted shows up as a
 * permission Bulwark offers and the platform ignores, which is the exact
 * failure this file is meant to prevent.
 */
object PermissionFlags {

    /** Set by a device-policy controller (work profile, MDM). */
    const val POLICY_FIXED = 1 shl 2

    /** Fixed by the platform itself. The revoke is accepted and ignored. */
    const val SYSTEM_FIXED = 1 shl 4

    fun isSystemFixed(flags: Int): Boolean = (flags and SYSTEM_FIXED) != 0

    fun isPolicyFixed(flags: Int): Boolean = (flags and POLICY_FIXED) != 0

    /** True when the platform will not let this permission be changed by us. */
    fun isFixed(flags: Int): Boolean = isSystemFixed(flags) || isPolicyFixed(flags)
}

/**
 * Whether Bulwark may offer to take a permission away, and if not, why not.
 *
 * An enum rather than a boolean because every "no" here is something the user
 * should be *told*. "Your work profile controls this" is information; a
 * greyed-out row with no explanation is Bulwark deciding for someone and not
 * saying so, which is the habit this project exists to fight.
 */
enum class Revocable {
    /** Granted, revocable, and ours to offer. */
    YES,

    /** Not currently held, so there is nothing to take away. */
    NOT_GRANTED,

    /**
     * Not a runtime permission. Granted at install and revocable by nobody -
     * not by Bulwark, not by Settings, not by the user.
     */
    NOT_RUNTIME,

    /** `SYSTEM_FIXED`. The platform accepts a revoke and ignores it. */
    FIXED_BY_SYSTEM,

    /** `POLICY_FIXED`. A device policy controller owns this one. */
    FIXED_BY_POLICY,

    /**
     * Bulwark could not tell what kind of permission this is.
     *
     * Fails closed - no offer - because the alternative is offering a control
     * that may do nothing, and saying so out loud because a greyed row with no
     * reason is the app deciding for someone silently.
     */
    UNKNOWN_KIND,

    /**
     * The app is on the never-remove list in `_shared/safety-rules.md`.
     *
     * The one refusal here that is Bulwark's own decision rather than the
     * platform's, so it is the one that has to explain itself best.
     */
    PROTECTED,
    ;

    /** True only for the one value that means "we can act". */
    val isOffered: Boolean get() = this == YES

    /**
     * Why this cannot be switched off, said to a person. Null when it can.
     *
     * Describes the platform, never the app. "This app insists on it" would be
     * an accusation; the truth is that the phone's software decides, and
     * saying so tells the user who to be annoyed with.
     */
    val plainReason: String?
        get() = when (this) {
            YES -> null
            NOT_GRANTED -> "Not currently allowed."
            NOT_RUNTIME ->
                "Granted when the app was installed. Android does not let " +
                    "anything switch this kind off, including Settings."
            FIXED_BY_SYSTEM ->
                "Fixed by the phone's software. Bulwark can ask, and Android " +
                    "will quietly ignore it - so it does not ask."
            FIXED_BY_POLICY ->
                "Controlled by a device policy - a work profile or a management " +
                    "app. Changing it there is the only way that sticks."
            UNKNOWN_KIND ->
                "Bulwark could not work out what this permission is, so it will " +
                    "not offer to change it."
            PROTECTED ->
                "Bulwark never changes this app. It is part of calling, or part " +
                    "of how you would undo a change, and breaking it could leave " +
                    "you with no way back."
        }
}

/**
 * Whether this holding can be revoked, and why not when it cannot.
 *
 * Order matters. Not-granted is checked first because "there is nothing to
 * take away" is a truer thing to say than "the system fixed it", even when
 * both are true of the same row.
 */
fun PermissionHolding.revocable(): Revocable = when {
    !isGranted -> Revocable.NOT_GRANTED
    // Before the platform's own refusals: "we will not touch this app" is the
    // more useful thing to tell someone, and it is true regardless of flags.
    isProtected -> Revocable.PROTECTED
    isRuntime == null -> Revocable.UNKNOWN_KIND
    !isRuntime -> Revocable.NOT_RUNTIME
    PermissionFlags.isSystemFixed(flags) -> Revocable.FIXED_BY_SYSTEM
    PermissionFlags.isPolicyFixed(flags) -> Revocable.FIXED_BY_POLICY
    else -> Revocable.YES
}

/** A permission said in words rather than in constant case. */
data class PlainWords(
    /** A short label for a row. */
    val name: String,
    /**
     * What holding it lets the app do, present tense and concrete. Null when
     * Bulwark has no description for this permission - see [wordsFor].
     */
    val meaning: String?,
)

/**
 * The permissions Bulwark can describe, and what each one actually permits.
 *
 * A fixed vocabulary, so it stays inline per `conventions.md`: these are
 * Android's dangerous permissions, a list that changes once a release, and
 * there is nothing to discover them from at runtime.
 *
 * Every line describes a **capability**, not a motive. "Can read your text
 * messages" is a fact; "spies on your texts" is an accusation Bulwark is not
 * entitled to make about an app it knows nothing about, and the same rule that
 * governs the special-access audit governs this.
 */
private val WORDS: Map<String, PlainWords> = mapOf(
    "android.permission.CAMERA" to
        PlainWords("Camera", "Can take photos and video at any time while it is running."),
    "android.permission.RECORD_AUDIO" to
        PlainWords("Microphone", "Can record audio at any time while it is running."),
    "android.permission.ACCESS_FINE_LOCATION" to
        PlainWords("Precise location", "Can see where you are, to within a few metres."),
    "android.permission.ACCESS_COARSE_LOCATION" to
        PlainWords("Approximate location", "Can see roughly where you are."),
    "android.permission.ACCESS_BACKGROUND_LOCATION" to
        PlainWords("Location in the background", "Can see where you are when you are not using it."),
    "android.permission.READ_CONTACTS" to
        PlainWords("Read contacts", "Can read everyone in your address book."),
    "android.permission.WRITE_CONTACTS" to
        PlainWords("Change contacts", "Can add, change and delete your contacts."),
    "android.permission.GET_ACCOUNTS" to
        PlainWords("Accounts on this phone", "Can see which accounts you are signed in to."),
    "android.permission.READ_CALENDAR" to
        PlainWords("Read calendar", "Can read your appointments."),
    "android.permission.WRITE_CALENDAR" to
        PlainWords("Change calendar", "Can add and delete appointments."),
    "android.permission.READ_SMS" to
        PlainWords("Read texts", "Can read your text messages, including login codes."),
    "android.permission.SEND_SMS" to
        PlainWords("Send texts", "Can send text messages, which can cost money."),
    "android.permission.RECEIVE_SMS" to
        PlainWords("Receive texts", "Can read texts as they arrive, before you see them."),
    "android.permission.RECEIVE_MMS" to
        PlainWords("Receive picture messages", "Can read picture messages as they arrive."),
    "android.permission.RECEIVE_WAP_PUSH" to
        PlainWords("Receive service messages", "Can read carrier service messages as they arrive."),
    "android.permission.READ_CALL_LOG" to
        PlainWords("Read call history", "Can see who you have called and who called you."),
    "android.permission.WRITE_CALL_LOG" to
        PlainWords("Change call history", "Can add to and delete your call history."),
    "android.permission.READ_PHONE_STATE" to
        PlainWords("Phone status", "Can see your phone number, carrier, and whether a call is in progress."),
    "android.permission.READ_PHONE_NUMBERS" to
        PlainWords("Phone number", "Can read this phone's number."),
    "android.permission.CALL_PHONE" to
        PlainWords("Make calls", "Can dial a number without asking you first."),
    "android.permission.ANSWER_PHONE_CALLS" to
        PlainWords("Answer calls", "Can pick up incoming calls."),
    "android.permission.ADD_VOICEMAIL" to
        PlainWords("Voicemail", "Can add messages to your voicemail."),
    "android.permission.READ_EXTERNAL_STORAGE" to
        PlainWords("Read storage", "Can read files you have saved."),
    "android.permission.WRITE_EXTERNAL_STORAGE" to
        PlainWords("Change storage", "Can change and delete files you have saved."),
    "android.permission.READ_MEDIA_IMAGES" to
        PlainWords("Photos", "Can read your photos."),
    "android.permission.READ_MEDIA_VIDEO" to
        PlainWords("Videos", "Can read your videos."),
    "android.permission.READ_MEDIA_AUDIO" to
        PlainWords("Music and audio", "Can read your music and audio files."),
    "android.permission.ACCESS_MEDIA_LOCATION" to
        PlainWords("Where photos were taken", "Can read the location stored inside your photos."),
    "android.permission.BODY_SENSORS" to
        PlainWords("Body sensors", "Can read heart rate and similar sensors."),
    "android.permission.ACTIVITY_RECOGNITION" to
        PlainWords("Physical activity", "Can tell when you are walking, running or in a vehicle."),
    "android.permission.BLUETOOTH_CONNECT" to
        PlainWords("Bluetooth devices", "Can connect to Bluetooth devices you have paired."),
    "android.permission.BLUETOOTH_SCAN" to
        PlainWords("Find Bluetooth devices", "Can scan for nearby Bluetooth devices, which reveals where you are."),
    "android.permission.BLUETOOTH_ADVERTISE" to
        PlainWords("Bluetooth broadcasting", "Can make this phone visible to nearby devices."),
    "android.permission.NEARBY_WIFI_DEVICES" to
        PlainWords("Nearby Wi-Fi devices", "Can see nearby Wi-Fi devices, which reveals where you are."),
    "android.permission.POST_NOTIFICATIONS" to
        PlainWords("Send notifications", "Can show you notifications."),
)

/**
 * [PlainWords] for any permission, described where we can and named honestly
 * where we cannot.
 *
 * The fallback prettifies the last segment and leaves [PlainWords.meaning]
 * null rather than inventing one. A privacy tool that paraphrases a permission
 * it does not know is guessing at someone's phone in a voice that sounds
 * certain - and `safety-rules.md` treats a confident wrong description as
 * worse than an absent one. The UI says so out loud and shows the raw name.
 */
fun wordsFor(permission: String): PlainWords =
    WORDS[permission] ?: PlainWords(name = prettify(permission), meaning = null)

private fun prettify(permission: String): String {
    val tail = permission.substringAfterLast('.')
    if (tail.isEmpty()) return permission
    return tail.split('_')
        .joinToString(" ") { it.lowercase() }
        .replaceFirstChar { it.uppercase() }
}

/**
 * Ordering for a list of permissions: the ones that read the world first.
 *
 * **This is a sort order, not a score.** It says nothing about whether an app
 * should hold something - a camera app holding the camera is at the top of the
 * list and is entirely as it should be. It exists because a list of forty rows
 * in alphabetical order buries the microphone under "Activity recognition",
 * and the first screenful is the only one most people read.
 *
 * Lower sorts first. Unknown permissions land after the described ones, since
 * a row Bulwark cannot explain is not a row to lead with.
 */
fun attentionRank(permission: String): Int = when (permission) {
    "android.permission.RECORD_AUDIO" -> 0
    "android.permission.CAMERA" -> 1
    "android.permission.ACCESS_BACKGROUND_LOCATION" -> 2
    "android.permission.ACCESS_FINE_LOCATION" -> 3
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS" -> 4
    "android.permission.READ_CALL_LOG" -> 5
    "android.permission.READ_CONTACTS" -> 6
    "android.permission.ACCESS_COARSE_LOCATION" -> 7
    "android.permission.SEND_SMS",
    "android.permission.CALL_PHONE" -> 8
    else -> if (permission in WORDS) 50 else 100
}

/** One permission, and every app on the phone currently holding it. */
data class PermissionAcrossApps(
    val permission: String,
    /** Apps that hold it, in name order. */
    val holders: List<PermissionHolding>,
) {
    val words: PlainWords get() = wordsFor(permission)

    /** The holders Bulwark could actually take it away from. */
    val revocable: List<PermissionHolding> get() = holders.filter { it.revocable().isOffered }
}

/**
 * Regroups per-app holdings into the one-permission-many-apps view.
 *
 * This is the shape the layer card argues for: "who can hear me" is a question
 * about a permission, and answering it app by app is the thing Android already
 * does badly. Only granted holdings appear - a list of apps that *could* ask
 * for the microphone answers a different and much less useful question.
 *
 * ## Runtime permissions only, and that is a judgement
 *
 * Seen on the Agni 2, 2026-09-11: without this filter the screen fills with
 * install-time permissions. `Access adservices attribution` across 47 apps,
 * `C2d message`, `Dynamic receiver not exported permission` - none of which any
 * person can act on, and none of which mean anything to one. They pushed
 * Microphone and Camera off the first screenful, which is the only one most
 * people read.
 *
 * So the cross-app view shows what Android treats as **the user's to decide**.
 * The screen says so rather than implying it is everything, because "every
 * permission" and "every permission you can answer for" are different claims
 * and only one of them is true here.
 *
 * Permissions Bulwark could not classify are left out too - `isRuntime` is
 * three-valued and only a definite yes appears. They are not offerable in any
 * case, and a row nobody can act on labelled with an apology is still noise.
 *
 * Pure, so the grouping the screen depends on is tested without a device.
 */
fun groupByPermission(holdings: List<PermissionHolding>): List<PermissionAcrossApps> =
    holdings.filter { it.isGranted && it.isRuntime == true }
        .groupBy { it.permission }
        .map { (permission, held) ->
            PermissionAcrossApps(permission, held.sortedBy { it.packageName })
        }
        .sortedWith(compareBy({ attentionRank(it.permission) }, { it.permission }))

/**
 * What the **system's own** authentication prompt says about a batch revoke.
 *
 * This is the most security-sensitive sentence in the app, and the reason it
 * is a tested pure function rather than a string built in a composable.
 *
 * Authentication runs in the system process precisely because a hostile
 * accessibility service can read Bulwark's screen and synthesise taps on it
 * (`_shared/security.md` FIXED-11). Everything on our own screen - the list,
 * the ticks, the count next to the button - is therefore attacker-writable.
 * The prompt is not. So whatever a person needs in order to *mean* their
 * fingerprint has to be in here.
 *
 * Two things go in, per the rule 1 amendment of 2026-09-11:
 *
 * - **The capability**, in the same plain words as the screen. "Take
 *   Microphone away" is a thing someone can refuse; "apply 12 changes" is not.
 * - **The count**, because it is the only cross-check that exists. If someone
 *   chose four apps and the system asks about thirty, that mismatch is their
 *   single warning that something else did the choosing.
 *
 * Small batches name every app, because they fit and naming them is strictly
 * better. Past [NAMED_APPS_LIMIT] the list would be truncated by the system
 * dialog, and a **silently truncated list is worse than an honest count** - it
 * reads as complete.
 */
fun batchRevokePrompt(permission: String, packageNames: List<String>): String {
    val capability = wordsFor(permission).name
    val count = packageNames.size
    val apps = if (count == 1) "1 app" else "$count apps"

    return if (count <= NAMED_APPS_LIMIT) {
        "Take $capability away from $apps: ${packageNames.joinToString(", ")}."
    } else {
        "Take $capability away from $apps. If you did not choose $count, cancel."
    }
}

/**
 * How many apps the prompt names before falling back to a count.
 *
 * Five fits the system dialog without truncation on a small screen. The number
 * is a display limit and nothing else - it is **not** a cap on batch size, and
 * must never become one by accident: a limit on what can be described is not a
 * limit on what may be done, and conflating them would silently cap the
 * feature at five.
 */
const val NAMED_APPS_LIMIT = 5

/** Short label for the button that opens the prompt. Same words, same order. */
fun batchRevokeButton(permission: String, count: Int): String =
    "Take ${wordsFor(permission).name.lowercase()} away from " +
        if (count == 1) "1 app" else "$count apps"

/**
 * Where an app came from, for a row in the cross-app view.
 *
 * The twin of [originLabel] for holdings, and three-valued for the same
 * reason: on the special-access audit's first hardware run an empty system
 * list read as `false`, and the screen told the user they had installed their
 * own launcher. Absent must not collapse into an accusation.
 *
 * @param systemPackages null when Bulwark could not establish which packages
 *   shipped with the phone - not an empty set, which would mean none did.
 */
fun originLabelFor(packageName: String, systemPackages: Set<String>?): String = when {
    systemPackages == null -> "Bulwark cannot tell whether this came with the phone."
    packageName in systemPackages -> "Came with the phone."
    else -> "You installed this."
}

/**
 * The line under a capability's name: how many apps, and how many of those
 * came with the phone.
 *
 * The second half matters more than it looks. The microphone list on a stock
 * phone opens with a wall of system components, and a bare "14 apps" reads as
 * alarming when most of it is the dialer and the assistant. Saying how much of
 * the number is factory software is the difference between informing someone
 * and startling them.
 *
 * When Bulwark cannot tell, it says only the count. A split it cannot stand
 * behind is worse than no split.
 */
fun groupHeadline(group: PermissionAcrossApps, systemPackages: Set<String>?): String {
    val count = group.holders.size
    val apps = if (count == 1) "1 app" else "$count apps"
    if (systemPackages == null) return apps

    val shipped = group.holders.count { it.packageName in systemPackages }
    return when (shipped) {
        0 -> "$apps, all installed by you"
        count -> "$apps, all came with the phone"
        else -> "$apps, $shipped came with the phone"
    }
}
