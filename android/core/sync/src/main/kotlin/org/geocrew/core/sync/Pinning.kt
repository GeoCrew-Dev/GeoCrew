package org.geocrew.core.sync

import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A server's certificate is not one the phone trusts: no certificate
 * authority vouches for it, and no key is pinned for the server. The user
 * checks its [ServerKey] with the leader before it is pinned (protocol v1 3.4).
 */
class Untrusted(cause: Throwable) : IOException("the server's certificate is not trusted", cause)

/**
 * The server's key is not the one pinned for it: it made a new key (moved
 * without its old one), or someone stands in between. Never taken on its own
 * (protocol v1 3.4); the new key [seen] is checked with the leader first.
 */
class KeyChanged(val seen: String) : IOException("the server's key is not the pinned one")

/** A server's certificate key: its [fingerprint], and whether a certificate authority vouches for it ([trusted]). */
data class ServerKey(val fingerprint: String, val trusted: Boolean)

/** Pinning a server's public key (ADR 0014, protocol v1 3.4). */
object Pins {
    /** The pin of [cert]: the SHA-256 of its public key (SubjectPublicKeyInfo, DER), as base64url without padding. */
    fun of(cert: X509Certificate): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    /** Connections to a server whose certificate has the key [pin], whatever else it says: its authority, dates and names. */
    internal fun pinned(pin: String): SSLContext = context(
        object : AnyServer() {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val seen = chain.firstOrNull()?.let(::of) ?: throw CertificateException("no certificate")
                if (seen != pin) throw Mismatch(seen)
            }
        },
    )

    /**
     * The key of the server at [base] (https), for the user to check with the
     * leader: one connection that takes any certificate, sends nothing and
     * notes whether the system would have trusted it.
     */
    fun keyOf(base: String, timeoutMs: Int = 15_000): ServerKey {
        val system = systemTrust()
        var key: ServerKey? = null
        val noting = object : AnyServer() {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                key = ServerKey(of(leaf), runCatching { system.checkServerTrusted(chain, authType) }.isSuccess)
            }
        }
        val c = URI(base.trimEnd('/') + "/api/v1/info").toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("not an https address")
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        var named = true
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.sslSocketFactory = context(noting).socketFactory
            c.setHostnameVerifier { host, session -> named = verifier.verify(host, session); true }
            c.connect()
        } finally {
            c.disconnect()
        }
        val k = key ?: throw IOException("the server showed no certificate")
        return k.copy(trusted = k.trusted && named)
    }

    /** The pin mismatch inside the handshake failure [e], if that is what it was. */
    internal fun mismatchIn(e: Throwable): Mismatch? {
        var t: Throwable? = e
        while (t != null) {
            if (t is Mismatch) return t
            t = t.cause
        }
        return null
    }

    internal class Mismatch(val seen: String) : CertificateException("the server's key is not the pinned one")

    private abstract class AnyServer : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("not for clients")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun context(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }

    private fun systemTrust(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Whether [e] is the certificate, or the name in it, not being trusted: not a connection cut short. */
    internal fun isTrustFailure(e: IOException): Boolean =
        e is SSLPeerUnverifiedException || (e is SSLException && generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException })
}
