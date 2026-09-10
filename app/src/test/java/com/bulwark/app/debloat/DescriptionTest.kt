package com.bulwark.app.debloat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The description must not hide what breaks.
 *
 * Bulwark showed only the first line of a UAD description under a green badge.
 * For `com.google.android.as.oss` the first line reads "On-device behavior
 * analysis" - which sounds like something to remove - while lines two and three
 * say it enables live caption and is a dependency of System Intelligence.
 *
 * SAI caught it by knowing the package better than the summary did. That is
 * not a truncation bug; it is the app misrepresenting its own source, which
 * `safety-rules.md` honesty rules forbid.
 */
class DescriptionTest {

    private val asOss = """
        Private Compute Services. On-device behavior analysis
        Enables live caption, music recognition and smart replies.
        Seems to be a dependency of System Intelligence.
        https://play.google.com/store/apps/details?id=com.google.android.as.oss
    """.trimIndent()

    @Test
    fun `keeps the lines that say what breaks`() {
        val text = asOss.readableDescription()
        assertTrue("must keep the feature list: $text", text.contains("live caption"))
        assertTrue("must keep the dependency warning: $text", text.contains("dependency of System Intelligence"))
    }

    @Test
    fun `drops URLs, which are unreachable from an app with no internet`() {
        assertFalse(asOss.readableDescription().contains("http"))
    }

    @Test
    fun `joins into one readable run rather than a wall of newlines`() {
        assertFalse(asOss.readableDescription().contains("\n"))
    }

    @Test
    fun `a single-line description survives unchanged`() {
        assertTrue("FederatedCompute.".readableDescription() == "FederatedCompute.")
    }

    @Test
    fun `a description that is only a URL comes back empty, not blank-looking`() {
        assertTrue("https://example.com".readableDescription().isEmpty())
    }
}
