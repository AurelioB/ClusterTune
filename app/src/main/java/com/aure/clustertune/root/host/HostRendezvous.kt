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

    /**
     * Identity of a host this process is willing to *adopt* — i.e. one that was
     * already running before this process started.
     *
     * Needed because everything else here is per-process: [hosts] and the
     * receiver both die with the app. The host only announces itself once, at
     * launch, so after the app process is killed and restarted there was no way
     * left to find a host that is still running perfectly well. On the no-root
     * path that matters a lot, because starting a replacement needs wireless
     * debugging, which the user may have since turned off.
     *
     * Adoption deliberately does not require the launch nonce: this process
     * never saw it. The receiver is registered requiring the sender to hold
     * BROADCAST_PACKAGE_REMOVED, which only uid 0 / uid 1000 can satisfy, and
     * the adopted binder is still identity-checked by ClusterTuneHostClient.ping
     * before anything is trusted to it.
     */
    private var adopting: AdoptIdentity? = null

    internal data class AdoptIdentity(val name: String, val generation: Long)

    internal data class HandoffIdentity(val name: String, val nonce: String, val generation: Long, val method: String)
    fun prepare(context: Context, name: String, generation: Long, method: String): String = synchronized(lock) {
        pending = HandoffIdentity(name, nonce(), generation, method)
        leases[name] = Binder()
        if (receiver == null) {
            registerReceiver(context)
        }
        pending!!.nonce
    }

    private fun registerReceiver(context: Context) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                val actual = HandoffIdentity(
                    intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    intent.getStringExtra(EXTRA_NONCE).orEmpty(),
                    intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE),
                    intent.getStringExtra(EXTRA_METHOD).orEmpty(),
                )
                // A launch we initiated is matched strictly, by nonce. A host that
                // predates this process can only be matched on name + generation,
                // because the nonce died with the previous process.
                val target = synchronized(lock) {
                    val p = pending
                    when {
                        p != null && matches(p, actual) -> p.name
                        p == null && adopting?.let { a ->
                            a.name == actual.name && a.generation == actual.generation
                        } == true -> actual.name
                        else -> null
                    }
                } ?: return
                val host = intent.extras?.getBundle(EXTRA_PAYLOAD)?.getBinder(EXTRA_HOST) ?: return
                synchronized(lock) {
                    hosts[target] = host
                    if (leases[target] == null) leases[target] = Binder()
                    pending = null
                    runCatching {
                        host.linkToDeath(
                            { synchronized(lock) { if (hosts[target] === host) hosts.remove(target) } },
                            0,
                        )
                    }.onFailure { hosts.remove(target) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver!!,
            IntentFilter(ACTION),
            "android.permission.BROADCAST_PACKAGE_REMOVED",
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /**
     * Start listening for a host that is already running, so it can be adopted
     * after an app restart. Idempotent; safe to call on every app start.
     */
    fun listen(context: Context, name: String, generation: Long) = synchronized(lock) {
        adopting = AdoptIdentity(name, generation)
        if (leases[name] == null) leases[name] = Binder()
        if (receiver == null) {
            registerReceiver(context)
        }
    }

    fun lookup(name: String): IBinder? = synchronized(lock) { hosts[name] }
    fun lease(name: String): IBinder? = synchronized(lock) { leases[name] }
    fun clearPending(name: String, nonce: String) = synchronized(lock) {
        if (pending?.name == name && pending?.nonce == nonce) pending = null
    }

    private fun nonce(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    internal fun matches(expected: HandoffIdentity, actual: HandoffIdentity): Boolean = expected == actual
}
