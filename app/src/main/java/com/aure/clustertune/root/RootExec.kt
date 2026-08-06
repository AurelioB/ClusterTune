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

    @Volatile
    private var binder: IBinder? = findBinder()

    override val pServerAvailable: Boolean
        get() = activeBinder() != null

    override fun executeAsRoot(cmd: String): Result<String?> = executeAsRoot(cmd, captureOutput = true)

    override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
        val activeBinder = activeBinder()
            ?: return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, if (captureOutput) "1" else "0"))
            val accepted = activeBinder.transact(0, data, reply, 0)
            if (captureOutput) {
                check(accepted) { "PServer rejected the transaction" }
            }
            // Output-disabled calls have no reply contract. Their effects are verified by callers.
            Result.success(if (captureOutput) decodeReply(reply) else null)
        } catch (throwable: Throwable) {
            if (!activeBinder.isBinderAlive) {
                synchronized(this) {
                    if (binder === activeBinder) binder = null
                }
            }
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun activeBinder(): IBinder? {
        binder?.takeIf(IBinder::isBinderAlive)?.let { return it }
        return synchronized(this) {
            binder?.takeIf(IBinder::isBinderAlive)
                ?: findBinder().also { binder = it }
        }
    }

    private fun findBinder(): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            getService.invoke(serviceManager, "PServerBinder") as? IBinder
        }.getOrNull()
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(StandardCharsets.UTF_8)
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }
}
