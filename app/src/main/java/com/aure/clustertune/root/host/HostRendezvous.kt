package com.aure.clustertune.root.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Binder
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.Base64

internal object HostRendezvous {
    const val ACTION = "com.aure.clustertune.HOST_HANDOFF"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_NAME = "name"
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_METHOD = "method"
    const val EXTRA_HOST = "host"
    const val EXTRA_PAYLOAD = "payload"
    private val random = SecureRandom()
    private val lock = Any()
    private val hosts = LinkedHashMap<String, IBinder>()
    private val leases = LinkedHashMap<String, IBinder>()
    private var receiver: BroadcastReceiver? = null
    private var pending: HandoffIdentity? = null

    internal data class HandoffIdentity(val name: String, val nonce: String, val generation: Long, val method: String)
    fun prepare(context: Context, name: String, generation: Long, method: String): String = synchronized(lock) {
        pending = HandoffIdentity(name, nonce(), generation, method)
        leases[name] = Binder()
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val p = synchronized(lock) { pending } ?: return
                    val actual = HandoffIdentity(
                        intent.getStringExtra(EXTRA_NAME).orEmpty(),
                        intent.getStringExtra(EXTRA_NONCE).orEmpty(),
                        intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE),
                        intent.getStringExtra(EXTRA_METHOD).orEmpty(),
                    )
                    if (intent.action != ACTION || !matches(p, actual)) return
                    val host = intent.extras?.getBundle(EXTRA_PAYLOAD)?.getBinder(EXTRA_HOST) ?: return
                    synchronized(lock) {
                        hosts[p.name] = host
                        pending = null
                        runCatching { host.linkToDeath({ synchronized(lock) { if (hosts[p.name] === host) hosts.remove(p.name) } }, 0) }
                            .onFailure { hosts.remove(p.name) }
                    }
                }
            }
            val filter = IntentFilter(ACTION)
            ContextCompat.registerReceiver(
                context,
                receiver!!,
                filter,
                "android.permission.BROADCAST_PACKAGE_REMOVED",
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        pending!!.nonce
    }

    fun lookup(name: String): IBinder? = synchronized(lock) { hosts[name] }
    fun lease(name: String): IBinder? = synchronized(lock) { leases[name] }
    fun clearPending(name: String, nonce: String) = synchronized(lock) {
        if (pending?.name == name && pending?.nonce == nonce) pending = null
    }

    private fun nonce(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    internal fun matches(expected: HandoffIdentity, actual: HandoffIdentity): Boolean = expected == actual
}
