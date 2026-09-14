package com.bulwark.app.security

import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Certificate authorities somebody added to this phone.
 *
 * A root CA the phone did not ship with can vouch for any site. Whoever holds
 * its private key can sit between the phone and the internet and read traffic
 * that would otherwise be private, without anything looking wrong. They arrive
 * through workplace enrolment, a "free VPN" app, a debugging proxy, or one tap
 * years ago that nobody remembers.
 *
 * Almost no phone surfaces this. Android has a screen for it, buried three
 * levels down under a name - "Trusted credentials" - that means nothing to the
 * person it protects.
 *
 * ## Why this needs no Shizuku
 *
 * `AndroidCAStore` is readable by any app, and its aliases are prefixed
 * `system:` or `user:`, which is exactly the distinction that matters.
 * Measured on stock Android 15, 2026-09-14: 145 aliases, all `system:`, until
 * a certificate was added and a 146th appeared as `user:`.
 *
 * That matters for more than convenience. This is the one audit Bulwark can
 * show on first launch, before anyone has been asked to set anything up - and
 * "start Shizuku to find out whether someone is reading your traffic" is a
 * sentence most people would never get past.
 *
 * ## Why there is no remove button
 *
 * **Bulwark cannot remove these, and neither can Shizuku.** Measured the same
 * day: at uid 2000 `/data/misc/user/0/cacerts-added/` cannot even be listed,
 * let alone written - `Permission denied`. Only Settings can do it.
 *
 * So this reports and points at the screen that can act.
 * `layers/02-permissions/revoke.md`: a control that cannot achieve anything is
 * not offered, because a button that spends someone's attention to reach a
 * refusal is worse than no button.
 */
object AddedCertificates {

    /** One certificate a person added, said in the terms a row needs. */
    data class Added(
        /** Common name where there is one, else the whole subject. */
        val label: String,
        /** Who vouches for it. Equal to [label] when it vouches for itself. */
        val issuedBy: String,
        /** Expiry, epoch millis. */
        val expiresAt: Long,
        /** Signed by itself, which is what a self-made interception root looks like. */
        val selfSigned: Boolean,
    )

    /**
     * Every `user:` entry in the phone's trust store.
     *
     * Throws nothing on an odd entry: one unreadable certificate must not cost
     * the whole reading, or a single malformed file hides the rest.
     */
    fun read(): List<Added> {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return store.aliases().toList()
            .filter { it.startsWith(USER_PREFIX) }
            .mapNotNull { alias ->
                runCatching {
                    val cert = store.getCertificate(alias) as? X509Certificate ?: return@runCatching null
                    Added(
                        label = commonName(cert.subjectX500Principal.name),
                        issuedBy = commonName(cert.issuerX500Principal.name),
                        expiresAt = cert.notAfter.time,
                        selfSigned = cert.subjectX500Principal == cert.issuerX500Principal,
                    )
                }.getOrNull()
            }
    }

    /**
     * `CN` out of an X.500 name, or the whole thing if it has none.
     *
     * Never returns empty: a row with no label is a row nobody can act on, and
     * the raw name - ugly as it is - at least identifies the certificate.
     */
    fun commonName(x500: String): String {
        val cn = x500.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
        return cn?.takeIf { it.isNotBlank() } ?: x500.ifBlank { "Unnamed certificate" }
    }

    /**
     * The card's headline.
     *
     * **Empty is genuinely good news here, and says so** - unlike the hidden
     * switches card, where nothing found means the read is suspect because
     * every phone has apps holding those. The opposite is true of this store:
     * most phones have no added certificate at all, so zero is the ordinary,
     * correct answer and dressing it up as inconclusive would teach people to
     * ignore the card on the day it finds something.
     */
    fun headline(added: List<Added>): String {
        if (added.isEmpty()) {
            return "Nobody has added a certificate authority to this phone."
        }
        val n = if (added.size == 1) "1 certificate authority has" else "${added.size} certificate authorities have"
        return "$n been added to this phone."
    }

    /**
     * The line under the headline. Null when there is nothing to qualify.
     *
     * Carries the limit as prominently as the risk. A root CA is **not** proof
     * that traffic is being read: since Android 7 an app built for API 24 or
     * later ignores added certificates unless it opts in, so in practice this
     * reaches browsers and apps that chose to trust them, not everything on the
     * phone. Saying "someone can read all your traffic" would be the easy,
     * frightening sentence and `threat-model.md` forbids it.
     */
    fun detail(added: List<Added>): String? {
        if (added.isEmpty()) return null
        return "A certificate authority can vouch for any website, so whoever " +
            "added it may be able to read traffic that would otherwise be " +
            "private. It is not proof that anyone is: apps built for Android 7 " +
            "and later ignore added certificates unless they choose to trust " +
            "them, so this usually reaches browsers rather than every app. A " +
            "work phone often has one for an ordinary reason. Bulwark cannot " +
            "remove these - only Android's own settings can."
    }

    /** One row, in a sentence. [now] decides only whether it has expired. */
    fun line(cert: Added, now: Long): String = buildString {
        append(if (cert.selfSigned) "Vouches for itself" else "Issued by ${cert.issuedBy}")
        append(if (cert.expiresAt < now) ", and has expired" else "")
        append(".")
    }

    private const val USER_PREFIX = "user:"
}
