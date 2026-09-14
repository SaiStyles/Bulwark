package com.bulwark.app.policy

import com.bulwark.app.shizuku.AppOpsWriter
import com.bulwark.app.shizuku.CommandSafety

/**
 * Taking one special access away, and giving it back.
 *
 * The app-op twin of [PermissionActions], deliberately the same shape and the
 * same guard order:
 *
 * 1. **`CommandSafety.requireMutable`** - validates the name and refuses the
 *    never-remove list.
 * 2. **Refuse if this device has no app-op API we recognise.** An action whose
 *    undo cannot be resolved is not reversible.
 * 3. **Refuse a shared uid.** Below, and the reason this class exists at all.
 * 4. **Read the current state**, so revoking something not held does nothing
 *    and records nothing rather than writing a lie into the log.
 * 5. **`ActionJournal.perform`** - attempt, work, outcome, in that order.
 * 6. **Read the state back**, because a privileged call that returns without
 *    throwing has not necessarily done anything.
 *
 * ## The shared-uid refusal
 *
 * An op held at uid level can only be changed with `setUidMode`, which applies
 * to **every package sharing that uid**. Aiming at one app and silently
 * changing three is broader than anything the user chose, and it is
 * unattributable afterwards - which is the harm `safety-rules.md` rule 1 names.
 *
 * So Bulwark refuses. Not "warns and proceeds": the screen would have to
 * explain a concept nobody asked about at the moment they are trying to do
 * something simple, and the honest answer to "I cannot do this to one app
 * without doing it to three" is to say so and stop.
 *
 * A uid with one package - the ordinary case - is unaffected.
 *
 * ## What is deliberately not here
 *
 * **Authentication.** It needs an `Activity` and belongs at the UI edge, same
 * as the other two policy classes. Nothing here may be called except from a
 * path holding `Result.Authenticated`.
 *
 * **Any bulk form.** One app, one op, one decision.
 */
