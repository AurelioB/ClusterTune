// SPDX-License-Identifier: MIT
package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel

internal interface PServerHostExecutor {
    val pServerAvailable: Boolean
    fun launchHost(command: String): Result<Unit>
}

/** One connection to the firmware service. A rejected or interrupted send must never be retried. */
internal interface PServerConnection {
    val alive: Boolean
    fun sendHostLaunch(command: String): Boolean
}

/**
 * Launch-only client for the PServer protocol documented in docs/execution-methods-plan.md.
 * Resolve the service for each operation instead of retaining a potentially stale Binder.
 */
internal class PServerHostLauncher(
    private val connect: () -> PServerConnection? = ::connectToPServer,
) : PServerHostExecutor {
    override val pServerAvailable: Boolean
        get() = runCatching { connect()?.alive == true }.getOrDefault(false)

    override fun launchHost(command: String): Result<Unit> = runCatching {
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid PServer host launcher" }
        require(command.toByteArray(Charsets.UTF_8).size <= 255) {
            "PServer host launcher exceeds 255 bytes"
        }
        val connection = checkNotNull(connect()) { "PServer service unavailable" }
        check(connection.alive) { "PServer connection is dead" }
        check(connection.sendHostLaunch(command)) { "PServer rejected the host launch" }
    }
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
private fun connectToPServer(): PServerConnection? {
    val service = Class.forName("android.os.ServiceManager")
        .getMethod("checkService", String::class.java)
        .invoke(null, "PServerBinder") as? IBinder
    return service?.let(::BinderPServerConnection)
}

internal class BinderPServerConnection(private val service: IBinder) : PServerConnection {
    override val alive: Boolean get() = service.isBinderAlive

    override fun sendHostLaunch(command: String): Boolean {
        val request = Parcel.obtain()
        try {
            val response = Parcel.obtain()
            try {
                // Firmware reads an int32 count followed by that many UTF-16 strings, without
                // an interface token. The last string selects output capture; "0" disables it.
                request.writeInt(2)
                request.writeString(command)
                request.writeString("0")
                return service.transact(0, request, response, 0)
            } finally {
                response.recycle()
            }
        } finally {
            request.recycle()
        }
    }
}
