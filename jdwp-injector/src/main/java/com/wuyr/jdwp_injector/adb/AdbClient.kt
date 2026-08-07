package com.wuyr.jdwp_injector.adb

import com.wuyr.jdwp_injector.exception.AdbCommunicationException
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread


/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2023-04-23 下午12:26
 *
 * 在手机通过数据线执行adb tcpip 5555之后，会在主机端（即手机本身）开放5555这个端口，可以通过socket直接连接
 * 但是你连接之后，还不能直接发送shell命令，还要先发送请求连接的消息，然后进行授权认证，它有这几个类型的消息：
 * CONNECTION（发出去这个消息，就是请求连接；如果收到这个消息，就是主机端（手机）接受了我们的连接请求）
 * AUTHORIZATION（这个是主机端发给我们的，表示现在需要验证客户端的身份）
 * OKAY（如果由客户端发出，表示已接收到主机端的消息；反之就是主机端表示收到客户端消息）
 * CLOSE（如果由客户端发出，就是请求断开连接（不过我昨天测试没有收到过主机端发过来这个消息））
 * WRITE（表示此消息附带二进制数据流，如果由主机端发出，客户端收到后需回复OKAY，反之亦然）
 * TLS（这个是android11新加入的，通过系统设置里面自带的无线调试功能中的ip和端口连接socket，就会返回这个类型的消息，这个消息的特点是不需要AUTHORIZATION，Handshake成功之后就可以直接通讯，但前提是需要先配对成功）
 * SYNC（没有用到这个，adb协议源码里写着已过时 http://aospxref.com/android-11.0.0_r21/xref/system/core/adb/protocol.txt#192）
 *1
 * 消息体里面包含：
 * 消息类型、
 * 附加参数1、
 * 附加参数2、
 * 字节数据流长度（如果>0表示有附加的字节流数据）、
 * 字节流数据checksum（用于校验完整性）、
 * magic（用于校验消息类型有效性）、
 * 字节流数据（一般WRITE类型的消息都会附带这个数据）
 *
 * 连接和通讯的流程：
 * 首先你要创建一个密钥对（等下验证需要用到），首次连接可以创建一个新的，然后保存下来以下次使用。
 * 在socket连接成功之后，你（即客户端）需要发送CONNECT消息，然后等待回复；
 * 正常情况，主机端会返回携带有二进制流数据的AUTHORIZATION消息，表示需要进行授权认证，你收到这个消息之后，需要用你刚刚创建的私钥通过RSA加密，然后回复给主机端，消息类型同样是AUTHORIZATION。
 * 当主机端收到你发过来的数据之后，如果检测到你这个密钥之前没有认证过，会继续发一个AUTHORIZATION消息给你，你收到之后，就要把公钥的数据用二进制流的方式发送过去了（消息体类型依然是AUTHORIZATION）。
 * 主机收到你这个公钥数据之后，就会弹出“是否同意xxxx进行usb调试”的对话框，这个阶段会一直等待用户做选择，当用户按下“确认”，主机端会发送CONNECTION消息给你，表示成功建立adb连接（注：在Android10版本(通过adb tcpip开放无线端口)，用户按下“确认”按钮后，客户端并不会收到CONNECTION消息，这时候就只能重新创建一个新socket连接）。当然，如果用户在上一次弹出“是否同意xxxx进行usb调试”的时候勾选“记住”，主机端就不会发两次AUTHORIZATION消息给你，而是在你第一次回复AUTHORIZATION（携带私钥加密数据的时候）就返回CONNECTION（连接成功了）
 *
 * 成功建立连接之后，还不能直接发送shell命令，需要先发送一个OPEN消息，等主机回复OKAY（这条消息会携带一个remote id，你需要保存起来）之后，你这边再回复一个OKAY（需要携带local id（可以为任意正数），和刚刚它传给你的remote id（表示要跟哪个主机通讯））给主机。接下来就可以进行常规的发送shell命令和获取命令执行结果了。
 *
 * 流程概括：
 * 例子，打开shell之后执行ls命令：
 * 客户端----->CONNECT
 *                                 AUTHORIZATION<-----主机端
 * 客户端----->AUTHORIZATION（携带私钥加密数据）
 *                                 （如果此密钥没有认证过）AUTHORIZATION<-----主机端
 * 客户端----->AUTHORIZATION（携带公钥数据）
 *                                 （密钥认证过）CONNECTION<-----主机端
 * 至此，连接成功建立
 * 客户端----->OPEN
 *                                 OKAY<-----主机端
 * 客户端----->OKAY
 * 至此，连接打开shell
 * 客户端----->WRITE（携带ls命令的byte[]数据流）
 *                                 OKAY<-----主机端
 *                                 （如有内容输出）WRITE<-----主机端
 * 客户端----->OKAY
 *  至此，一次shell命令处理完毕
 */
class AdbClient private constructor(host: String, port: Int) : Closeable {

