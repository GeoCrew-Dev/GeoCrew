package org.geocrew.core.sync

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A server with a certificate of its own, as the GeoCrew server makes (ADR 0014): its key made here with the JDK's keytool.
class PinningTest {
    private val dir = createTempDirectory("pinning").toFile()
    private val password = "test-only".toCharArray()
    private val store = KeyStore.getInstance("PKCS12").apply {
        val file = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
            "-dname", "CN=GeoCrew Server", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", String(password),
        ).redirectErrorStream(true).start()
        check(made.waitFor() == 0) { made.inputStream.bufferedReader().readText() }
        file.inputStream().use { load(it, password) }
    }
    private val pin = Pins.of(store.getCertificate("server") as X509Certificate)
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) })
        createContext("/") { exchange ->
            val body = """{"protocol": 1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    // By its address, not "localhost": the certificate names no host, and a pinned key needs none.
    private val base = "https://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun theKeyIsShownToBeCheckedAndNotTrustedOnItsOwn() {
        assertEquals(43, pin.length)
        assertEquals(ServerKey(pin, trusted = false), Pins.keyOf(base))
        assertFailsWith<Untrusted> { HttpTransport(base).send("GET", "/api/v1/info", null, null) }
    }

    @Test
    fun thePinnedKeyIsTakenWhateverTheNameAndAnotherIsRefused() {
        val reply = HttpTransport(base, pin).send("GET", "/api/v1/info", null, null)
        assertEquals(200, reply.status)
        assertTrue("protocol" in reply.body)
        val other = "A".repeat(43)
        val changed = assertFailsWith<KeyChanged> { HttpTransport(base, other).send("GET", "/api/v1/info", null, null) }
        assertEquals(pin, changed.seen, "the new key, to check with the leader")
    }

    @Test
    fun aPinNeedsHttps() {
        val e = assertFailsWith<java.io.IOException> { HttpTransport("http://127.0.0.1:${server.address.port}", pin).send("GET", "/", null, null) }
        assertFalse(e is KeyChanged)
    }
}
