package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel

internal interface PServerHostExecutor {
    val pServerAvailable: Boolean
    fun launchHost(command: String): Result<Unit>

    /**
     * Verifies that a transaction to PServer actually completes.
     *
     * Holding a live binder reference is NOT the same as being allowed to use
     * it. On the Odin 2 Mini the PServerBinder service is registered and
     * `isBinderAlive` is true, but every call is refused by SELinux:
     *
     *   avc: denied { call } scontext=u:r:untrusted_app tcontext=u:r:pservice
     *        tclass=binder permissive=0
     *   E/JavaBinder: !!! FAILED BINDER TRANSACTION !!!
     *
     * A presence-only probe therefore reports PServer as available on a device
     * where it can never work, auto-detection selects it over the working
     * method, and the privileged host is never reachable.
     */
    fun verify(): Result<Unit>
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

    /**
     * Cached because probing runs on state refreshes: an uncached verify
     * produced a failed binder transaction roughly once a second in logcat.
     * Successes are re-checked more often than failures, since a device that
     * has never been able to call PServer is unlikely to start.
     */
    override fun verify(): Result<Unit> {
        val now = System.currentTimeMillis()
        cachedVerify?.let { (checkedAt, result) ->
            val ttl = if (result.isSuccess) VERIFY_OK_TTL_MS else VERIFY_FAIL_TTL_MS
            if (now - checkedAt < ttl) return result
        }
        // `true` is a no-op on every shell, so a working PServer is unaffected.
        val result = transact(VERIFY_COMMAND)
        cachedVerify = now to result
        return result
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

    @Volatile
    private var cachedVerify: Pair<Long, Result<Unit>>? = null

    private fun findBinder(): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            getService.invoke(serviceManager, "PServerBinder") as? IBinder
        }.getOrNull()
    }

    private companion object {
        const val VERIFY_COMMAND = "true"
        const val VERIFY_OK_TTL_MS = 10_000L
        const val VERIFY_FAIL_TTL_MS = 60_000L
    }
}
