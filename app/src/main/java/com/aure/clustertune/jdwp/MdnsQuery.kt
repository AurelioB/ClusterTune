package com.aure.clustertune.jdwp

import android.content.Context
import android.net.wifi.WifiManager
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Sends real mDNS queries instead of waiting to overhear an announcement.
 *
 * ## Why this exists
 *
 * `NsdManager.discoverServices` is the normal way to do this, and it is still
 * used — but on this device it only ever reports `_adb-tls-connect._tcp` at the
 * moment adbd *announces* it (toggling wireless debugging, or opening the
 * Wireless debugging settings screen). Measured across three logs: discovery
 * started, then the service appeared 15s later, 41s later, and in one run never
 * at all — each time tracking when the settings screen was touched rather than
 * when discovery began. A discovery listener that starts after the announcement
 * has gone out simply sits there with `servicesFound=0`.
 *
 * The practical effect was that the port scan — meant to be the fallback —
 * became the normal path, and it costs 20,000 connect attempts.
 *
 * A DNS-SD query is a ~45 byte UDP packet. Sending one directly puts the
 * question on the wire whenever we want an answer, rather than hoping to be
 * listening at the right moment.
 *
 * ## Details that matter
 *
 * - The **QU bit** (unicast-response-requested, top bit of the question class)
 *   is set, so a responder may reply straight back to our source port. That
 *   avoids depending on multicast being looped back to another socket on the
 *   same host, which is the fragile part when querier and responder are the
 *   same device.
 * - A `MulticastLock` is taken when available, because Wi-Fi hardware otherwise
 *   filters multicast frames the app would need for a non-QU reply.
 * - Only the SRV record is of interest: the host is always this device, so the
 *   port is the single unknown.
 */
internal object MdnsQuery {

    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    private const val QU_BIT = 0x8000

    /**
     * Asks for [serviceType] and returns the advertised port, or null.
     *
     * Blocking; call off the main thread. Re-sends the query a few times inside
     * the window because a single multicast datagram is allowed to be lost.
     */
    fun queryPort(
        context: Context,
        serviceType: String,
        timeoutMs: Long = 2500L,
    ): Int? {
        val lock = acquireMulticastLock(context)
        try {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = SOCKET_READ_MS
                val query = buildQuery(serviceType)
                val group = InetAddress.getByName(MDNS_GROUP)
                val deadline = System.currentTimeMillis() + timeoutMs
                var sentAt = 0L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now - sentAt >= RESEND_INTERVAL_MS) {
                        sentAt = now
                        runCatching {
                            socket.send(DatagramPacket(query, query.size, InetSocketAddress(group, MDNS_PORT)))
                        }.onFailure {
                            JdwpDebugLog.w("mdns-query: send failed: ${it.message}")
                            return null
                        }
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) continue
                    val port = runCatching {
                        parseSrvPort(packet.data, packet.length, serviceType)
                    }.getOrNull()
                    if (port != null && port in 1..65535) {
                        JdwpDebugLog.d("mdns-query: $serviceType answered port=$port")
                        return port
                    }
                }
            }
        } catch (error: Throwable) {
            JdwpDebugLog.w("mdns-query: failed: ${error.message}")
        } finally {
            runCatching { lock?.release() }
        }
        JdwpDebugLog.d("mdns-query: no answer for $serviceType within ${timeoutMs}ms")
        return null
    }

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        wifi.createMulticastLock("clustertune-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    // ---- wire format --------------------------------------------------------

    private fun buildQuery(serviceType: String): ByteArray {
        val name = encodeName("$serviceType.local")
        val out = ByteArray(12 + name.size + 4)
        // id=0, flags=0 (standard query), qdcount=1, others 0
        writeShort(out, 4, 1)
        name.copyInto(out, 12)
        writeShort(out, 12 + name.size, TYPE_PTR)
        writeShort(out, 12 + name.size + 2, CLASS_IN or QU_BIT)
        return out
    }

    private fun encodeName(name: String): ByteArray {
        val out = ArrayList<Byte>(name.length + 2)
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray()
            require(bytes.size in 1..63) { "invalid label" }
            out.add(bytes.size.toByte())
            bytes.forEach(out::add)
        }
        out.add(0)
        return out.toByteArray()
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    /**
     * Reads a DNS name, following compression pointers, and returns it together
     * with the offset just past the name *as encoded at [start]* — which is not
     * where parsing ended if a pointer was followed.
     */
    private fun readName(buffer: ByteArray, start: Int, limit: Int): Pair<String, Int> {
        val labels = StringBuilder()
        var offset = start
        var next = start
        var jumped = false
        var guard = 0
        while (true) {
            require(++guard <= MAX_NAME_HOPS) { "name loop" }
            require(offset < limit) { "name overruns packet" }
            val length = buffer[offset].toInt() and 0xFF
            if (length == 0) {
                offset += 1
                if (!jumped) next = offset
                break
            }
            if (length and 0xC0 == 0xC0) {
                require(offset + 1 < limit) { "pointer overruns packet" }
                val pointer = ((length and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                if (!jumped) next = offset + 2
                jumped = true
                offset = pointer
                continue
            }
            require(offset + 1 + length <= limit) { "label overruns packet" }
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(buffer, offset + 1, length))
            offset += 1 + length
        }
        return labels.toString() to next
    }

    /** Returns the port from the first SRV record belonging to [serviceType]. */
    private fun parseSrvPort(buffer: ByteArray, limit: Int, serviceType: String): Int? {
        if (limit < 12) return null
        val questions = readShort(buffer, 4)
        val records = readShort(buffer, 6) + readShort(buffer, 8) + readShort(buffer, 10)
        var offset = 12
        repeat(questions) {
            offset = readName(buffer, offset, limit).second + 4
        }
        val suffix = "$serviceType.local"
        repeat(records) {
            val (name, afterName) = readName(buffer, offset, limit)
            offset = afterName
            require(offset + 10 <= limit) { "record header overruns packet" }
            val type = readShort(buffer, offset)
            val dataLength = readShort(buffer, offset + 8)
            offset += 10
            require(offset + dataLength <= limit) { "record data overruns packet" }
            if (type == TYPE_SRV && name.endsWith(suffix) && dataLength >= 6) {
                // SRV rdata: priority(2) weight(2) port(2) target(name)
                return readShort(buffer, offset + 4)
            }
            offset += dataLength
        }
        return null
    }

    private const val SOCKET_READ_MS = 250
    private const val RESEND_INTERVAL_MS = 600L
    private const val MAX_NAME_HOPS = 128
}
