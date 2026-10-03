package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Software RSA TLS identity sealed by a non-exportable AndroidKeyStore AES key; exact DER peer pins. */
internal class FluxIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("ohm_flux_identity", Context.MODE_PRIVATE)
    private val trust = context.getSharedPreferences("ohm_flux_trust", Context.MODE_PRIVATE)
    private val alias = "ohm-flux-wrap-v3"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val certificate: X509Certificate
    val deviceId: String
    private val key: PrivateKey

    init {
        val saved = prefs.getString("sealedMaterialV3", null)
        val savedId = prefs.getString("deviceIdV3", null)
        // An interrupted or restored identity must never silently replace a previously pinned certificate.
        check((saved == null) == (savedId == null)) { "Incomplete Flux identity; reset app data" }
        if (saved != null) check(store.containsAlias(alias)) { "Flux wrapping key unavailable; reset app data" }
        val wrappingKey = if (store.containsAlias(alias)) store.getKey(alias, null) as SecretKey else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySize(256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        val material: Material
        if (saved != null) {
            deviceId = requireNotNull(savedId)
            require(FluxWire.validId(deviceId))
            material = openMaterial(deviceId, Base64.decode(saved, Base64.NO_WRAP), wrappingKey)
        } else {
            deviceId = UUID.randomUUID().toString().replace("-", "")
            material = createMaterial(deviceId)
            val sealed = sealMaterial(deviceId, material, wrappingKey)
            check(prefs.edit().putString("deviceIdV3", deviceId)
                .putString("sealedMaterialV3", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
        }
        certificate = material.certificate
        key = material.privateKey
    }

    fun pinned(id: String): ByteArray? = trust.getString(id, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    fun pin(id: String, cert: X509Certificate) {
        require(FluxWire.validId(id))
        check(trust.edit().putString(id, Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).commit())
    }
    fun forget(id: String) { check(trust.edit().remove(id).commit()) }

    /** Pairing remains gated by FluxSession's secured identity, CN match, and exact DER pin checks. */
    fun tlsContext(): SSLContext = createTlsContext(Material(key, certificate))

    internal data class Material(val privateKey: PrivateKey, val certificate: X509Certificate)

    companion object {
        internal fun createTlsContext(material: Material): SSLContext = SSLContext.getInstance("TLSv1.2").apply {
        val alias = "flux"
        init(arrayOf(object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
            override fun getCertificateChain(alias: String?) = arrayOf(material.certificate)
            override fun getPrivateKey(alias: String?) = material.privateKey
        }), arrayOf(object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?, socket: Socket?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?, socket: Socket?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?, engine: SSLEngine?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?, engine: SSLEngine?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), SecureRandom())
    }

        internal fun createMaterial(id: String): Material {
            require(FluxWire.validId(id))
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Principal("CN=$id,OU=KDE Connect,O=KDE")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date(now - 86_400_000L), Date(now + 315_360_000_000L), name, pair.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            val cert = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
            cert.verify(pair.public)
            return Material(pair.private, cert)
        }

        /** Version byte, fresh 96-bit GCM nonce, authenticated encrypted PKCS#8 and X.509 DER. */
        internal fun sealMaterial(id: String, material: Material, wrappingKey: SecretKey): ByteArray {
            require(FluxWire.validId(id))
            val clear = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use { data ->
                    val privateBytes = material.privateKey.encoded
                    try {
                        val certBytes = material.certificate.encoded
                        data.writeInt(privateBytes.size)
                        data.write(privateBytes)
                        data.writeInt(certBytes.size)
                        data.write(certBytes)
                    } finally { privateBytes.fill(0) }
                }
            }.toByteArray()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
                val iv = cipher.iv
                require(iv.size == 12) { "Unexpected AES-GCM IV length" }
                return byteArrayOf(1) + iv + cipher.doFinal(clear)
            } finally { clear.fill(0) }
        }

        internal fun openMaterial(id: String, sealed: ByteArray, wrappingKey: SecretKey): Material {
            require(FluxWire.validId(id) && sealed.size in 30..16_384 && sealed[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            cipher.updateAAD(id.toByteArray(Charsets.US_ASCII))
            val clear = cipher.doFinal(sealed, 13, sealed.size - 13)
            try {
                val input = DataInputStream(ByteArrayInputStream(clear))
                val keySize = input.readInt()
                require(keySize in 1..8192)
                val keyBytes = ByteArray(keySize).also { input.readFully(it) }
                val certSize = input.readInt()
                require(certSize in 1..8192)
                val certBytes = ByteArray(certSize).also { input.readFully(it) }
                require(input.available() == 0)
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                keyBytes.fill(0)
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                require(cert.subjectX500Principal.name.split(',').any { it == "CN=$id" })
                cert.verify(cert.publicKey)
                val proof = Signature.getInstance("SHA256withRSA")
                proof.initSign(privateKey)
                proof.update(id.toByteArray(Charsets.US_ASCII))
                val signature = proof.sign()
                proof.initVerify(cert.publicKey)
                proof.update(id.toByteArray(Charsets.US_ASCII))
                require(proof.verify(signature)) { "Flux certificate does not match private key" }
                return Material(privateKey, cert)
            } finally { clear.fill(0) }
        }
    }
}