class SpecialAccessActions(
    private val journal: ActionJournal,
    /** Seam for tests. Production passes the real privileged calls. */
    private val access: Access = PlatformAccess,
) {

    /**
     * Which entry actually governs an op for one app.
     *
     * Declared here rather than borrowed from `shizuku/`, because the wrappers
     * in that package are `internal` by convention and a public seam must not
     * leak one. The policy layer is where "which door, and may we use it"
     * is decided anyway.
     */
    enum class Door {
        /** Held at uid level; only `setUidMode` moves it, and it moves it for
         *  every package sharing that uid. */
        UID,

        /** No uid entry, so the package entry governs and only this app moves. */
        PACKAGE,
    }

    /** The privileged surface this needs, behind a seam tests can drive. */
    interface Access {
        val isSupported: Boolean
        fun opCode(opName: String): Int
        fun uidOf(packageName: String, userId: Int): Int
        fun packagesSharingUid(uid: Int): List<String>
        fun mode(code: Int, uid: Int, packageName: String): Int
        fun uidMode(code: Int, uid: Int): Int
        fun packageMode(code: Int, uid: Int, packageName: String): Int
        fun door(code: Int, uid: Int): Door
        fun setPackageMode(code: Int, uid: Int, packageName: String, mode: Int)
        fun setUidMode(code: Int, uid: Int, mode: Int)
    }

    private object PlatformAccess : Access {
        override val isSupported: Boolean get() = AppOpsWriter.canChangeAppOps
        override fun opCode(opName: String) = AppOpsWriter.opCode(opName)
        override fun uidOf(packageName: String, userId: Int) =
            AppOpsWriter.uidOf(packageName, userId)
        override fun packagesSharingUid(uid: Int) = AppOpsWriter.packagesSharingUid(uid)
        override fun mode(code: Int, uid: Int, packageName: String) =
            AppOpsWriter.mode(code, uid, packageName)
        override fun uidMode(code: Int, uid: Int) = AppOpsWriter.uidMode(code, uid)
        override fun packageMode(code: Int, uid: Int, packageName: String) =
            AppOpsWriter.packageMode(code, uid, packageName)
        override fun door(code: Int, uid: Int) = when (AppOpsWriter.doorFor(code, uid)) {
            AppOpsWriter.Door.UID -> Door.UID
            AppOpsWriter.Door.PACKAGE -> Door.PACKAGE
        }
        override fun setPackageMode(code: Int, uid: Int, packageName: String, mode: Int) =
            AppOpsWriter.setPackageMode(code, uid, packageName, mode)
        override fun setUidMode(code: Int, uid: Int, mode: Int) =
            AppOpsWriter.setUidMode(code, uid, mode)
    }

    /**
     * Takes [opName] away from [packageName].
     *
     * @throws IllegalArgumentException if the package name is malformed.
     * @throws SecurityException if the package is on the never-remove list, or
     *   if the op is held at uid level by more than one package.
     * @throws IllegalStateException if this device has no app-op API we
     *   recognise, or if the platform accepted the call and changed nothing.
     */
    fun revoke(packageName: String, opName: String, userId: Int = 0) {
        write(packageName, opName, userId, AppOpsWriter.MODE_IGNORED, ActionKind.REVOKE_SPECIAL_ACCESS)
    }

    /**
     * Puts back exactly what was there. The undo for [revoke].
     *
     * Takes both recorded entries rather than assuming `MODE_ALLOWED`, because
     * "reversible" means back to what it was and not back to permitted. An op
     * the phone held at uid level with no package entry must come back that
     * way; writing the effective value through the package door leaves state
     * the phone never had, which is the residue this pair of columns exists to
     * prevent.
     */
    fun giveBack(
        packageName: String,
        opName: String,
        previousPackageMode: Int,
        previousUidMode: Int,
        userId: Int = 0,
    ) {
        CommandSafety.requireMutable(packageName)
        check(access.isSupported) {
            "This phone has no app-op interface Bulwark recognises, so it will not try."
        }

        val code = access.opCode(opName)
        val uid = access.uidOf(packageName, userId)

        journal.perform(
            kind = ActionKind.GRANT_SPECIAL_ACCESS,
            packageName = packageName,
            userId = userId,
            previousState = access.packageMode(code, uid, packageName),
            previousUidState = access.uidMode(code, uid),
            appOp = opName,
        ) {
            // Each entry back to what it was - and **only if it moved**.
            //
            // Writing a door that is already correct is not free: setting the
            // uid entry to MODE_DEFAULT records an entry saying "no override"
            // where the phone had no entry at all. Inert, since default is
            // default and `doorFor` still reads PACKAGE, but it is not the
            // state the phone was in, and this class exists to put things back
            // rather than to put them somewhere equivalent. Seen on hardware
            // 2026-09-14 after an otherwise perfect clipboard round trip.
            if (access.uidMode(code, uid) != previousUidMode) {
                access.setUidMode(code, uid, previousUidMode)
            }
            if (access.packageMode(code, uid, packageName) != previousPackageMode) {
                access.setPackageMode(code, uid, packageName, previousPackageMode)
            }
            confirmGiven(code, uid, packageName)
        }
    }

    private fun write(
        packageName: String,
        opName: String,
        userId: Int,
        target: Int,
        kind: ActionKind,
    ) {
        CommandSafety.requireMutable(packageName)
        check(access.isSupported) {
            "This phone has no app-op interface Bulwark recognises, so it will not try."
        }

        val code = access.opCode(opName)
        val uid = access.uidOf(packageName, userId)
        val door = access.door(code, uid)

        if (door == Door.UID) {
            val sharing = access.packagesSharingUid(uid)
            if (sharing.size > 1) {
                throw SecurityException(
                    "$packageName shares its Android user id with " +
                        "${sharing.size - 1} other app(s): " +
                        sharing.filterNot { it == packageName }.joinToString(", ") +
                        ". This access is held for all of them together, so Bulwark " +
                        "cannot change it for one without changing it for every one - " +
                        "and it will not do that on your behalf.",
                )
            }
        }

        val before = access.mode(code, uid, packageName)
        // Already where we are trying to put it. Do nothing, record nothing - a
        // row claiming a change that did not happen is a false record, and the
        // log is the thing a user is meant to be able to trust.
        if (before == target) return

        // **Each entry as it stands, not the effective answer.** `before` above
        // is what the platform resolves to; these two are what is actually
        // stored, and only they can be put back faithfully. Recording the
        // effective value here is what left residue on a real app on
        // 2026-09-14.
        val beforePackage = access.packageMode(code, uid, packageName)
        val beforeUid = access.uidMode(code, uid)

        journal.perform(
            kind = kind,
            packageName = packageName,
            userId = userId,
            previousState = beforePackage,
            previousUidState = beforeUid,
            appOp = opName,
        ) {
            when (door) {
                Door.UID -> access.setUidMode(code, uid, target)
                Door.PACKAGE -> access.setPackageMode(code, uid, packageName, target)
            }
            confirmApplied(code, uid, packageName, target)
        }
    }

    /**
     * Rule 6 for the undo, and it needs a different question from the revoke.
     *
     * Restoring two recorded entries can succeed at the entry level and still
     * leave the app denied - which is exactly what happened on 2026-09-14, when
     * a row written by an earlier build carried a wrong `previous_uid_state`
     * and the undo faithfully wrote it back. Both writes landed. The access
     * stayed revoked. Bulwark recorded SUCCEEDED.
     *
     * So the undo checks the thing the person actually asked for: **is the
     * access usable again.** It does not then force `MODE_ALLOWED` - inventing
     * a grant nobody recorded would be Bulwark making a change of its own while
     * claiming to undo one. It fails, says why, and leaves the decision.
     */
    private fun confirmGiven(code: Int, uid: Int, packageName: String) {
        val effective = access.mode(code, uid, packageName)
        check(effective != AppOpsWriter.MODE_IGNORED) {
            "Bulwark put back the settings it recorded and $packageName is still " +
                "denied this access. The record may have been written before a " +
                "fix, so it no longer describes what was there. Nothing else was " +
                "changed - grant it in Settings if you want it back."
        }
    }

    /**
     * Rule 6: confirm the change landed before reporting success.
     *
     * The most necessary guard in this file. `setMode` returns `void` - no
     * error, no result - and on 2026-09-14 a write through the wrong door did
     * exactly nothing while looking identical to success.
     */
    private fun confirmApplied(code: Int, uid: Int, packageName: String, expected: Int) {
        val actual = access.mode(code, uid, packageName)
        check(actual == expected) {
            "The phone accepted the change and did not make it: expected mode " +
                "$expected for $packageName, still reads $actual."
        }
    }
}
