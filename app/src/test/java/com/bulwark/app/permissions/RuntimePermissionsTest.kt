package com.bulwark.app.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgements behind the revoke button, asserted without a device.
 *
 * Every one of these decides whether Bulwark offers someone a destructive
 * control, or what it says when it will not. The screen renders these answers
 * and chooses none of them, which is what makes them testable at all -
 * `conventions.md`, "keep copy out of composables".
 */
class RuntimePermissionsTest {

    private fun holding(
        permission: String = "android.permission.CAMERA",
        granted: Boolean = true,
        runtime: Boolean? = true,
        flags: Int = 0,
        packageName: String = "com.example.app",
        protectedApp: Boolean = false,
    ) = PermissionHolding(packageName, permission, granted, runtime, flags, protectedApp)

    @Test
    fun `a granted runtime permission with no flags is ours to offer`() {
        assertEquals(Revocable.YES, holding().revocable())
    }

    @Test
    fun `a system-fixed permission is never offered`() {
        // The finding this whole file exists for: on 2026-09-11 `pm revoke`
        // against a SYSTEM_FIXED permission returned success and changed
        // nothing. Offering it would put a button on screen that lies, and
        // write the lie into the log.
        assertEquals(
            Revocable.FIXED_BY_SYSTEM,
            holding(flags = PermissionFlags.SYSTEM_FIXED).revocable(),
        )
    }

    @Test
    fun `a policy-fixed permission is never offered`() {
        assertEquals(
            Revocable.FIXED_BY_POLICY,
            holding(flags = PermissionFlags.POLICY_FIXED).revocable(),
        )
    }

    @Test
    fun `flags combine rather than replace each other`() {
        // Real flag values arrive with USER_SET and GRANTED_BY_DEFAULT bits
        // alongside. A check written as equality instead of a mask would read
        // "fixed" as "not fixed" the moment any other bit was set - and would
        // pass every test that only ever fed it one flag.
        val alsoUserSet = PermissionFlags.SYSTEM_FIXED or 1
        assertTrue(PermissionFlags.isFixed(alsoUserSet))
        assertEquals(Revocable.FIXED_BY_SYSTEM, holding(flags = alsoUserSet).revocable())
    }

    @Test
    fun `nothing held means nothing to take away`() {
        // Checked before fixedness: both are true of the same row, and this is
        // the truer thing to say about it.
        assertEquals(
            Revocable.NOT_GRANTED,
            holding(granted = false, flags = PermissionFlags.SYSTEM_FIXED).revocable(),
        )
    }

    @Test
    fun `an install-time permission is not offered`() {
        assertEquals(Revocable.NOT_RUNTIME, holding(runtime = false).revocable())
    }

    @Test
    fun `a permission Bulwark cannot classify fails closed and says so`() {
        // Unknown is a value, not a default. The alternative - reading "could
        // not tell" as "not a runtime permission" - puts a confident false
        // sentence on screen, which is how the special-access audit once told
        // a user they had installed their own launcher.
        val unknown = holding(runtime = null).revocable()
        assertEquals(Revocable.UNKNOWN_KIND, unknown)
        assertTrue("it must not be offered", !unknown.isOffered)
        assertNotNull("and it must explain itself", unknown.plainReason)
    }

    @Test
    fun `every refusal explains itself, and the offer does not`() {
        Revocable.entries.forEach { verdict ->
            if (verdict == Revocable.YES) {
                assertNull("an offer needs no excuse", verdict.plainReason)
            } else {
                val reason = verdict.plainReason
                assertNotNull("${verdict.name} must say why", reason)
                assertTrue("${verdict.name} must be a sentence", reason!!.length > 20)
            }
        }
    }

