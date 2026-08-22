package com.wuyr.jdwp_injector.adb

import android.net.ssl.SSLSockets
import android.os.Build
import com.wuyr.jdwp_injector.adb.AdbClient.Companion.createSSLSocket
import com.wuyr.jdwp_injector.exception.PairingException
import com.wuyr.jdwp_injector.utils.HiddenApiExemptions
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-01-20 5:53 PM
 */
class AdbWirelessPairing(private val host: String, private val port: Int, private val pairingCode: String) : Closeable {

    private lateinit var socket: SSLSocket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private lateinit var spake2: Spake2

    fun start() {
        setupTlsConnection()
        val secretKey = doExchangeMessages()
        doExchangePeerInfo(secretKey)
    }

    private fun setupTlsConnection() {
        // Setup the secure transport
        socket = Socket(host, port).run {
            tcpNoDelay = true
            createSSLSocket(host, port)
        }.apply {
            this@AdbWirelessPairing.inputStream = DataInputStream(inputStream)
            this@AdbWirelessPairing.outputStream = DataOutputStream(outputStream)
        }
        // Setup the SPAKE2
        val keyingMaterial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SSLSockets.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
        } else {
            HiddenApiExemptions.doExemptions()
            Class.forName("com.android.org.conscrypt.Conscrypt").getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.java)
                .invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray
        }
        if (keyingMaterial == null) {
            throw PairingException("Unable to export keying material")
        } else {
            val password = ByteArray(pairingCode.length + keyingMaterial.size).apply {
                pairingCode.toByteArray().copyInto(this)
                keyingMaterial.copyInto(this, pairingCode.length)
            }
            spake2 = Spake2(password)
        }
    }

    private fun doExchangeMessages(): ByteArray {
        // Write our SPAKE2 msg
        outputStream.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply { put(1/*version*/).put(0/*spake2 message*/).putInt(spake2.message.size/*payload*/) }.array())
        outputStream.write(spake2.message)
        // Read the peer's SPAKE2 msg header
        val (version, type, payload) = ByteBuffer.wrap(ByteArray(6).apply { inputStream.readFully(this) }).order(ByteOrder.BIG_ENDIAN).run { Triple(get().toInt(), get().toInt(), int) }
        if (version != 1) {
            throw PairingException("header version mismatch (us=1 them=$version)")
        }
        if (payload == 0 || payload > 16384) {
            throw PairingException("header payload not within a safe payload size: $payload")
        }
        if (type != 0) {
            throw PairingException("header type mismatch (expected: 0 actual: $type")
        }
        // initialize the secretKey
        return spake2.processMessage(ByteArray(payload).apply { inputStream.readFully(this) })
    }

    private fun doExchangePeerInfo(secretKey: ByteArray) {
        // Prepare PeerInfo
        val peerInfoPayload = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN).apply {
            put(0).put(ByteArray(8191).apply {
                AdbClient.PUBLIC_KEY_AUTHORIZATION_DATA.copyInto(
                    this, 0, 0, AdbClient.PUBLIC_KEY_AUTHORIZATION_DATA.size.coerceAtMost(size)
                )
            })
        }.array()
        // Encrypt PeerInfo
        val encryptedPayload = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(peerInfoPayload)
        }
        // Write out the packet header
        outputStream.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply { put(1/*version*/).put(1/*peer info message*/).putInt(encryptedPayload.size/*payload*/) }.array())
        // Write out the encrypted payload
        outputStream.write(encryptedPayload)
        // Read in the peer's packet header
        val (version, type, payload) = ByteBuffer.wrap(ByteArray(6).apply { inputStream.readFully(this) }).order(ByteOrder.BIG_ENDIAN).run { Triple(get().toInt(), get().toInt(), int) }
        if (version != 1) {
            throw PairingException("header version mismatch (us=1 them=$version)")
        }
        if (payload == 0 || payload > 16384) {
            throw PairingException("header payload not within a safe payload size: $payload")
        }
        if (type != 1) {
            throw PairingException("header type mismatch (expected: 1 actual: $type")
        }
        // Read in the encrypted peer certificate
        val peersMessage = ByteArray(payload).apply { inputStream.readFully(this) }
        // Try to decrypt the certificate
        val decryptedPayload = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
                doFinal(peersMessage)
            } ?: throw PairingException("Unsupported payload while decrypting peer info.")
        }.getOrElse { throw PairingException("Failed to decrypt the certificate.") }
        // The decrypted message should contain the PeerInfo.
        if (decryptedPayload.size != 8192) {
            throw PairingException("The decrypted message does not contain the PeerInfo.")
        }
    }

    override fun close() {
        if (::socket.isInitialized) {
            socket.close()
        }
        if (::spake2.isInitialized) {
            spake2.destroy()
        }
    }
}