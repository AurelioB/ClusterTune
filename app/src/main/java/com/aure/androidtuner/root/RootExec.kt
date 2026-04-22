package com.aure.androidtuner.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RootExec {

    private val binder: IBinder?
    var pServerAvailable: Boolean = false
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

    fun executeAsRoot(cmd: String): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return runCatching {
            data.writeStringArray(arrayOf(cmd, "1"))
            binder?.transact(0, data, reply, 0)
            reply.createByteArray()
                ?.toString(Charset.defaultCharset())
                ?.trim()
                ?.let { value -> if (value == "null") null else value }
        }.onFailure {
            data.recycle()
            reply.recycle()
        }.onSuccess {
            data.recycle()
            reply.recycle()
        }
    }

    fun executeAsRoot(cmd: Array<String>): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return runCatching {
            data.writeStringArray(cmd + "1")
            binder?.transact(0, data, reply, 0)
            reply.createByteArray()
                ?.toString(Charset.defaultCharset())
                ?.trim()
                ?.let { value -> if (value == "null") null else value }
        }.onFailure {
            data.recycle()
            reply.recycle()
        }.onSuccess {
            data.recycle()
            reply.recycle()
        }
    }
}
