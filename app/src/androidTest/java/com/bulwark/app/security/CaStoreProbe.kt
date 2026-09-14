package com.bulwark.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * [AddedCertificates] against a real trust store.
 *
 * The pure half - the copy, the counting, the label - is covered without a
 * device in `AddedCertificatesTest`. What only a device can answer is whether
 * `AndroidCAStore` really separates what shipped with the phone from what
 * somebody added, and whether it does so **without any privilege at all**,
 * which is the entire argument for this feature sitting above the rest of the
 * Audit screen.
 *
 * Measured on stock Android 15, 2026-09-14: 145 aliases, every one `system:`,
 * until a self-signed root was placed in `/data/misc/user/0/cacerts-added/`
 * and a 146th appeared as `user:`.
 */
@RunWith(AndroidJUnit4::class)
class CaStoreProbe {

    /**
     * The assertion that holds on every device: the reader returns the added
     * certificates and **not** the ~145 that shipped with the phone.
     *
     * A reader that returned everything would still look plausible on screen -
     * a long list, all real - while telling a person their phone is full of
     * certificates somebody added. That is the failure this pins.
     */
    @Test
    fun theReaderReturnsOnlyWhatSomebodyAdded() {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = store.aliases().toList()
        val system = aliases.count { it.startsWith("system:") }
        val user = aliases.count { it.startsWith("user:") }

        assertTrue("a phone with no system CAs means the store did not load", system > 0)

        val read = AddedCertificates.read()
        assertTrue(
            "read() returned ${read.size} for $user added and $system system - " +
                "if this equals the total, it is returning the whole store",
            read.size == user,
        )
        assertTrue("must not be returning the system store", read.size < system)
    }

    /**
     * Needs a device that actually has one, so it is an assumption rather than
     * an assertion - the same rule the emulator taught on 2026-09-14. A phone
     * with an empty trust store is the ordinary, healthy case.
     */
    @Test
    fun anAddedCertificateIsReadWithItsLabelAndIssuer() {
        val read = AddedCertificates.read()
        assumeTrue(
            "SKIPPED: nothing has been added to this phone's trust store, which " +
                "is the ordinary case and cannot prove the parse.",
            read.isNotEmpty(),
        )

        read.forEach { cert ->
            assertTrue("every row needs a label a person can act on", cert.label.isNotBlank())
            assertTrue("every row needs an issuer", cert.issuedBy.isNotBlank())
            assertTrue("an expiry of zero means notAfter was not read", cert.expiresAt > 0)
        }
    }
}
