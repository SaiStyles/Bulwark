package com.bulwark.app.policy

import com.bulwark.app.shizuku.CriticalRoles
import com.bulwark.app.shizuku.CriticalRoles.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a person is told, and what they have to do to proceed.
 *
 * The blocklist that used to answer these questions was retired on 2026-09-12
 * because it guessed from package names. Everything asserted here is derived
 * from what the phone said, so the tests drive the jobs directly.
 */
class PackageStandingTest {

    private fun standing(
        name: String = "com.example.app",
        jobs: Set<Job> = emptySet(),
        restorability: Restorability = Restorability.BULWARK_CAN_RESTORE,
        incomplete: Boolean = false,
        isSelf: Boolean = false,
    ) = Standing(name, jobs, restorability, incomplete, isSelf)

    @Test
    fun anOrdinaryPreinstalledAppNeedsNoCeremony() {
        val s = standing()

        assertFalse(s.isCritical)
        assertFalse(s.needsCeremony)
        assertFalse(s.refused)
        assertNull("no second warning for an ordinary app", s.secondWarning())
    }

    @Test
    fun aPackageThePhoneNamedAsCriticalGetsTheCeremony() {
        val s = standing(jobs = setOf(Job.DIALER))

        assertTrue(s.isCritical)
        assertTrue(s.needsCeremony)
        assertTrue(
            "the warning must name the consequence: ${s.secondWarning()}",
            s.secondWarning()!!.contains("emergency calls"),
        )
    }

    @Test
    fun anUnfinishedCheckIsSaidOutLoudButDoesNotEscalate() {
        // It used to fire the ceremony, on fail-closed reasoning. One failed
        // read would then have put the ceremony on all 370 packages, and a
        // gate that fires on nearly everything teaches people to type through
        // the one that mattered - the same narrowing A2b already needed.
        val s = standing(incomplete = true)

        assertFalse("nothing was found, so it is not known to be critical", s.isCritical)
        assertFalse("and an ordinary app must not inherit the ceremony", s.needsCeremony)
        // And it is no longer said on the row either. One failing read marks
        // every package, so the line appeared on all 370 - a notice that fires
        // on everything says nothing, and it had no action attached. The flag
        // is still held, for a screen-level notice to use.
        assertTrue(s.checkIncomplete)
        assertTrue(
            "no per-row noise",
            s.labels().none { it.contains("could not finish checking") },
        )
    }

    @Test
    fun anEverydayAppNeverGetsTheCeremonyHoweverTheCheckWent() {
        // Instagram, and the other 93 like it. The ceremony is for the handful
        // the phone actually named.
        listOf(true, false).forEach { incomplete ->
            val s = standing(
                name = "com.instagram.android",
                restorability = Restorability.GONE_FOR_GOOD,
                incomplete = incomplete,
            )
            assertFalse("incomplete=$incomplete", s.needsCeremony)
            assertNull("incomplete=$incomplete", s.secondWarning())
        }
    }

    @Test
    fun bulwarkIsTheOneThingRefused() {
        val s = standing(name = "com.bulwark.app", isSelf = true)

        assertTrue(s.refused)
        assertFalse("a refusal is not a ceremony", s.needsCeremony)
        assertTrue(
            "and it says why, on technical grounds: ${s.labels()}",
            s.labels().single().contains("destroy the record that undoes it"),
        )
    }

    @Test
    fun `no label promises an outcome Bulwark cannot guarantee`() {
        val texts = listOf(
            standing().labels().joinToString(" "),
            standing(jobs = setOf(Job.SETTINGS)).secondWarning().orEmpty(),
        )
        listOf("will restore", "can put this back", "guaranteed", "always").forEach { word ->
            texts.forEach { t ->
                assertFalse("a promise survived: $word in $t", t.contains(word))
            }
        }
    }

    @Test
    fun `Shizuku is refused, because switching it off removes the way back`() {
        // 2026-09-12: it was allowed, SAI switched it off through Bulwark, and
        // Bulwark could not switch it back on - re-enabling needs the privilege
        // that disabling just removed. It took a PC and adb.
        val s = standingFor(
            "moe.shizuku.privileged.api",
            isSystem = false,
            roles = CriticalRoles.Reading(emptyMap(), emptySet()),
            selfPackage = "com.bulwark.app",
        )

        assertTrue(s.refused)
        assertFalse("a refusal is not a ceremony", s.needsCeremony)
        assertEquals("SHIZUKU", s.badge())
        assertTrue(
            "and it says why: ${s.labels()}",
            s.labels().single().contains("switch it back on"),
        )
    }

