package com.bulwark.app.policy

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The action log's **storage**, tested on real SQLite.
 *
 * Every other log test uses an in-memory fake, which verifies the *contract*
 * and says nothing about the implementation underneath it. That implementation
 * is what `safety-rules.md` rules 3 and 5 both stand on - the undo and the
 * record - and until 2026-09-11 it had no tests at all. It was working, as far
 * as anyone could tell from using the app. "As far as anyone could tell" is the
 * phrase this project keeps learning to distrust.
 *
 * Instrumented rather than Robolectric on purpose: this tests the platform's
 * own SQLite on the hardware Bulwark targets, and adds no dependency to a
 * project that counts them (`supply-chain.md`).
 *
 * **Runs against its own database file, never the app's.** It used to use the
 * real one and clear it in `@Before` - documented in this very comment, and
 * still destroyed a phone's entire action history on 2026-09-14 when someone
 * ran the suite without re-reading it. An append-only record of what was done
 * to a stranger's phone is exactly the thing a test must not be able to reach,
 * so `SqliteActionLog` now takes the filename and this passes a test-only one.
 */
@RunWith(AndroidJUnit4::class)
class SqliteActionLogTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var log: SqliteActionLog

    @Before
    fun freshDatabase() {
        context.deleteDatabase(DATABASE)
        log = SqliteActionLog(context, databaseName = DATABASE)
    }

    private fun entry(
        pkg: String = "com.test.app",
        kind: ActionKind = ActionKind.DISABLE,
        phase: Phase = Phase.ATTEMPTED,
        previousState: Int? = null,
        attemptId: Long? = null,
        detail: String? = null,
    ) = NewEntry(pkg, kind, phase, userId = 0, previousState, attemptId, detail)

    @Test
    fun appendReturnsAnIdAndReadsBackIntact() {
        val id = log.append(entry(previousState = 4, detail = "why"))
        assertTrue("ids must be usable as attempt links", id > 0)

        val row = log.all().single()
        assertEquals(id, row.id)
        assertEquals("com.test.app", row.packageName)
        assertEquals(ActionKind.DISABLE, row.kind)
        assertEquals(Phase.ATTEMPTED, row.phase)
        assertEquals(4, row.previousState)
        assertEquals("why", row.detail)
    }

    @Test
    fun nullsSurviveTheRoundTrip() {
        // previousState is genuinely absent for an outcome row, and reading it
        // back as 0 would silently mean COMPONENT_ENABLED_STATE_DEFAULT - a
        // real state, and the wrong one.
        log.append(entry(previousState = null, attemptId = null, detail = null))
        val row = log.all().single()
        assertNull(row.previousState)
        assertNull(row.attemptId)
        assertNull(row.detail)
    }

    @Test
    fun previousStateZeroIsNotConfusedWithAbsent() {
        // DEFAULT is 0. If the column round-tripped 0 as null, every restore of
        // a package that shipped at DEFAULT would silently become a restore to
        // "whatever we guess".
        log.append(entry(previousState = 0))
        assertEquals(0, log.all().single().previousState)
    }

    @Test
    fun rowsComeBackOldestFirst() {
        // undoPlan reverses this list, so the order here decides whether undo
        // is a stack or nonsense.
        val first = log.append(entry(pkg = "com.first"))
        val second = log.append(entry(pkg = "com.second"))
        assertEquals(listOf(first, second), log.all().map { it.id })
        assertEquals(listOf("com.first", "com.second"), log.all().map { it.packageName })
    }

    @Test
    fun idsAreNeverReused() {
        // AUTOINCREMENT. An outcome row links to its attempt by id; a reused id
        // would attach an outcome to the wrong action.
        val first = log.append(entry())
        val second = log.append(entry())
        assertNotEquals(first, second)
        assertTrue(second > first)
    }

    @Test
    fun forPackageReturnsOnlyThatPackage() {
        log.append(entry(pkg = "com.wanted"))
        log.append(entry(pkg = "com.other"))
        log.append(entry(pkg = "com.wanted", phase = Phase.SUCCEEDED))

        assertEquals(2, log.forPackage("com.wanted").size)
        assertTrue(log.forPackage("com.wanted").all { it.packageName == "com.wanted" })
        assertTrue(log.forPackage("com.nothing").isEmpty())
    }

    @Test
    fun outcomesLinkToTheirAttemptAcrossAReopen() {
        // The property the whole append-only design exists for: the record has
        // to survive the process dying. A new SqliteActionLog over the same
        // file is the closest an instrumented test gets to that.
        val attempt = log.append(entry())
        log.append(entry(phase = Phase.SUCCEEDED, attemptId = attempt))

        val reopened = SqliteActionLog(context, databaseName = DATABASE)
        val rows = reopened.all()
        assertEquals(2, rows.size)
        assertEquals(attempt, rows[1].attemptId)
        assertTrue("nothing should look interrupted", rows.unfinished().isEmpty())
    }

    @Test
    fun anAttemptWithNoOutcomeSurvivesAsInterrupted() {
        // A process killed between the attempt and the outcome. This is the
        // state the log exists to make recoverable, and it has to survive a
        // reopen or it is worthless.
        log.append(entry(pkg = "com.interrupted"))

        val rows = SqliteActionLog(context, databaseName = DATABASE).all()
        assertEquals("com.interrupted", rows.unfinished().single().packageName)
    }

    @Test
    fun timestampsAreRecordedAndMonotonic() {
        val clock = object {
            var now = 1_000L
        }
        val timed = SqliteActionLog(context, databaseName = DATABASE) { clock.now }
        timed.append(entry())
        clock.now = 2_000L
        timed.append(entry())

        assertEquals(listOf(1_000L, 2_000L), timed.all().map { it.atEpochMillis })
    }

    @Test
    fun anEmptyLogIsEmptyRatherThanBroken() {
        assertTrue(log.all().isEmpty())
        assertTrue(log.forPackage("anything").isEmpty())
        assertTrue(log.all().undoPlan().isEmpty())
    }

    @Test
    fun theUndoPlanSurvivesStorage() {
        // Proves the pure functions still work over rows that made a round trip
        // through SQLite, rather than only over hand-built objects.
        val attempt = log.append(entry(pkg = "com.oem.bloat", previousState = 4))
        log.append(entry(pkg = "com.oem.bloat", phase = Phase.SUCCEEDED, attemptId = attempt))

        val plan = SqliteActionLog(context, databaseName = DATABASE).all().undoPlan().single()
        assertEquals(ActionKind.ENABLE, plan.kind)
        assertEquals("must carry the state we found, not a guess", 4, plan.previousState)
    }

    /**
     * **The migration, on a log that already has rows in it.**
     *
     * Every other test here starts from an empty database, so none of them
     * would notice if `onUpgrade` dropped the table - and SAI's phone holds a
     * real version-2 log full of real actions. An append-only record that a
     * version bump silently empties is the worst outcome this file can have,
     * and until now no test covered it: 1 -> 2 shipped untested in 2026-09-11.
     *
     * Builds a version-2 database by hand, in the shape a phone in the field
     * is holding right now, then opens the real `SqliteActionLog` over it.
     */
    @Test
    fun aVersionTwoLogUpgradesInPlaceAndKeepsEveryRow() {
        context.deleteDatabase(DATABASE)

        val legacy = object : SQLiteOpenHelper(context, DATABASE, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE actions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        at_millis INTEGER NOT NULL,
                        package_name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        previous_state INTEGER,
                        attempt_id INTEGER,
                        detail TEXT,
                        permission TEXT
                    )
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
        }
        legacy.writableDatabase.execSQL(
            "INSERT INTO actions " +
                "(at_millis, package_name, kind, phase, user_id, previous_state, permission) " +
                "VALUES (1000, 'com.test.legacy', 'REVOKE_PERMISSION', 'SUCCEEDED', 0, 1, " +
                "'android.permission.CAMERA')",
        )
        legacy.close()

        val rows = SqliteActionLog(context, databaseName = DATABASE).all()

        assertEquals("the version-2 row did not survive the upgrade", 1, rows.size)
        val row = rows.single()
        assertEquals("com.test.legacy", row.packageName)
        assertEquals(ActionKind.REVOKE_PERMISSION, row.kind)
        assertEquals("android.permission.CAMERA", row.permission)
        assertEquals(1, row.previousState)
        // Null is the truth for an old row, not a gap: no app op was involved.
        assertNull(row.appOp)
        assertNull(row.previousUidState)
    }

    /** A row written after the upgrade carries the new columns. */
    @Test
    fun anUpgradedLogCanStoreTheNewAppOpColumns() {
        context.deleteDatabase(DATABASE)
        val fresh = SqliteActionLog(context, databaseName = DATABASE)

        fresh.append(
            NewEntry(
                packageName = "com.test.app",
                kind = ActionKind.REVOKE_SPECIAL_ACCESS,
                phase = Phase.ATTEMPTED,
                userId = 0,
                previousState = 3,
                appOp = "android:manage_external_storage",
                previousUidState = 0,
            ),
        )

        val row = fresh.all().single()
        assertEquals("android:manage_external_storage", row.appOp)
        assertEquals(3, row.previousState)
        // Zero is a real mode (MODE_ALLOWED) and must not read as absent.
        assertEquals(0, row.previousUidState)
    }

    private companion object {
        /** **Not** `action-log.db`. See the class note. */
        const val DATABASE = "action-log-test.db"
    }

}
