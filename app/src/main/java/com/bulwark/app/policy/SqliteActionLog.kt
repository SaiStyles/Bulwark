package com.bulwark.app.policy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.pm.ApplicationInfo
import android.os.Looper

/**
 * [ActionLog] on the platform's own SQLite. One table, insert and select.
 *
 * ## Why not Room
 *
 * `app-architecture.md` chose Room on 2026-09-09 and this reverses it, so the
 * reasoning is here rather than in a commit message.
 *
 * Room would add six runtime artifacts plus KSP - a **code generator running
 * inside our build** - to the project that already refused `androidx.biometric`
 * in favour of the framework `BiometricPrompt`, on the grounds that "three
 * branches shorter" does not justify supply-chain surface in a
 * security-critical path. `supply-chain.md` puts it plainly: every dependency
 * is attack surface, and dependencies are where the 2025-26 attacks landed.
 * There are 392 pinned components in `verification-metadata.xml` today and
 * each one is a checksum somebody has to keep honest.
 *
 * What Room buys is compile-checked queries and migration scaffolding. This
 * schema is **one append-only table**: four statements, no joins, no
 * relations, and migrations that can only ever be `ALTER TABLE ADD COLUMN`
 * because rows are never rewritten. That is the case where the framework API
 * is genuinely sufficient, and the undo record for someone's only phone is a
 * good place to have fewer moving parts rather than more.
 *
 * The trade is real and worth stating: no compile-time query checking. It is
 * paid for by keeping every piece of *logic* out of this file. Ordering,
 * interrupted-attempt detection and undo planning are pure functions in
 * `ActionLog.kt` and tested without a device. What is left here is four
 * statements and a cursor loop.
 *
 * ## Never leaves the phone
 *
 * `getDatabasePath` is app-private storage, `android:allowBackup` is off with
 * exclude-everything rules for cloud backup *and* device transfer
 * (`security.md` FIXED-10), and Bulwark holds no `INTERNET` permission - the
 * build fails if one appears. The only way this file moves is a user
 * deliberately exporting it (`ActionLogExport`).
 */
class SqliteActionLog(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : ActionLog {

    /**
     * Debug builds refuse to touch the database from the main thread.
     *
     * Two reads landed in a composable body on 2026-09-11 - invisible on a fast
     * phone, and exactly what makes a cheap one stutter. StrictMode would not
     * have caught it: it watches the network here, and turning on disk
     * detection would bury real findings under the framework's own main-thread
     * reads, which is the noise-trains-people-to-ignore-it failure this project
     * already knows.
     *
     * So the check is narrow instead of broad: this database, this thread,
     * loud. Debug only - `penaltyDeath` on a stranger's phone turns our
     * performance bug into their crash.
     */
    private val strict =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun requireBackgroundThread(what: String) {
        if (!strict) return
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Action log $what on the main thread. It is a database; read it from " +
                "Dispatchers.IO."
        }
    }

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext, DATABASE_NAME, null, VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
            db.execSQL(CREATE_PACKAGE_INDEX)
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Append-only means no destructive migration is ever correct here.
            // Future versions add nullable columns; they never drop or rewrite
            // one, because a row already written is a record of something that
            // actually happened to a real phone.
        }

        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Default behaviour deletes the database. Refuse instead: losing
            // the log is worse than refusing to open on a downgraded build.
            throw IllegalStateException("Refusing to downgrade the action log ($old -> $new)")
        }
    }

    override fun append(entry: NewEntry): Long {
        requireBackgroundThread("write")
        val values = ContentValues().apply {
            put(COL_AT, now())
            put(COL_PACKAGE, entry.packageName)
            put(COL_KIND, entry.kind.name)
            put(COL_PHASE, entry.phase.name)
            put(COL_USER, entry.userId)
            entry.previousState?.let { put(COL_PREVIOUS_STATE, it) }
            entry.attemptId?.let { put(COL_ATTEMPT, it) }
            entry.detail?.let { put(COL_DETAIL, it) }
        }
        val id = helper.writableDatabase.insertOrThrow(TABLE, null, values)
        // insertOrThrow already throws on failure; -1 would mean the contract
        // changed under us. Fail closed rather than hand back a fake id that a
        // later outcome row would attach itself to.
        check(id != -1L) { "Action log insert returned no row id" }
        return id
    }

    override fun all(): List<ActionRecord> = query(selection = null, args = null)

    override fun forPackage(packageName: String): List<ActionRecord> =
        query("$COL_PACKAGE = ?", arrayOf(packageName))

    private fun query(selection: String?, args: Array<String>?): List<ActionRecord> {
        requireBackgroundThread("read")
        return helper.readableDatabase.query(
            TABLE, null, selection, args, null, null, "$COL_ID ASC",
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(cursor.toRecord())
            }
        }
    }

    private fun Cursor.toRecord() = ActionRecord(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        atEpochMillis = getLong(getColumnIndexOrThrow(COL_AT)),
        packageName = getString(getColumnIndexOrThrow(COL_PACKAGE)),
        // An unrecognised enum name means a row written by a newer build.
        // Reading it as null and dropping the row would hide history, so this
        // throws: an unreadable log must look broken, not empty.
        kind = ActionKind.valueOf(getString(getColumnIndexOrThrow(COL_KIND))),
        phase = Phase.valueOf(getString(getColumnIndexOrThrow(COL_PHASE))),
        userId = getInt(getColumnIndexOrThrow(COL_USER)),
        previousState = getIntOrNull(COL_PREVIOUS_STATE),
        attemptId = getLongOrNull(COL_ATTEMPT),
        detail = getStringOrNull(COL_DETAIL),
    )

    private fun Cursor.getIntOrNull(column: String): Int? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

    private fun Cursor.getLongOrNull(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private companion object {
        const val DATABASE_NAME = "action-log.db"
        const val VERSION = 1

        const val TABLE = "actions"
        const val COL_ID = "id"
        const val COL_AT = "at_millis"
        const val COL_PACKAGE = "package_name"
        const val COL_KIND = "kind"
        const val COL_PHASE = "phase"
        const val COL_USER = "user_id"
        const val COL_PREVIOUS_STATE = "previous_state"
        const val COL_ATTEMPT = "attempt_id"
        const val COL_DETAIL = "detail"

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AT INTEGER NOT NULL,
                $COL_PACKAGE TEXT NOT NULL,
                $COL_KIND TEXT NOT NULL,
                $COL_PHASE TEXT NOT NULL,
                $COL_USER INTEGER NOT NULL,
                $COL_PREVIOUS_STATE INTEGER,
                $COL_ATTEMPT INTEGER,
                $COL_DETAIL TEXT
            )
        """

        // AUTOINCREMENT above is deliberate and costs a little write speed: it
        // stops SQLite reusing the row id of a deleted row. Nothing here
        // deletes, so it should never matter - which is exactly why it is
        // cheap insurance against an outcome row attaching to the wrong
        // attempt if that ever stops being true.

        const val CREATE_PACKAGE_INDEX =
            "CREATE INDEX idx_${TABLE}_$COL_PACKAGE ON $TABLE ($COL_PACKAGE)"
    }
}
