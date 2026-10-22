package io.github.tuthan.stationar.receiver

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tuthan.stationar.core.stream.Pairing
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The receiver's TLS: an EC P-256 key that never leaves the Station's keystore (`AndroidKeyStore`, made on first
 * use), its self-signed certificate, and a server socket that speaks TLS 1.3 only. Laptops pin the certificate's
 * SHA-256 ([fingerprint]) when they pair.
 */
object Tls {
    private const val ALIAS = "stationar-receiver"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS signs a digest it made itself (NONE); SHA-256 lets the keystore sign the certificate.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Station AR"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 30 * 365 * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun certificate(ks: KeyStore): X509Certificate {
        ensureKey(ks)
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    /** The SHA-256 of the receiver's certificate, lower-case hex (what the sender pins). */
    fun fingerprint(): String = Pairing.hex(java.security.MessageDigest.getInstance("SHA-256").digest(certificate(keyStore()).encoded))

    /** A TLS 1.3 server socket on `port`, all interfaces. */
    fun serverSocket(port: Int): SSLServerSocket {
        val ks = keyStore()
        val cert = certificate(ks)
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(KeyManager(key, cert)), null, null)
        val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), 8)
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = false
        return server
    }

    /** Hands TLS the one key and certificate, whatever it asks for. */
    private class KeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(cert)
        override fun getPrivateKey(alias: String?) = key
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