    private var socket: Socket
    private var inputStream: DataInputStream
    private var outputStream: OutputStream
    private var connected = false
    private var localId = 1
    private var remoteId = 0

    init {
        // A bare Socket(host, port) has NO connect timeout and NO read timeout, so
        // a port that accepts TCP but never speaks adb (very common when the port
        // scanner probes every open port) made waitForResponse() block forever.
        // Because connectAdb/openShell are @Synchronized on the companion, that
        // one stuck thread deadlocked every later adb call in the whole app.
        socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            this@AdbClient.inputStream = DataInputStream(inputStream)
            this@AdbClient.outputStream = outputStream
        }
    }

    /** Removes the handshake read timeout once the adb connection is up. */
    internal fun clearHandshakeReadTimeout() {
        runCatching { socket.soTimeout = 0 }
    }

    private fun openShell() {
        sendMessage(CMD_OPEN, "shell:", closeWhenFailed = true)
    }

    fun sendCommand2jdwp(vararg data: Byte): ByteArray {
        var responsePayload = sendMessage(CMD_WRITE, *data) ?: throw AdbCommunicationException("failed to send command")
        if (responsePayload.isEmpty()) return responsePayload
        val totalLength = (responsePayload[0].toInt() and 0xff) shl 24 or ((responsePayload[1].toInt() and 0xff) shl 16) or
                ((responsePayload[2].toInt() and 0xff) shl 8) or (responsePayload[3].toInt() and 0xff)
        while (responsePayload.size < totalLength) {
            responsePayload += waitForResponse().third
            sendOKMessage()
        }
        return responsePayload
    }

    fun pollJdwpMessage(): ByteArray {
        val response = waitForResponse()
        var responsePayload = when {
            response.first == CMD_OKAY -> waitForResponse().third.also { sendOKMessage() }
            response.third.isEmpty() -> waitForResponse().third.also { sendOKMessage() }
            response.first == CMD_WRITE -> response.third.also { sendOKMessage() }
            else -> return ByteArray(0)
        }
        if (responsePayload.isEmpty()) {
            return ByteArray(0)
        }
        val totalLength = (responsePayload[0].toInt() and 0xff) shl 24 or ((responsePayload[1].toInt() and 0xff) shl 16) or
                ((responsePayload[2].toInt() and 0xff) shl 8) or (responsePayload[3].toInt() and 0xff)
        while (responsePayload.size < totalLength) {
            responsePayload += waitForResponse().also { sendOKMessage() }.third
        }
        return responsePayload
    }

    private fun forward2jdwp(targetPid: Int, connectTimeout: Long = CONNECT_TIMEOUT): Boolean {
        var jdwpConnected = false
        val timeoutThread = thread {
            runCatching {
                if (!jdwpConnected) {
                    synchronized(LOCK) {
                        if (!jdwpConnected) {
                            LOCK.wait(connectTimeout)
                        }
                    }
                    if (!jdwpConnected) {
                        close()
                    }
                }
            }
        }
        runCatching {
            sendMessage(CMD_OPEN, "jdwp:$targetPid", false) ?: throw AdbCommunicationException("failed to connect target process: $targetPid")
            sendMessage(CMD_WRITE, "JDWP-Handshake") ?: throw AdbCommunicationException("failed to handshake with target process: $targetPid")
            jdwpConnected = true
            timeoutThread.interrupt()
        }.onFailure {
            throw SocketTimeoutException("time out to connect jdwp")
        }
        return true
    }

    private fun sendMessage(cmd: Int, content: String, receiveResponse: Boolean = true, closeWhenFailed: Boolean = false) =
        sendMessage(cmd, *content.toByteArray(), receiveResponse = receiveResponse, closeWhenFailed = closeWhenFailed)

    private fun sendMessage(cmd: Int, vararg data: Byte, receiveResponse: Boolean = true, closeWhenFailed: Boolean = false): ByteArray? {
        write2socket(generateMessage(cmd, localId, remoteId, data))
        return pollMessage(receiveResponse, closeWhenFailed)
    }

    private fun pollMessage(receiveResponse: Boolean = true, closeWhenFailed: Boolean = false): ByteArray? {
        val response = waitForResponse()
        if (response.first == CMD_OKAY) {
            remoteId = response.second
            return if (receiveResponse) waitForResponse().third.also { sendOKMessage() } else response.third
        } else if (closeWhenFailed) {
            write2socket(generateMessage(CMD_CLOSE, localId, response.second))
            close()
        }
        return null
    }

    private fun sendOKMessage() = write2socket(generateMessage(CMD_OKAY, localId, remoteId))

    fun sendShellCommand(command: String, waitForResponse: Boolean = true) = StringBuilder().apply {
        append(String(sendMessage(CMD_WRITE, *command.toByteArray(), 13, closeWhenFailed = true) ?: return@apply))
        while (waitForResponse && ((!endsWith(" $ ") && !endsWith(" # ")) || inputStream.available() > 0)) {
            append(String(waitForResponse().third))
            sendOKMessage()
        }
    }.toString()

    override fun close() {
        if (!socket.isClosed) {
            inputStream.close()
            outputStream.close()
            socket.close()
        }
        connected = false
    }

    companion object {
        private const val CMD_CONNECTION = 0x4e584e43
        private const val CMD_AUTHORIZATION = 0x48545541
        private const val CMD_TLS = 0x534C5453
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLOSE = 0x45534c43
        private const val CMD_WRITE = 0x45545257
        private const val CMD_OPEN = 0x4e45504f

        private val MSG_CONNECT = byteArrayOf(67, 78, 88, 78, 0, 0, 0, 1, 0, 16, 0, 0, 7, 0, 0, 0, 50, 2, 0, 0, -68, -79, -89, -79, 104, 111, 115, 116, 58, 58, 0)
        private val MSG_TLS_VERSION = byteArrayOf(83, 84, 76, 83, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -84, -85, -77, -84)

        private const val CONNECT_TIMEOUT = 2_000L

        /** TCP connect timeout. Without this a dead host hangs for the OS default. */
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3_000

        /**
         * Read timeout applied while establishing/handshaking only. Cleared once
         * the adb connection is up so long-lived shell / JDWP reads are unaffected.
         */
        private const val HANDSHAKE_READ_TIMEOUT_MS = 5_000
        private val LOCK = Object()

        @JvmStatic
        @Synchronized
        fun openShell(host: String, port: Int, connectTimeout: Long = CONNECT_TIMEOUT, maxRetryCount: Int = 3): AdbClient {
            return connectAdb(host, port, connectTimeout, maxRetryCount).apply { openShell() }
        }

        @JvmStatic
        @Synchronized
        fun connect2jdwp(host: String, port: Int, targetPid: Int, connectTimeout: Long = CONNECT_TIMEOUT, maxRetryCount: Int = 3): AdbClient {
            return connectAdb(host, port, connectTimeout, maxRetryCount).apply { forward2jdwp(targetPid, connectTimeout) }
        }

        @JvmStatic
        @Synchronized
        fun connectAdb(host: String, port: Int, timeout: Long = CONNECT_TIMEOUT, maxRetryCount: Int = 3): AdbClient {
            return runCatching {
                AdbClient(host, port).apply {
                    write2socket(MSG_CONNECT)
                    val response = waitForResponse()
                    if (response.first == CMD_TLS) {
                        write2socket(MSG_TLS_VERSION)
                        socket = socket.createSSLSocket(host, port)
                        inputStream = DataInputStream(socket.inputStream)
                        outputStream = socket.outputStream
                        runCatching { connected = waitForResponse().first == CMD_CONNECTION }.onFailure { e ->
                            if (e is SSLProtocolException) {
                                throw SSLHandshakeException("handshake failed, wireless pairing is required!")
                            }
                        }
                    } else {
                        write2socket(generateMessage(CMD_AUTHORIZATION, 2, 0, response.third.signByPrivateKey()))
                        connected = waitForResponse().first == CMD_CONNECTION
                        if (!connected) {
                            write2socket(generateMessage(CMD_AUTHORIZATION, 3, 0, PUBLIC_KEY_AUTHORIZATION_DATA))
                            var authorizationResponse = -1
                            val thread = thread {
                                runCatching { authorizationResponse = waitForResponse().first }.onSuccess {
                                    synchronized(LOCK) {
                                        LOCK.notifyAll()
                                    }
                                }
                            }
                            if (authorizationResponse == -1) {
                                synchronized(LOCK) {
                                    if (authorizationResponse == -1) {
                                        LOCK.wait(timeout)
                                    }
                                }
                            }
                            if (authorizationResponse == -1) {
                                thread.interrupt()
                                close()
                                throw SocketTimeoutException()
                            } else {
                                connected = authorizationResponse == CMD_CONNECTION
                            }
                        }
                    }
                    if (!connected) {
                        close()
                        throw ConnectException("failed to connect adb")
                    }
                    // Connection established: drop the handshake read timeout so
                    // long-lived shell / JDWP reads can block as long as needed.
                    clearHandshakeReadTimeout()
                }
            }.getOrElse {
                if (it !is ConnectException && maxRetryCount > 0) {
                    openShell(host, port, timeout, maxRetryCount - 1)
                } else throw it
            }
        }

        fun Socket.createSSLSocket(host: String, port: Int): SSLSocket = SSLContext.getInstance("TLSv1.3").run {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
            (socketFactory.createSocket(this@createSSLSocket, host, port, true) as SSLSocket).apply {
                tcpNoDelay = true
                soTimeout = HANDSHAKE_READ_TIMEOUT_MS
                startHandshake()
            }
        }

        val PUBLIC_KEY_AUTHORIZATION_DATA = byteArrayOf(
            81, 65, 65, 65, 65, 78, 80, 76, 80, 106, 121, 108, 79, 120, 57, 115, 107, 121, 115, 104, 71, 69, 109, 48, 86, 106, 117, 86,
            51, 87, 112, 54, 57, 108, 78, 100, 73, 105, 48, 120, 103, 50, 43, 50, 80, 113, 84, 49, 88, 43, 104, 82, 120, 120, 48, 49,
            119, 53, 77, 120, 50, 43, 118, 66, 66, 75, 79, 83, 116, 79, 107, 84, 70, 81, 50, 88, 108, 80, 67, 57, 98, 89, 88, 78, 88, 80,
            101, 112, 121, 73, 50, 118, 51, 103, 66, 112, 114, 97, 73, 87, 84, 52, 85, 54, 89, 98, 49, 43, 57, 103, 113, 113, 106, 120,
            116, 49, 49, 103, 57, 89, 114, 51, 84, 116, 102, 112, 47, 107, 114, 76, 65, 77, 112, 76, 74, 115, 115, 57, 88, 112, 89, 86,
            111, 121, 77, 97, 57, 116, 110, 87, 50, 69, 80, 114, 122, 110, 119, 86, 117, 118, 47, 69, 51, 105, 118, 68, 88, 75, 65, 57,
            47, 100, 56, 107, 67, 69, 99, 75, 43, 43, 121, 107, 52, 101, 101, 120, 48, 97, 103, 115, 65, 117, 51, 111, 71, 106, 47, 119,
            90, 57, 55, 102, 90, 78, 65, 112, 78, 115, 119, 104, 70, 98, 79, 65, 106, 49, 55, 112, 116, 89, 43, 105, 112, 89, 119, 80,
            74, 85, 73, 119, 87, 122, 109, 103, 54, 113, 84, 81, 47, 84, 74, 117, 99, 67, 78, 78, 111, 48, 56, 97, 85, 81, 106, 43, 103,
            116, 75, 112, 101, 106, 113, 65, 122, 114, 115, 51, 73, 109, 114, 102, 113, 111, 97, 114, 83, 85, 52, 73, 122, 99, 83, 111,
            43, 99, 43, 90, 112, 119, 72, 49, 112, 54, 50, 121, 68, 51, 50, 54, 88, 80, 118, 83, 75, 66, 50, 72, 117, 105, 81, 69, 48,
            98, 113, 105, 83, 79, 116, 87, 105, 119, 90, 54, 105, 69, 51, 101, 98, 112, 111, 76, 116, 103, 78, 89, 68, 97, 102, 97, 68,
            43, 65, 57, 66, 84, 77, 121, 83, 78, 82, 57, 67, 102, 101, 55, 86, 53, 110, 72, 89, 119, 85, 90, 71, 68, 57, 99, 87, 53, 82,
            51, 56, 121, 120, 82, 65, 68, 48, 111, 69, 69, 81, 68, 57, 120, 79, 73, 122, 47, 122, 97, 103, 81, 65, 47, 106, 98, 103, 70,
            100, 83, 103, 74, 118, 90, 52, 71, 88, 99, 88, 77, 116, 90, 88, 85, 105, 49, 122, 121, 68, 66, 73, 67, 97, 85, 51, 101, 108,
            117, 48, 67, 71, 88, 75, 84, 104, 117, 76, 82, 106, 71, 49, 109, 80, 122, 83, 50, 86, 51, 101, 116, 90, 78, 101, 82, 117,
            75, 103, 69, 103, 122, 57, 113, 75, 69, 101, 68, 106, 83, 81, 119, 119, 74, 90, 76, 54, 97, 78, 82, 65, 106, 87, 51, 50, 104,
            97, 66, 115, 106, 114, 115, 111, 117, 110, 88, 65, 109, 104, 115, 101, 110, 109, 101, 103, 53, 112, 103, 122, 120, 108,
            72, 111, 57, 47, 83, 57, 57, 98, 120, 43, 47, 115, 117, 110, 115, 72, 71, 117, 112, 81, 87, 82, 71, 79, 106, 81, 97, 76, 69,
            116, 77, 70, 122, 111, 118, 53, 89, 104, 118, 111, 120, 49, 90, 83, 107, 52, 57, 66, 43, 98, 102, 119, 43, 77, 72, 53, 48,
            77, 87, 118, 49, 43, 70, 48, 47, 89, 78, 71, 51, 53, 75, 67, 112, 104, 79, 88, 48, 83, 77, 74, 97, 83, 99, 113, 56, 103, 100,
            109, 43, 99, 122, 76, 90, 117, 53, 84, 78, 79, 51, 113, 52, 66, 89, 72, 68, 97, 112, 50, 115, 102, 47, 71, 73, 43, 112, 57,
            50, 109, 86, 88, 121, 56, 54, 112, 106, 117, 106, 73, 117, 120, 119, 119, 120, 107, 120, 116, 109, 81, 48, 49, 71, 52, 79,
            103, 52, 121, 101, 113, 121, 51, 75, 120, 85, 89, 73, 90, 87, 50, 115, 47, 114, 87, 115, 109, 69, 99, 50, 86, 106, 100, 50,
            85, 97, 70, 105, 85, 112, 82, 99, 78, 72, 69, 87, 71, 81, 69, 65, 65, 81, 65, 61, 32, 119, 105, 114, 101, 108, 101, 115,
            115, 64, 97, 100, 98, 0
        )

        private val PRIVATE_KEY_DATA = byteArrayOf(
            48, -126, 4, -66, 2, 1, 0, 48, 13, 6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 1, 5, 0, 4, -126, 4, -88, 48, -126, 4, -92, 2, 1, 0, 2,
            -126, 1, 1, 0, -97, -48, 71, -115, 36, 51, 83, -48, 3, -2, -96, 125, -38, -128, 53, 96, -69, -96, -23, -26, -35, -124, -88, 103,
            -80, 104, -75, -114, 36, -86, 27, 77, 64, -94, 123, -40, -127, 34, -67, -49, -91, -37, -9, 32, -37, 122, 90, 31, 112, -102, -7,
            -100, -113, 74, -36, -116, -32, -108, -76, 106, -88, -6, -83, 38, 114, -77, -21, 12, -88, -93, -105, 42, 45, -24, -113, 16, -91,
            -15, 52, -38, 52, 2, -25, 38, -45, 15, 77, -86, 14, -102, -77, 5, 35, 84, -14, -64, 88, 42, -6, 88, -101, -18, -11, 8, 56, 91, 17,
            -62, 108, -109, 2, 77, -10, -19, 125, 6, -1, -93, -127, -34, 46, -64, -126, 26, 29, 123, 30, 78, -54, -66, -81, 112, -124, 64,
            -14, -35, -33, 3, -54, 53, -68, -30, 77, -4, -81, 91, -63, -25, -68, 62, -124, 109, -99, 109, -81, 49, 50, 90, 97, -23, -43, -77,
            108, -78, -92, 12, -80, -84, -28, -97, 126, -19, 116, -81, 88, 15, -42, 117, 27, -113, -86, 10, -10, 126, -67, 97, 58, -123, 79,
            22, -94, -83, 105, 0, -34, -81, -115, -56, -87, -9, 92, -51, -123, 109, -67, -16, -108, -105, 13, 21, 19, -23, -76, -110, -93, 4,
            -63, -21, -37, 49, -109, -61, 53, 29, -57, 81, -24, 95, -11, -92, 62, -74, 111, -125, 49, 45, 34, 93, 83, -10, 122, 106, -35,
            -107, 59, 86, -76, 73, 24, 33, 43, -109, 108, 31, 59, -91, 2, 3, 1, 0, 1, 2, -126, 1, 0, 0, -69, -113, -92, 57, 93, 122, 4, -120,
            60, -70, 63, 27, -65, 6, 27, 13, 29, 89, 24, -6, 127, -97, -91, -116, 36, 120, 69, -63, 1, 83, 29, -121, 0, -7, 55, -62, 12, 46,
            -101, 126, 55, 73, 16, 77, 109, 122, 0, -47, -76, -103, -28, -32, -106, -46, 14, -91, 72, 67, -23, 25, 44, -53, 80, -87, 6, 1,
            -27, -31, 93, 22, -46, -46, -3, 75, 66, -51, -69, 0, 49, 98, -38, 21, -53, 15, -77, -115, 84, -85, 95, -38, 11, 89, 87, -50, -13,
            121, -54, -37, -30, -23, 3, -121, 69, 13, 54, 10, -48, -118, 3, 56, -74, -66, -67, -77, -41, -83, 125, -118, -121, 23, 115, 16,
            112, 75, -81, -8, -104, 96, -70, -96, 123, -39, -16, 82, 79, 21, -94, -38, 12, -89, 65, -111, -104, 74, -59, 120, -16, 114, -84,
            -14, -81, 124, -39, -6, 43, -127, 100, -51, -114, -42, -102, 86, 62, 49, -14, 4, -112, -45, -15, 83, 125, -87, -46, -91, 93, 46,
            106, -98, 49, -95, 114, 28, -10, 48, -40, 29, -47, 60, 52, 72, -112, 23, 60, 33, -114, -61, 126, -120, 117, 36, 123, -42, 10,
            54, -21, -99, 52, -105, 10, 93, -28, 56, -6, -39, 36, -39, 27, -48, -66, 82, -116, -53, 65, 122, 110, -12, 109, -88, 22, -96, 85,
            -48, -88, -114, -49, -78, -7, 29, -117, 27, -104, 108, -24, 52, -66, 28, 17, -45, -110, -104, -55, -81, -61, -22, 11, 2, -127,
            -127, 0, -33, 56, 16, 4, 13, 39, 101, 58, 44, -112, 101, 70, -35, 66, 74, -69, 110, 108, -11, 28, -79, 12, -67, 25, -92, 101,
            56, -68, 87, -68, 23, -35, 65, 51, 22, -21, 113, -127, -126, 71, 20, -65, -75, -25, -96, 105, 92, -67, -10, 45, -88, -103, -120,
            56, 0, -88, -38, -42, -127, 68, -23, 71, 39, -36, -80, -10, -114, -126, -75, 12, -28, -52, -40, 26, -108, 97, -7, 10, 28, -23, 119,
            103, 16, -109, 20, 118, 106, -48, 126, 76, -30, -96, -22, 25, 31, 34, -54, 126, 91, 106, 48, 30, -21, -98, -31, -60, 112, -21,
            32, 117, -33, -51, -66, -81, -99, -9, 64, 65, 110, 34, -67, 123, -33, 71, 66, -80, 65, 3, 2, -127, -127, 0, -73, 72, 124, 62, -102,
            12, -128, 42, -7, -27, 1, 66, 101, -32, 125, 86, -111, 63, 14, -43, 121, -118, -48, 120, -87, 23, -108, 23, 13, -41, -9, 35, 88,
            -49, 97, -31, -27, -109, -110, 14, 120, 73, 69, -48, -11, 66, -114, 108, -46, 79, 104, -81, -10, 108, -12, -92, -79, 63, 40, -48,
            80, -115, -29, 76, 101, -49, -88, 115, -81, 71, 46, -106, -31, 68, 18, -15, 89, 82, -79, 51, 53, 38, -83, 42, 47, 96, 107, -127,
            51, 43, -55, 82, 59, -59, -51, 10, 83, 88, -106, -123, -116, -10, -95, -121, -55, 59, -87, -87, -105, -31, 116, 127, 19, -29, -31,
            -119, 77, 123, -82, 8, 79, 74, 32, 65, -75, -100, 108, 55, 2, -127, -127, 0, -91, 110, -52, 87, -28, 83, -51, 47, 23, 54, 17, 9,
            59, 20, 85, -124, -95, -21, 120, -95, -62, 9, -7, -32, 22, 57, -70, -103, -61, -64, 48, 67, -105, 125, -64, -65, -48, 8, -74, -65,
            -19, 125, -61, -40, 29, -57, -40, -89, 36, -37, 99, -8, 29, -65, -69, -91, 105, 66, -50, -35, 126, -78, 112, -75, -100, 37, -81,
            42, -23, -7, -37, 92, -127, -48, -7, 37, -65, 71, -94, 115, -39, 61, 118, 72, 59, 67, 36, 24, -49, 114, 54, 8, 34, 87, 105, -1, 3,
            -22, 47, -33, -4, 55, -2, 82, 107, 106, -122, 113, -116, 70, 48, 15, 49, 2, -64, -27, 45, 39, 16, -12, 8, 80, -43, 2, -32, 70, -10,
            119, 2, -127, -128, 35, -2, 34, 24, 17, 28, 127, 86, -15, 56, 29, -91, 50, 104, -127, 116, -84, -107, 91, -114, 100, -115, -12,
            30, -99, -26, 57, 120, -59, -119, 49, -55, 73, 57, -128, -103, 98, 2, 54, -34, -116, -108, -89, 23, 63, -14, -48, 17, 98, -61, -95,
            101, 92, -39, 76, -71, -62, -19, 10, 80, -50, 96, -18, -48, 35, -10, 65, -72, 102, 37, 110, 106, -58, -42, 29, 122, 51, -10, 95,
            66, 21, 32, 1, 69, -107, -124, 51, -40, 109, 122, 29, -48, -2, 87, -5, -54, 25, 79, -2, 79, 63, -67, 119, 63, 57, 47, 116, 68, 15,
            -59, -128, -95, 44, 0, -58, 91, -74, 81, -95, 125, -108, 68, -108, 77, 19, 35, 34, -59, 2, -127, -127, 0, -100, 86, 105, -124, 16,
            63, -77, 55, 97, 100, -76, -19, 93, -122, -55, -120, 38, 125, -127, 94, -34, 73, 105, -40, 59, -16, 100, -120, -53, -111, 58, 71,
            -109, 71, 12, -56, -119, 116, -104, 15, -49, -85, 19, -69, -99, -65, -7, 75, -104, 109, 126, 96, -54, -21, -111, -79, -93, -104,
            -48, 31, 107, -106, 81, -118, -57, -60, -128, -116, 112, 3, -42, -79, 68, -62, -3, -36, 10, -18, -81, -57, 32, 117, 54, -43, 57,
            10, -127, -120, 96, 101, -97, -45, 114, 41, -75, 69, 32, -90, 86, -7, 25, 124, 43, -98, -116, 23, 54, 110, 87, 80, -45, 75, 65,
            -48, -51, -19, -95, -34, 44, 7, -7, 22, 107, 17, -121, 96, -39, -32
        )

        private val PUBLIC_KEY_DATA = byteArrayOf(
            48, -126, 1, 34, 48, 13, 6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 1, 5, 0, 3, -126, 1, 15, 0, 48, -126, 1, 10, 2, -126, 1, 1, 0, -97,
            -48, 71, -115, 36, 51, 83, -48, 3, -2, -96, 125, -38, -128, 53, 96, -69, -96, -23, -26, -35, -124, -88, 103, -80, 104, -75, -114,
            36, -86, 27, 77, 64, -94, 123, -40, -127, 34, -67, -49, -91, -37, -9, 32, -37, 122, 90, 31, 112, -102, -7, -100, -113, 74, -36,
            -116, -32, -108, -76, 106, -88, -6, -83, 38, 114, -77, -21, 12, -88, -93, -105, 42, 45, -24, -113, 16, -91, -15, 52, -38, 52, 2,
            -25, 38, -45, 15, 77, -86, 14, -102, -77, 5, 35, 84, -14, -64, 88, 42, -6, 88, -101, -18, -11, 8, 56, 91, 17, -62, 108, -109, 2,
            77, -10, -19, 125, 6, -1, -93, -127, -34, 46, -64, -126, 26, 29, 123, 30, 78, -54, -66, -81, 112, -124, 64, -14, -35, -33, 3, -54,
            53, -68, -30, 77, -4, -81, 91, -63, -25, -68, 62, -124, 109, -99, 109, -81, 49, 50, 90, 97, -23, -43, -77, 108, -78, -92, 12, -80,
            -84, -28, -97, 126, -19, 116, -81, 88, 15, -42, 117, 27, -113, -86, 10, -10, 126, -67, 97, 58, -123, 79, 22, -94, -83, 105, 0,
            -34, -81, -115, -56, -87, -9, 92, -51, -123, 109, -67, -16, -108, -105, 13, 21, 19, -23, -76, -110, -93, 4, -63, -21, -37, 49, -109,
            -61, 53, 29, -57, 81, -24, 95, -11, -92, 62, -74, 111, -125, 49, 45, 34, 93, 83, -10, 122, 106, -35, -107, 59, 86, -76, 73, 24,
            33, 43, -109, 108, 31, 59, -91, 2, 3, 1, 0, 1
        )

        private val X509_V3_CERTIFICATE = byteArrayOf(
            48, -126, 2, -107, 48, -126, 1, 125, -96, 3, 2, 1, 2, 2, 1, 1, 48, 13, 6, 9, 42,
            -122, 72, -122, -9, 13, 1, 1, 11, 5, 0, 48, 13, 49, 11, 48, 9, 6, 3, 85, 4, 3, 12, 2, 48, 48, 48, 32, 23, 13, 55, 48, 48, 49, 48,
            49, 48, 48, 48, 48, 48, 48, 90, 24, 15, 50, 49, 50, 51, 49, 50, 50, 51, 49, 49, 51, 51, 51, 53, 90, 48, 13, 49, 11, 48, 9, 6,
            3, 85, 4, 3, 12, 2, 48, 48, 48, -126, 1, 34, 48, 13, 6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 1, 5, 0, 3, -126, 1, 15, 0, 48, -126,
            1, 10, 2, -126, 1, 1, 0, -97, -48, 71, -115, 36, 51, 83, -48, 3, -2, -96, 125, -38, -128, 53, 96, -69, -96, -23, -26, -35, -124, -88,
            103, -80, 104, -75, -114, 36, -86, 27, 77, 64, -94, 123, -40, -127, 34, -67, -49, -91, -37, -9, 32, -37, 122, 90, 31, 112, -102,
            -7, -100, -113, 74, -36, -116, -32, -108, -76, 106, -88, -6, -83, 38, 114, -77, -21, 12, -88, -93, -105, 42, 45, -24, -113, 16, -91,
            -15, 52, -38, 52, 2, -25, 38, -45, 15, 77, -86, 14, -102, -77, 5, 35, 84, -14, -64, 88, 42, -6, 88, -101, -18, -11, 8, 56, 91, 17,
            -62, 108, -109, 2, 77, -10, -19, 125, 6, -1, -93, -127, -34, 46, -64, -126, 26, 29, 123, 30, 78, -54, -66, -81, 112, -124, 64, -14,
            -35, -33, 3, -54, 53, -68, -30, 77, -4, -81, 91, -63, -25, -68, 62, -124, 109, -99, 109, -81, 49, 50, 90, 97, -23, -43, -77, 108,
            -78, -92, 12, -80, -84, -28, -97, 126, -19, 116, -81, 88, 15, -42, 117, 27, -113, -86, 10, -10, 126, -67, 97, 58, -123, 79, 22,
            -94, -83, 105, 0, -34, -81, -115, -56, -87, -9, 92, -51, -123, 109, -67, -16, -108, -105, 13, 21, 19, -23, -76, -110, -93, 4, -63,
            -21, -37, 49, -109, -61, 53, 29, -57, 81, -24, 95, -11, -92, 62, -74, 111, -125, 49, 45, 34, 93, 83, -10, 122, 106, -35, -107, 59,
            86, -76, 73, 24, 33, 43, -109, 108, 31, 59, -91, 2, 3, 1, 0, 1, 48, 13, 6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 11, 5, 0, 3, -126,
            1, 1, 0, 55, 117, 46, 120, 23, 91, 13, 51, 109, -62, -114, 59, 99, -65, -102, 46, 85, 40, 103, -100, -81, 112, 109, 87, -123,
            95, 34, -96, -79, 94, 123, -88, 107, -48, 124, 16, -111, 57, 29, 53, 60, 105, 20, 10, -108, -98, 66, 69, 4, -34, -128, -20, -43,
            -24, 27, -48, -36, -3, 113, -51, 82, 23, -46, 116, 75, -93, -25, -65, -48, -128, -124, 62, -80, 56, -46, -22, -24, -82, -66, 0, -115,
            -65, -83, -53, -33, -56, 114, 44, -53, 61, 115, 42, -11, 100, 77, 58, -65, -34, -115, 44, -23, -41, -57, -51, -108, -45, 75, 7, -58,
            -77, 88, 56, 80, -96, 7, 66, 86, -89, -100, -20, 0, 28, -101, -73, -108, 105, 114, 7, -112, 119, -46, -83, -80, 81, -80, 18, -126,
            -31, -27, 61, -6, -20, 23, 20, 73, -63, -68, -84, 38, -98, 2, 20, 100, -52, -9, 77, 24, -47, -63, 95, -84, 126, 108, 16, 49, -10,
            67, -48, 7, 88, -53, -115, -72, -89, -63, 115, -45, 32, 34, 54, -114, 45, -55, 44, 16, -20, -46, 81, 96, -62, -70, -3, -74, -121,
            -119, -70, -58, -44, -67, -7, -23, 39, -86, -74, 100, -62, -99, 98, 78, 6, 41, -74, -119, -85, 63, 76, -88, 32, -66, 57, 17, 33, 61,
            44, 107, -99, 78, -64, 108, -33, 66, 72, 29, 68, -109, -115, 94, 111, -27, -46, -77, -23, -56, -108, -83, 121, -24, -30, 24, -52,
            -60, -43, -55, 54, 44, 85
        )

        private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(PRIVATE_KEY_DATA))

        private fun ByteArray.signByPrivateKey() = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, privateKey)
            update(
                byteArrayOf(
                    0x0, 0x1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    0x0, 0x30, 0x21, 0x30, 0x9, 0x6, 0x5, 0x2b, 0xe, 0x3, 0x2, 0x1a, 0x5, 0x0, 0x4, 0x14
                )
            )
            doFinal(this@signByPrivateKey)
        }

        private val keyManager: KeyManager
            get() = object : X509ExtendedKeyManager() {

                override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?) = ""

                override fun getCertificateChain(alias: String?) = arrayOf(
                    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(X509_V3_CERTIFICATE)) as X509Certificate
                )

                override fun getPrivateKey(alias: String?): PrivateKey = privateKey

                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

                override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null

                override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
            }

        private val trustManager: TrustManager
            get() = object : X509TrustManager {

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
    }

    private fun generateMessage(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) =
        ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(data.sumOf { if (it >= 0) it.toInt() else it + 256 }).putInt(cmd xor -0x1).put(data)
        }.array()

    private fun write2socket(data: ByteArray) = synchronized(outputStream) {
        outputStream.write(data)
        outputStream.flush()
    }

    private fun waitForResponse(): Triple<Int, Int, ByteArray> {
        val adbHeaderLength = 24
        val responsePacket = ByteBuffer.allocate(adbHeaderLength).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(responsePacket.array(), 0, adbHeaderLength)
        val command = responsePacket.int
        val arg0 = responsePacket.int
        responsePacket.int
        val packetDataLength = responsePacket.int
        val packetChecksum = responsePacket.int
        val magic = responsePacket.int
        var packetData = ByteArray(0)
        if (packetDataLength > 0) {
            packetData = ByteArray(packetDataLength)
            inputStream.readFully(packetData, 0, packetDataLength)
        }
        val packetDataChecksum = packetData.sumOf { if (it >= 0) it.toInt() else it + 256 }
        if (command != magic xor -0x1 || (packetDataLength > 0 && packetDataChecksum != packetChecksum)) {
            error("response checksum is invalidate!")
        }
        return Triple(command, arg0, packetData)
    }
}