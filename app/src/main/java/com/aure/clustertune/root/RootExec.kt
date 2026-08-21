package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel

internal interface PServerHostExecutor {
    val pServerAvailable: Boolean
    fun launchHost(command: String): Result<Unit>
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class RootExec : PServerHostExecutor {

    @Volatile
    private var binder: IBinder? = findBinder()

    override val pServerAvailable: Boolean
        get() = activeBinder() != null

    override fun launchHost(command: String): Result<Unit> {
        return transact(command).map { Unit }
    }

    private fun transact(cmd: String): Result<Unit> {
        val activeBinder = activeBinder()
            ?: return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "0"))
            val accepted = activeBinder.transact(0, data, reply, 0)
            // Binder returning false means the host launch transaction was not delivered.
            check(accepted) { "PServer rejected the transaction" }
            Result.success(Unit)
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

}