    @Test
    fun `a refusal blames the platform, never the app`() {
        // Bulwark cannot know why an app holds something. "This app insists on
        // it" is an accusation; "the phone's software fixes it" is the fact.
        val accusations = listOf("malicious", "malware", "spy", "insists", "refuses to")
        Revocable.entries.mapNotNull { it.plainReason }.forEach { reason ->
            accusations.forEach { word ->
                assertTrue(
                    "a refusal must not accuse the app: found \"$word\" in \"$reason\"",
                    !reason.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `a described permission says what it permits, not what it implies`() {
        val words = wordsFor("android.permission.RECORD_AUDIO")
        assertEquals("Microphone", words.name)
        assertNotNull(words.meaning)
        assertTrue(
            "meanings describe a capability",
            words.meaning!!.startsWith("Can "),
        )
    }

    @Test
    fun `no description accuses an app of anything`() {
        // The same rule the special-access audit follows: a red word next to a
        // legitimate screen reader is a false accusation pointed at the app a
        // disabled user depends on most.
        val accusations = listOf("malicious", "malware", "spy", "steal", "attack", "dangerous")
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_SMS",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ).forEach { permission ->
            val meaning = wordsFor(permission).meaning!!
            accusations.forEach { word ->
                assertTrue(
                    "\"$meaning\" must not contain \"$word\"",
                    !meaning.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `an undescribed permission is named honestly, not paraphrased`() {
        // A privacy tool that invents a description for a permission it does
        // not know is guessing about someone's phone in a confident voice.
        val words = wordsFor("com.oem.permission.SECRET_THING")
        assertEquals("Secret thing", words.name)
        assertNull("no meaning may be invented", words.meaning)
    }

    @Test
    fun `a permission with no dots still gets a name`() {
        assertEquals("Weird", wordsFor("weird").name)
    }

    @Test
    fun `the loudest permissions sort first`() {
        // A sort order, not a score: it says nothing about whether an app
        // should hold something. It exists because the first screenful is the
        // only one most people read.
        val order = listOf(
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.RECORD_AUDIO",
            "com.oem.permission.UNKNOWN",
            "android.permission.CAMERA",
        ).sortedBy { attentionRank(it) }

        assertEquals(
            listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.ACTIVITY_RECOGNITION",
                "com.oem.permission.UNKNOWN",
            ),
            order,
        )
    }

    @Test
    fun `grouping answers who can hear me, and only about what is held`() {
        val holdings = listOf(
            holding(packageName = "com.b", permission = "android.permission.RECORD_AUDIO"),
            holding(packageName = "com.a", permission = "android.permission.RECORD_AUDIO"),
            holding(packageName = "com.c", permission = "android.permission.CAMERA"),
            // Requested and refused. A list of apps that *could* ask answers a
            // different and much less useful question.
            holding(packageName = "com.d", permission = "android.permission.RECORD_AUDIO", granted = false),
        )

        val grouped = groupByPermission(holdings)

        assertEquals(
            listOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"),
            grouped.map { it.permission },
        )
        assertEquals(
            "holders are listed in name order",
            listOf("com.a", "com.b"),
            grouped.first().holders.map { it.packageName },
        )
    }

    @Test
    fun `a group separates what can be acted on from what cannot`() {
        val group = PermissionAcrossApps(
            "android.permission.CAMERA",
            listOf(
                holding(packageName = "com.a"),
                holding(packageName = "com.b", flags = PermissionFlags.SYSTEM_FIXED),
            ),
        )

        assertEquals(2, group.holders.size)
        assertEquals(
            "only the one the platform will actually let go",
            listOf("com.a"),
            group.revocable.map { it.packageName },
        )
    }

    @Test
    fun `the prompt names the capability and the count`() {
        // The two things the rule 1 amendment requires, in the one place an
        // attacker cannot rewrite. Everything on Bulwark's own screen is
        // writable by something that can drive the screen; this is not.
        val prompt = batchRevokePrompt(
            "android.permission.RECORD_AUDIO",
            listOf("com.a", "com.b", "com.c"),
        )

        assertTrue("the capability must be named: $prompt", prompt.contains("Microphone"))
        assertTrue("the count must be named: $prompt", prompt.contains("3 apps"))
    }

    @Test
    fun `a small batch names every app`() {
        val apps = List(NAMED_APPS_LIMIT) { "com.app$it" }
        val prompt = batchRevokePrompt("android.permission.CAMERA", apps)
        apps.forEach {
            assertTrue("$it must appear in: $prompt", prompt.contains(it))
        }
    }

    @Test
    fun `a large batch gives an honest count rather than a truncated list`() {
        // A list the system dialog cuts off reads as complete. The count does
        // not pretend, and it is what the user checks against what they chose.
        val apps = List(NAMED_APPS_LIMIT + 7) { "com.app$it" }
        val prompt = batchRevokePrompt("android.permission.CAMERA", apps)

        assertTrue(prompt.contains("${apps.size} apps"))
        assertTrue("no app may be named at all: $prompt", !prompt.contains("com.app"))
        assertTrue(
            "it must tell the user what the number is for: $prompt",
            prompt.contains("cancel", ignoreCase = true),
        )
    }

    @Test
    fun `one app reads as one app`() {
        val prompt = batchRevokePrompt("android.permission.CAMERA", listOf("com.a"))
        // Still names the app, so the sentence runs on into the list.
        assertTrue(prompt, prompt.contains("1 app: com.a."))
        assertTrue("no stray plural: $prompt", !prompt.contains("1 apps"))
    }

    @Test
    fun `the button and the prompt say the same thing`() {
        // If the button promises one capability and the system prompt names
        // another, the trustworthy channel has stopped matching the one the
        // user actually read.
        val button = batchRevokeButton("android.permission.RECORD_AUDIO", 3)
        val prompt = batchRevokePrompt(
            "android.permission.RECORD_AUDIO", listOf("com.a", "com.b", "com.c"),
        )

        assertTrue(button.contains("microphone"))
        assertTrue(prompt.contains("Microphone"))
        assertTrue(button.contains("3 apps"))
        assertTrue(prompt.contains("3 apps"))
    }

    @Test
    fun `origin is three-valued, and unknown says so`() {
        // Absent must not collapse into "you installed this" - that is the
        // exact sentence the special-access audit got wrong on hardware.
        assertEquals(
            "Came with the phone.",
            originLabelFor("com.android.systemui", setOf("com.android.systemui")),
        )
        assertEquals("You installed this.", originLabelFor("com.example.app", emptySet()))
        assertTrue(
            originLabelFor("com.example.app", null).contains("cannot tell"),
        )
    }

    @Test
    fun `a group headline says how much of the number is factory software`() {
        val group = PermissionAcrossApps(
            "android.permission.RECORD_AUDIO",
            listOf(
                holding(packageName = "com.android.dialer"),
                holding(packageName = "com.android.assistant"),
                holding(packageName = "com.example.app"),
            ),
        )

        assertEquals(
            "3 apps, 2 came with the phone",
            groupHeadline(group, setOf("com.android.dialer", "com.android.assistant")),
        )
        assertEquals("3 apps, all installed by you", groupHeadline(group, emptySet()))
        // Unknown: the count only. A split Bulwark cannot stand behind is
        // worse than no split.
        assertEquals("3 apps", groupHeadline(group, null))
    }

    @Test
    fun `the cross-app view leaves out what nobody can act on`() {
        // Found on the Agni 2, 2026-09-11: without this the screen fills with
        // install-time permissions - `Access adservices attribution` across 47
        // apps, `C2d message`, `Dynamic receiver not exported permission` -
        // and Microphone and Camera get pushed off the first screenful, which
        // is the only one most people read.
        val holdings = listOf(
            holding(permission = "android.permission.RECORD_AUDIO"),
            holding(permission = "android.permission.ACCESS_ADSERVICES_ATTRIBUTION", runtime = false),
            // Could not be classified. Not offerable either way, and a row
            // nobody can act on with an apology attached is still noise.
            holding(permission = "com.oem.permission.MYSTERY", runtime = null),
        )

        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            groupByPermission(holdings).map { it.permission },
        )
    }

    @Test
    fun `an app on the never-remove list is never offered a control`() {
        // Found by looking at the screen on 2026-09-11: the row offered a tick
        // for telephony components. The policy layer refused them, so nothing
        // unsafe happened - but a control that can only fail is a promise the
        // app cannot keep, and a batch stops at the first failure, so one such
        // tick halts everything after it.
        val verdict = holding(protectedApp = true).revocable()

        assertEquals(Revocable.PROTECTED, verdict)
        assertTrue("it must not be offered", !verdict.isOffered)
        assertNotNull("and it must say why", verdict.plainReason)
    }

    @Test
    fun `being protected outranks the platform's own refusals`() {
        // Both are true of a telephony component. "Bulwark will not touch this
        // app" is the more useful thing to tell someone, and it stays true
        // whatever the flags say.
        assertEquals(
            Revocable.PROTECTED,
            holding(protectedApp = true, flags = PermissionFlags.SYSTEM_FIXED).revocable(),
        )
    }

    @Test
    fun `but nothing held still reads as nothing to take away`() {
        // The one thing that outranks it: there is no point explaining a
        // refusal to change something the app does not have.
        assertEquals(
            Revocable.NOT_GRANTED,
            holding(protectedApp = true, granted = false).revocable(),
        )
    }

    @Test
    fun `the screen says a revoke is not a lock, and owns the part that is a choice`() {
        // Watched on hardware 2026-09-11: revoked, the app asked, the user said
        // yes, it came back. Nothing broken - but Bulwark had said nothing, and
        // advice that does not survive being followed is the failure this
        // project already named once.
        assertTrue(
            "it must say the app can ask again: $REVOKE_IS_NOT_A_LOCK",
            REVOKE_IS_NOT_A_LOCK.contains("asking for it again"),
        )
        // The limitation and the choice are different things, and the choice is
        // the one worth owning rather than hiding inside the limitation.
        assertTrue(
            "it must own the refusal to override the user: $REVOKE_IS_NOT_A_LOCK",
            REVOKE_IS_NOT_A_LOCK.contains("will not") &&
                REVOKE_IS_NOT_A_LOCK.contains("choice you made"),
        )
        // No promise it cannot keep.
        listOf("permanently", "forever", "never again", "blocks").forEach { word ->
            assertTrue(
                "it must not overclaim with \"$word\"",
                !REVOKE_IS_NOT_A_LOCK.contains(word, ignoreCase = true),
            )
        }
    }
}
