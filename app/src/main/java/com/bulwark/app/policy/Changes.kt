package com.bulwark.app.policy

/**
 * What Bulwark has changed on this phone, right now.
 *
 * The log is append-only history; this is the *state* it adds up to. Same
 * derivation as [blockedPackages], widened to every kind: only closed attempts
 * count, and the last decision per thing wins, so switch-off-then-on leaves
 * nothing behind.
 *
 * ## Why this exists
 *
 * Until 2026-09-12 Bulwark could change 14 things and then not tell you which
 * 14. The count was on screen ("2 apps blocked from the internet"); the names
 * were not, anywhere. You could scroll 371 rows looking for marks, or export a
 * file. That is not reversibility - `safety-rules.md` promises every
 * destructive action is reversible, and a reversal you cannot see or aim is one
 * all-or-nothing button.
 *
 * Pure, so the list a person acts on is tested without a device.
 */
enum class ChangeKind {
    /** Disabled. Still installed, data kept. */
    SWITCHED_OFF,

    /** A runtime permission taken away. Keyed by package *and* permission. */
    PERMISSION_TAKEN,

    /** In the firewall's rule set. Enforced only while the tunnel runs. */
    INTERNET_BLOCKED,

    /** Removed for this user. The APK stays on `/system`. Not built yet. */
    UNINSTALLED,
}

/**
 * One thing Bulwark changed, and can put back.
 *
 * [permission] is set only for [ChangeKind.PERMISSION_TAKEN]; the same package
 * can appear several times, once per permission, because each is undone
 * separately.
 */
data class Change(
    val packageName: String,
    val kind: ChangeKind,
    val permission: String? = null,
    val atEpochMillis: Long = 0L,
)

/** The undo for each kind, so a caller never has to map it by hand. */
val ChangeKind.undoKind: ActionKind
    get() = when (this) {
        ChangeKind.SWITCHED_OFF -> ActionKind.ENABLE
        ChangeKind.PERMISSION_TAKEN -> ActionKind.GRANT_PERMISSION
        ChangeKind.INTERNET_BLOCKED -> ActionKind.ALLOW_NETWORK
        ChangeKind.UNINSTALLED -> ActionKind.INSTALL_EXISTING
    }

private val ActionKind.changes: ChangeKind?
    get() = when (this) {
        ActionKind.DISABLE, ActionKind.ENABLE -> ChangeKind.SWITCHED_OFF
        ActionKind.REVOKE_PERMISSION, ActionKind.GRANT_PERMISSION -> ChangeKind.PERMISSION_TAKEN
        ActionKind.BLOCK_NETWORK, ActionKind.ALLOW_NETWORK -> ChangeKind.INTERNET_BLOCKED
        ActionKind.UNINSTALL, ActionKind.INSTALL_EXISTING -> ChangeKind.UNINSTALLED
    }

/** True for the half of each pair that leaves the phone changed. */
private val ActionKind.leavesAChange: Boolean
    get() = this == ActionKind.DISABLE ||
        this == ActionKind.REVOKE_PERMISSION ||
        this == ActionKind.BLOCK_NETWORK ||
        this == ActionKind.UNINSTALL

/**
 * Everything Bulwark has changed and not put back.
 *
 * **Only [Phase.SUCCEEDED] attempts count.** An attempt with no outcome is
 * [unfinished] - Bulwark does not know whether it landed, and listing it here
 * as undoable would offer to reverse something that may never have happened.
 * Those are reported separately, which is `safety-rules.md` rule 6.
 *
 * Newest first, because the thing you just did is the thing you are most likely
 * to want back.
 */
fun List<ActionRecord>.currentChanges(): List<Change> {
    val closed = filter { it.phase == Phase.SUCCEEDED }.mapNotNull { it.attemptId }.toSet()
    // Keyed by the thing changed, not by the action: package for two kinds,
    // package + permission for the third, so revoking two permissions from one
    // app is two entries and undoing one leaves the other.
    val decided = mutableMapOf<Triple<String, ChangeKind, String?>, ActionRecord>()
    filter { it.phase == Phase.ATTEMPTED && it.id in closed }
        .forEach { record ->
            val kind = record.kind.changes ?: return@forEach
            decided[Triple(record.packageName, kind, record.permission)] = record
        }
    return decided
        .filterValues { it.kind.leavesAChange }
        .map { (key, record) ->
            Change(
                packageName = key.first,
                kind = key.second,
                permission = key.third,
                atEpochMillis = record.atEpochMillis,
            )
        }
        .sortedByDescending { it.atEpochMillis }
}

/**
 * The one-line summary above the list.
 *
 * Says the number *before* anything is committed to. "Put everything back" used
 * to ask for a decision without showing what the decision covered.
 */
fun changesHeadline(changes: List<Change>): String {
    if (changes.isEmpty()) return "Bulwark has not changed anything on this phone."
    val parts = buildList {
        val off = changes.count { it.kind == ChangeKind.SWITCHED_OFF }
        val perms = changes.count { it.kind == ChangeKind.PERMISSION_TAKEN }
        val blocked = changes.count { it.kind == ChangeKind.INTERNET_BLOCKED }
        val gone = changes.count { it.kind == ChangeKind.UNINSTALLED }
        if (off > 0) add(if (off == 1) "1 app switched off" else "$off apps switched off")
        if (perms > 0) add(if (perms == 1) "1 permission taken" else "$perms permissions taken")
        if (blocked > 0) add(if (blocked == 1) "1 app blocked" else "$blocked apps blocked")
        if (gone > 0) add(if (gone == 1) "1 app removed" else "$gone apps removed")
    }
    val total = if (changes.size == 1) "1 change" else "${changes.size} changes"
    return "$total: " + parts.joinToString(", ") + "."
}

/**
 * What one entry says about itself.
 *
 * Plain, and never reassuring: a blocked app is only blocked while the tunnel
 * runs, and this list must not imply otherwise. The firewall card is where that
 * state is said properly; here the row states the rule, not its enforcement.
 */
fun Change.describe(): String = when (kind) {
    ChangeKind.SWITCHED_OFF ->
        "Switched off. Still installed, data kept."
    ChangeKind.PERMISSION_TAKEN ->
        "Permission taken away. The app can ask again, and you may say yes."
    ChangeKind.INTERNET_BLOCKED ->
        "Set to block the internet. Enforced only while Bulwark's tunnel runs."
    ChangeKind.UNINSTALLED ->
        "Removed for this user. The APK is still on the system partition."
}

/** The button on the row. Names the undo, not the change. */
fun Change.undoLabel(): String = when (kind) {
    ChangeKind.SWITCHED_OFF -> "Switch back on"
    ChangeKind.PERMISSION_TAKEN -> "Give it back"
    ChangeKind.INTERNET_BLOCKED -> "Allow online"
    ChangeKind.UNINSTALLED -> "Put back"
}
