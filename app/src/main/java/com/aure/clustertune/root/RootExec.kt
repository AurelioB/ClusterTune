package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.StandardCharsets

interface PServerRootExecutor {
    val pServerAvailable: Boolean
    fun executeAsRoot(cmd: String): Result<String?>

    fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> = executeAsRoot(cmd)
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RootExec : PServerRootExecutor {

    private val binder: IBinder?
    override var pServerAvailable: Boolean = false
        private set

    init {
        binder = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getService.invoke(serviceManager, "PServerBinder") as IBinder
            pServerAvailable = true
            rawBinder
        }.getOrDefault(null)
    }

    override fun executeAsRoot(cmd: String): Result<String?> = executeAsRoot(cmd, captureOutput = true)

    override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, if (captureOutput) "1" else "0"))
            check(binder.transact(0, data, reply, 0)) { "PServer rejected the transaction" }
            Result.success(if (captureOutput) decodeReply(reply) else null)
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(StandardCharsets.UTF_8)
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }
}
