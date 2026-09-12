package com.bulwark.app.policy

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
    fun anUnfinishedCheckIsTreatedAsPossiblyCritical() {
        // Fail closed. The holder Bulwark could not read might be this one,
        // and an unread check read as an all-clear is the failure this project
        // keeps paying for.
        val s = standing(incomplete = true)

        assertFalse("nothing was found, so it is not known to be critical", s.isCritical)
        assertTrue("but it still takes the ceremony", s.needsCeremony)
        assertTrue(
            "and it says so rather than implying an all-clear",
            s.labels().any { it.contains("could not finish checking") },
        )
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
    fun restorabilityIsStatedOnEveryRowAndSaysWhichWay() {
        val preinstalled = standing().labels().last()
        val userInstalled =
            standing(restorability = Restorability.GONE_FOR_GOOD).labels().last()

        assertTrue(preinstalled, preinstalled.contains("Bulwark can put this back"))
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
