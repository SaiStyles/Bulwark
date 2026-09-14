package com.bulwark.app.security

import com.bulwark.app.security.AddedCertificates.Added
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy and the classification, without a device.
 *
 * The reading itself needs a real trust store and is proven by
 * `CaStoreProbe`; what is testable here is everything that decides what a
 * person is told, which is where this feature can actually do harm.
 */
class AddedCertificatesTest {

    private companion object {
        const val NOW = 1_757_800_000_000L
        val PROXY = Added(
            label = "Bulwark Test Interception CA",
            issuedBy = "Bulwark Test Interception CA",
            expiresAt = NOW + 86_400_000L,
            selfSigned = true,
        )
    }

    // -- the honesty rules ---------------------------------------------------

    /**
     * The sentence this feature exists to avoid.
     *
     * "Someone can read all your traffic" is the frightening version and it is
     * false: since Android 7 an app targeting API 24+ ignores user-added
     * certificates unless it opts in.
     */
    @Test
    fun `the detail never claims all traffic is being read`() {
        val detail = AddedCertificates.detail(listOf(PROXY))!!
        listOf("all your traffic", "everything you do", "is reading", "is intercepting")
            .forEach { assertTrue("must not claim \"$it\": $detail", !detail.contains(it, true)) }
        assertTrue("must carry the Android 7 limit", detail.contains("Android 7"))
        assertTrue("must say it is not proof", detail.contains("not proof"))
    }

    /** And it must not offer what it cannot do. */
    @Test
    fun `the detail says Bulwark cannot remove them`() {
        val detail = AddedCertificates.detail(listOf(PROXY))!!
        assertTrue(detail, detail.contains("cannot remove"))
    }

    /**
     * The opposite of the hidden switches card, deliberately.
     *
     * There, nothing found means the read is suspect. Here most phones really
     * do have none, so hedging would train people to ignore the card on the
     * day it matters.
     */
    @Test
    fun `an empty store is stated as good news, not as doubt`() {
        val headline = AddedCertificates.headline(emptyList())
        assertEquals("Nobody has added a certificate authority to this phone.", headline)
        assertNull("nothing to qualify when there is nothing", AddedCertificates.detail(emptyList()))
    }

    // -- counting and rows ---------------------------------------------------

    @Test
    fun `one and many are both said in plain English`() {
        assertTrue(AddedCertificates.headline(listOf(PROXY)).startsWith("1 certificate authority has"))
        assertTrue(
            AddedCertificates.headline(listOf(PROXY, PROXY, PROXY))
                .startsWith("3 certificate authorities have"),
        )
    }

    @Test
    fun `a self-signed certificate is named as vouching for itself`() {
        assertEquals("Vouches for itself.", AddedCertificates.line(PROXY, NOW))
    }

    @Test
    fun `one signed by something else names the issuer`() {
        val fromMdm = PROXY.copy(selfSigned = false, issuedBy = "Example Corp Root")
        assertEquals("Issued by Example Corp Root.", AddedCertificates.line(fromMdm, NOW))
    }

    /** An expired root still sits in the store and is still worth saying. */
    @Test
    fun `an expired certificate says so`() {
        val old = PROXY.copy(expiresAt = NOW - 1)
        assertTrue(AddedCertificates.line(old, NOW).contains("has expired"))
    }

    // -- the label -----------------------------------------------------------

    @Test
    fun `the common name is pulled out of an X500 name`() {
        assertEquals(
            "Bulwark Test Interception CA",
            AddedCertificates.commonName("C=GB,O=Bulwark Test,CN=Bulwark Test Interception CA"),
        )
    }

    /**
     * A row with no label cannot be acted on, so the raw name is used rather
     * than an empty string or a cheerful placeholder.
     */
    @Test
    fun `a name with no CN falls back to the whole subject`() {
        assertEquals("O=Nameless,C=GB", AddedCertificates.commonName("O=Nameless,C=GB"))
        assertEquals("Unnamed certificate", AddedCertificates.commonName(""))
    }
}
