package cl.villagranquiroz.ohm_launcher

import java.security.Signature
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxIdentityTest {
    private val id = "1234567890abcdef1234567890abcdef"

    @Test fun generatedCertificateSignsWithItsSoftwareKeyAndNamesTheDevice() {
        val material = FluxIdentity.createMaterial(id)
        assertTrue(material.certificate.subjectX500Principal.name.split(',').any { it == "CN=$id" })
        material.certificate.verify(material.certificate.publicKey)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(material.privateKey)
        signature.update("flux handshake".toByteArray())
        val signed = signature.sign()
        signature.initVerify(material.certificate.publicKey)
        signature.update("flux handshake".toByteArray())
        assertTrue(signature.verify(signed))
        assertEquals("RSA", material.privateKey.algorithm)
        assertEquals(2048, (material.certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength())
    }

    @Test fun softwareCertificateCompletesMutualTls12Handshake() {
        val context = FluxIdentity.createTlsContext(FluxIdentity.createMaterial(id))
        val server = context.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf("TLSv1.2")
        val serverError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                (server.accept() as javax.net.ssl.SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    assertEquals("CN=$id", (socket.session.peerCertificates[0] as java.security.cert.X509Certificate)
                        .subjectX500Principal.name.split(',')[0])
                }
            } catch (error: Throwable) { serverError.set(error) }
        }.apply { start() }
        try {
            try {
                (context.socketFactory.createSocket("127.0.0.1", server.localPort) as javax.net.ssl.SSLSocket).use { socket ->
                    socket.enabledProtocols = arrayOf("TLSv1.2")
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    assertEquals(id, (socket.session.peerCertificates[0] as java.security.cert.X509Certificate)
                        .subjectX500Principal.name.split(',')[0].removePrefix("CN="))
                }
            } catch (error: Throwable) {
                worker.join(5000)
                serverError.get()?.let(error::addSuppressed)
                throw error
            }
            worker.join(5000)
            assertTrue("TLS server timed out", !worker.isAlive)
            serverError.get()?.let { throw AssertionError("TLS server handshake failed", it) }
        } finally { server.close() }
    }

    @Test fun encryptedIdentityRoundTripsAndBindsDeviceId() {
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val material = FluxIdentity.createMaterial(id)
        val sealed = FluxIdentity.sealMaterial(id, material, wrappingKey)
        assertTrue(!sealed.contentEquals(material.privateKey.encoded))
        val restored = FluxIdentity.openMaterial(id, sealed, wrappingKey)
        assertArrayEquals(material.certificate.encoded, restored.certificate.encoded)
        assertArrayEquals(material.privateKey.encoded, restored.privateKey.encoded)
        assertThrows(Exception::class.java) { FluxIdentity.openMaterial("abcdef1234567890abcdef1234567890", sealed, wrappingKey) }
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { FluxIdentity.openMaterial(id, sealed, wrappingKey) }
    }
}
