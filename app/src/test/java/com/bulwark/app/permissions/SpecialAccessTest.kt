package com.bulwark.app.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the audit says, and what it refuses to say.
 *
 * The hard part of this layer is not reading the data - it is not overreaching
 * with it. Bulwark cannot know whether an app has a good reason to hold
 * something, and a red badge on a legitimate screen reader would be a false
 * accusation aimed at the app a disabled user depends on most.
 */
class SpecialAccessTest {

    private fun app(
        name: String,
        vararg accesses: Access,
        isSystem: Boolean? = false,
    ) = AppAccess(name, accesses.toSet(), isSystem)

    @Test
    fun `every access explains itself in plain language`() {
        // "Holds BIND_ACCESSIBILITY_SERVICE" is not something a person can act
        // on. "Can read everything on your screen" is.
        Access.entries.forEach {
            assertTrue("${it.name} needs a meaning", it.plainMeaning.length > 20)
            assertTrue("${it.name} needs a label", it.shortLabel.isNotBlank())
            assertFalse(
                "${it.name} must not leak the constant name",
                it.plainMeaning.contains("_") || it.shortLabel.contains("_"),
            )
        }
    }

    @Test
    fun `an app holding nothing does not appear in the audit`() {
        // 370 rows saying "nothing" buries the ten that matter, and the phone
        // already has a list of installed apps.
        val audited = listOf(app("com.quiet.app")).audit()
        assertTrue(audited.isEmpty())
    }

    @Test
    fun `a single access is worth knowing, not an alarm`() {
        val a = app("com.some.app", Access.NOTIFICATION_LISTENER)
        assertEquals(Attention.WORTH_KNOWING, a.attention)
        assertTrue("one access is not a combination", a.combinations().isEmpty())
    }

    @Test
    fun `screen control plus drawing over apps is called out`() {
        // The pairing that lets malware act on the phone while showing the
        // user something else. A fact about Android, not a claim about the app.
        val a = app("com.some.app", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS)
        assertEquals(Attention.LOOK_AT_THIS, a.attention)
        val why = a.combinations().single().why
        assertTrue("must explain the actual mechanism: $why", why.contains("showing you something else"))
    }

    @Test
    fun `monitoring-shaped combination is named`() {
        val a = app("com.some.app", Access.DEVICE_ADMIN, Access.ACCESSIBILITY)
        assertEquals(Attention.LOOK_AT_THIS, a.attention)
        assertTrue(a.combinations().any { it.why.contains("monitoring software") })
    }

    @Test
    fun `the audit never accuses, it only describes`() {
        // Every combination explanation must describe a capability, not
        // attribute intent. "It can X" is checkable; "it is malware" is a guess
        // Bulwark has no standing to make.
        val a = app(
            "com.some.app",
            Access.ACCESSIBILITY, Access.DRAW_OVER_APPS,
            Access.NOTIFICATION_LISTENER, Access.DEVICE_ADMIN,
            Access.INSTALL_UNKNOWN_APPS,
        )
        a.combinations().forEach {
            assertFalse(
                "must not accuse: ${it.why}",
                it.why.contains("malicious") || it.why.contains("is malware") ||
                    it.why.contains("spying"),
            )
        }
    }

    @Test
    fun `things worth looking at come first`() {
        val audited = listOf(
            app("com.one.access", Access.USAGE_ACCESS),
            app("com.combination", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
            app("com.nothing"),
        ).audit()

        assertEquals(listOf("com.combination", "com.one.access"), audited.map { it.packageName })
    }

    @Test
    fun `a user-installed app outranks a system one holding the same thing`() {
        // A preinstalled launcher reading notifications is how launchers work.
        // A downloaded app doing it is a choice somebody made.
        val audited = listOf(
            app("com.android.launcher3", Access.NOTIFICATION_LISTENER, isSystem = true),
            app("com.downloaded.app", Access.NOTIFICATION_LISTENER),
        ).audit()

        assertEquals("com.downloaded.app", audited.first().packageName)
    }

    @Test
    fun `the summary counts what the header claims`() {
        val apps = listOf(
            app("com.combination", Access.ACCESSIBILITY, Access.DRAW_OVER_APPS),
            app("com.sys", Access.USAGE_ACCESS, isSystem = true),
            app("com.user", Access.INSTALL_UNKNOWN_APPS),
            app("com.nothing"),
        )
        val s = apps.summarise()
        assertEquals(3, s.appsWithAnyAccess)
        assertEquals(1, s.appsToLookAt)
        assertEquals("com.nothing holds nothing and must not be counted", 2, s.userInstalledWithAccess)
    }

    @Test
    fun `unknown system-ness is never counted as user-installed`() {
        // The first hardware run said "2 apps - 2 you installed yourself"
        // about the launcher and Android Auto, because without Shizuku the
        // system list was empty and absent read as false. That is the most
        // alarming reading of the data, stated falsely.
        val apps = listOf(
            app("com.unknown.one", Access.NOTIFICATION_LISTENER, isSystem = null),
            app("com.unknown.two", Access.NOTIFICATION_LISTENER, isSystem = null),
        )
        assertEquals(2, apps.summarise().appsWithAnyAccess)
        assertEquals(
            "unknown must not be claimed as user-installed",
            0, apps.summarise().userInstalledWithAccess,
        )
    }

    @Test
    fun `unknown sorts between user-installed and system`() {
        // Not accused, not excused.
        val audited = listOf(
            app("com.system", Access.USAGE_ACCESS, isSystem = true),
            app("com.unknown", Access.USAGE_ACCESS, isSystem = null),
            app("com.user", Access.USAGE_ACCESS, isSystem = false),
        ).audit()
        assertEquals(
            listOf("com.user", "com.unknown", "com.system"),
            audited.map { it.packageName },
        )
    }

    @Test
    fun `combinations stay short enough to be read`() {
        // A list that flags everything trains people to dismiss it - the same
        // lesson PackageCatalog learned about dependency warnings. If this ever
        // grows past a handful, the reason had better be very good.
        val everything = app(
            "com.everything",
            *Access.entries.toTypedArray(),
        )
        assertTrue(
            "too many combinations to read: ${everything.combinations().size}",
            everything.combinations().size <= 5,
        )
    }
}