    @Test
    fun restorabilityIsStatedOnEveryRowAndSaysWhichWay() {
        val preinstalled = standing().labels().last()
        val userInstalled =
            standing(restorability = Restorability.GONE_FOR_GOOD).labels().last()

        assertTrue(preinstalled, preinstalled.contains("shipped with"))
        assertTrue(
            "an updated system app comes back as the shipped version, and that " +
                "is not the same app the user had: $preinstalled",
            preinstalled.contains("update"),
        )
        // SAI's rule, 2026-09-13: if one app could break the claim, the user
        // gets the doubt. Three successes on one device is evidence, not a
        // promise - so the row must not read like one.
        assertTrue(
            "restore must not be stated as a certainty: $preinstalled",
            preinstalled.contains("try to") || preinstalled.contains("not the same as a promise"),
        )
        assertTrue(userInstalled, userInstalled.contains("cannot put it back"))
    }

    @Test
    fun theSecondWarningForAUserInstalledAppSaysItCannotComeBack() {
        val s = standing(jobs = setOf(Job.IMS), restorability = Restorability.GONE_FOR_GOOD)

        assertTrue(s.secondWarning()!!.contains("cannot put this one back at all"))
    }

    @Test
    fun noRowCarriesAStrangersVerdict() {
        // The whole point of dropping the UAD badges: Bulwark states facts
        // about this device and never someone else's rating.
        val everything = Job.entries.toSet()
        val text = standing(jobs = everything).labels().joinToString(" ") +
            " " + standing(jobs = everything).secondWarning()

        listOf("recommended", "safe", "unsafe", "expert", "advanced").forEach { word ->
            assertFalse("a rating word survived: $word in $text", text.lowercase().contains(word))
        }
    }

    @Test
    fun jobsReadInAFixedOrderSoThePhoneAlwaysReadsTheSame() {
        val one = standing(jobs = setOf(Job.IMS, Job.HOME)).labels()
        val two = standing(jobs = setOf(Job.HOME, Job.IMS)).labels()

        assertEquals(one, two)
        assertTrue("home comes first", one.first().contains("home screen"))
    }

    @Test
    fun theBadgeNamesTheJobRatherThanRatingThePackage() {
        assertEquals("DIALER", standing(jobs = setOf(Job.DIALER)).badge())
        assertEquals("BULWARK", standing(isSelf = true).badge())
        assertNull("an ordinary app wears no badge", standing().badge())
    }

    @Test
    fun onlyOneBadgeEvenWhenThePhoneNamesSeveralJobs() {
        // design.md rule 5 - a row that shouts twice has said nothing. And it
        // must not reorder itself between reads, so declaration order wins.
        val both = standing(jobs = setOf(Job.IMS, Job.HOME))

        assertEquals("HOME SCREEN", both.badge())
        assertEquals(both.badge(), standing(jobs = setOf(Job.HOME, Job.IMS)).badge())
    }

    @Test
    fun aReadingThatHasNotArrivedIsNotAnAllClear() {
        // Null is "not read yet", which is not the same as a phone with no
        // dialer. It must not quietly render as ordinary.
        val s = standingFor("com.example.app", isSystem = true, roles = null, selfPackage = "com.bulwark.app")

        assertTrue(s.checkIncomplete)
        assertFalse("but it still does not escalate", s.needsCeremony)
    }

    @Test
    fun restorabilityFollowsWhetherThePackageWasPreinstalled() {
        val roles = CriticalRoles.Reading(emptyMap(), emptySet())
        val preinstalled = standingFor("com.oem.bloat", true, roles, "com.bulwark.app")
        val theirs = standingFor("com.instagram.android", false, roles, "com.bulwark.app")

        assertEquals(Restorability.BULWARK_CAN_RESTORE, preinstalled.restorability)
        assertEquals(Restorability.GONE_FOR_GOOD, theirs.restorability)
        assertFalse("a complete reading is not an incomplete check", preinstalled.checkIncomplete)
    }

    @Test
    fun theTypedPhraseIsThePackageNameAndNothingLooser() {
        val s = standing(name = "com.android.systemui", jobs = setOf(Job.SYSTEM_UI))

        assertEquals("com.android.systemui", s.confirmationPhrase())
        assertTrue("surrounding space is forgiven", s.confirmationAccepts("  com.android.systemui "))
        assertFalse("case is not", s.confirmationAccepts("COM.ANDROID.SYSTEMUI"))
        assertFalse("nor a near miss", s.confirmationAccepts("com.android.systemu"))
        assertFalse("nor a shortcut", s.confirmationAccepts("yes"))
        assertFalse("nor empty", s.confirmationAccepts(""))
    }
}
