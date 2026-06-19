package com.aure.clustertune.root

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.nio.charset.Charset

/**
 * One-shot diagnostic that runs at PServer initialisation. Sends a
 * known-good canary command (`echo PROBE_MARKER_4242`) to the PServer
 * binder using every plausible combination of transaction code,
 * request flag, and reply decoder, then logs each outcome.
 *
 * Goal: identify which combination on the Odin 2 Mini actually returns
 * the canary text, so we can replace [RootExec.executeAsRoot] with the
 * matching protocol. The current Odin 3 protocol (code 0, flag "1",
 * `createByteArray`) returns null on the Mini even though the
 * transaction does not throw.
 *
 * Logcat tag: `ClusterTuneProbe`. Filter with:
 *     adb logcat -s ClusterTuneProbe
 * and look for a line ending in `*** CANARY MATCH ***`.
 */
internal object PServerProbe {

    private const val TAG = "ClusterTuneProbe"
    private const val CANARY_CMD = "echo PROBE_MARKER_4242"
    private const val CANARY_EXPECTED = "PROBE_MARKER_4242"

    fun run(binder: IBinder) {
        Log.d(TAG, "===== PServerProbe begin =====")
        Log.d(TAG, "Canary command: $CANARY_CMD  (look for '$CANARY_EXPECTED' in a reply)")

        // Group A: vary transaction code, keep current request/reply shape.
        for (code in intArrayOf(0, 1, 2, 3, 4, 5)) {
            probe(
                label = "A-code$code",
                binder = binder,
                txCode = code,
                writer = { p -> p.writeStringArray(arrayOf(CANARY_CMD, "1")) },
            )
        }

        // Group B: keep code 0, vary the second-slot flag value.
        for (flag in arrayOf("0", "1", "2", "", "true")) {
            probe(
                label = "B-flag'$flag'",
                binder = binder,
                txCode = 0,
                writer = { p -> p.writeStringArray(arrayOf(CANARY_CMD, flag)) },
            )
        }

        // Group C: keep code 0, vary request marshalling shape.
        probe("C-no-flag", binder, 0) { p ->
            p.writeStringArray(arrayOf(CANARY_CMD))
        }
        probe("C-single-string", binder, 0) { p ->
            p.writeString(CANARY_CMD)
        }
        probe("C-string-then-int", binder, 0) { p ->
            p.writeString(CANARY_CMD)
            p.writeInt(1)
        }
        probe("C-int-then-string", binder, 0) { p ->
            p.writeInt(1)
            p.writeString(CANARY_CMD)
        }
        probe("C-byte-array", binder, 0) { p ->
            p.writeByteArray(CANARY_CMD.toByteArray(Charset.defaultCharset()))
        }
        probe("C-interface-token-PServer", binder, 0) { p ->
            p.writeInterfaceToken("PServer")
            p.writeStringArray(arrayOf(CANARY_CMD, "1"))
        }
        probe("C-interface-token-PServerBinder", binder, 0) { p ->
            p.writeInterfaceToken("PServerBinder")
            p.writeStringArray(arrayOf(CANARY_CMD, "1"))
        }

        Log.d(TAG, "===== PServerProbe end =====")
    }

    private fun probe(
        label: String,
        binder: IBinder,
        txCode: Int,
        writer: (Parcel) -> Unit,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            writer(data)
            val ok = try {
                binder.transact(txCode, data, reply, 0)
            } catch (t: Throwable) {
                Log.d(TAG, "$label: transact threw ${t.javaClass.simpleName}: ${t.message}")
                return
            }
            val replySize = reply.dataSize()
            Log.d(TAG, "$label: transact=$ok, reply.dataSize=$replySize")

            if (replySize == 0) {
                return
            }

            tryDecode(label, "byteArray", reply) {
                it.setDataPosition(0)
                it.createByteArray()?.toString(Charset.defaultCharset())
            }
            tryDecode(label, "readString", reply) {
                it.setDataPosition(0)
                it.readString()
            }
            tryDecode(label, "stringArray", reply) {
                it.setDataPosition(0)
                it.createStringArray()?.joinToString("|")
            }
            tryDecode(label, "exc+string", reply) {
                it.setDataPosition(0)
                val excCode = it.readInt()
                val s = it.readString()
                "exc=$excCode,s=$s"
            }
            tryDecode(label, "exc+byteArray", reply) {
                it.setDataPosition(0)
                val excCode = it.readInt()
                val ba = it.createByteArray()
                "exc=$excCode,s=${ba?.toString(Charset.defaultCharset())}"
            }
            tryDecode(label, "marshall-hex", reply) {
                it.setDataPosition(0)
                val raw = it.marshall()
                val cut = if (raw.size > 64) raw.copyOf(64) else raw
                val hex = cut.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                hex + if (raw.size > 64) "...(${raw.size}B total)" else ""
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun tryDecode(
        label: String,
        decoderName: String,
        reply: Parcel,
        decode: (Parcel) -> String?,
    ) {
        val result = try {
            decode(reply)
        } catch (t: Throwable) {
            "<threw ${t.javaClass.simpleName}: ${t.message}>"
        }
        val truncated = result?.let { if (it.length > 200) it.substring(0, 200) + "..." else it }
        val hit = if (result?.contains(CANARY_EXPECTED) == true) " *** CANARY MATCH ***" else ""
        Log.d(TAG, "$label: decode[$decoderName] = $truncated$hit")
    }
}
