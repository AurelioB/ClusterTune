package com.aure.clustertune.jdwp

import android.content.Context
import android.util.Log
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.adb.AdbWirelessPairing
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbPairingPort
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbTcpConnectPort
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbWirelessConnectPort

/**
 * Holds the on-device wireless-debugging connection (host/port of the local
 * adbd) and drives mDNS discovery + SPAKE2 pairing using the vendored
 * jdwp-injector resolver/pairing classes (from
 * github.com/wuyr/jdwp-injector-for-android, Apache-2.0).
 *
 * Lifecycle: wireless debugging must be re-enabled each boot, so this holds
 * the connection in memory only. [JdwpInjectionExecutionMethod] reads
 * [connectionInfo] via a provider lambda.
 *
 * Typical flow (driven by the setup UI):
 *   1. startConnectDiscovery(...) — find the "connect" port (already paired).
 *   2. If that fails, startPairingDiscovery(...) — find the "pair" port, then
 *      pair(code, ...) with the 6-digit code shown under Wireless debugging.
 *   3. On success, [connectionInfo] is populated and injection can run.
 */
class WirelessDebugConnectionManager private constructor(
    context: Context,
) {

    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: WirelessDebugConnectionManager? = null

        /** Process-wide singleton, shared across all AppContainer instances. */
        fun getInstance(context: Context): WirelessDebugConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WirelessDebugConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Volatile
    var connectionInfo: AdbConnectionInfo? = null
        private set

    // A persistent shell connection reused across profile applies. Opening a
    // fresh adb connection every apply makes Android pop the "wireless
    // debugging connected" heads-up each time; reusing one connection avoids
    // that repeated pop-up (and the overhead).
    @Volatile
    private var persistentShell: AdbClient? = null
    private val shellLock = Any()

    /**
     * Returns a live shell [AdbClient], reusing the persistent one if healthy,
     * otherwise (re)opening it. Returns null if not connected.
     */
    fun sharedShell(): AdbClient? {
        val conn = connectionInfo ?: return null
        synchronized(shellLock) {
            val existing = persistentShell
            if (existing != null) {
                // Verify it still works with a cheap command; reopen if not.
                val probe = runCatching { existing.sendShellCommand("true") }
                if (probe.isSuccess) return existing
                JdwpDebugLog.w(
                    "sharedShell: persistent shell died, reopening " +
                        "(${probe.exceptionOrNull()?.javaClass?.simpleName}: " +
                        "${probe.exceptionOrNull()?.message})",
                )
                runCatching { existing.close() }
                persistentShell = null
            } else {
                JdwpDebugLog.d("sharedShell: no persistent shell yet, opening one")
            }
            return runCatching {
                AdbClient.openShell(conn.host, conn.port).also { persistentShell = it }
            }.getOrNull()
        }
    }

    /**
     * Serialises *use* of the shared shell.
     *
     * sharedShell() only guarded acquisition, so an apply and a concurrent state
     * refresh could both call sendShellCommand() on the SAME AdbClient socket.
     * The adb protocol is request/response on one stream, so the replies
     * interleaved (logs showed a command echoed back spliced into itself) and the
     * socket then died with "Socket is closed" seconds after being opened. Every
     * caller must hold this while talking to the shell.
     */
    val shellUseLock: Any = Any()

    /** Drop the persistent shell (e.g. after a failure or disconnect). */
    fun invalidateShell() {
        synchronized(shellLock) {
            runCatching { persistentShell?.close() }
            persistentShell = null
        }
    }

    /**
     * Forget the current connection entirely. Clears [connectionInfo] and tears
     * down the persistent shell + JDWP session so a fresh connect starts clean.
     * Called when the connection is found to be dead (e.g. wireless debugging was
     * turned off, or the pairing was deleted in system settings).
     */
    fun clearConnection() {
        JdwpDebugLog.d("clearConnection(): forgetting connection + sessions")
        connectionInfo = null
        invalidateShell()
        synchronized(jdwpLock) {
            runCatching { persistentDebugger?.close() }
            persistentDebugger = null
            persistentDebuggerPid = -1
        }
    }

    /**
     * Returns true if there is a live connection. If [connectionInfo] is set but
     * the underlying transport is actually dead, this clears it and returns false
     * — so the UI/state can fall back to the setup prompt instead of trusting a
     * stale "connected" flag. Runs a cheap shell probe; call off the main thread.
     */
    /**
     * Returns true if the connection is genuinely alive, clearing it if not.
     *
     * This performs the same real adb handshake used to establish the connection.
     * An earlier version probed via sharedShell() and cleared on any failure,
     * which wrongly destroyed live sessions (the shell and JDWP transports are
     * independent). A later version avoided that by never clearing — but then a
     * connection killed externally (wireless debugging turned off) was never
     * noticed, so the UI kept showing profiles and offered no way to reconnect.
     *
     * Doing the real handshake is now safe because AdbClient has bounded connect
     * and read timeouts, so this cannot hang. Call off the main thread.
     */
    /**
     * Cheap liveness check. MUST NOT perform an adb handshake.
     *
     * AdbClient.openShell/connect2jdwp/connectAdb are all @Synchronized on the
     * same companion — one process-wide lock. Doing a handshake here held that
     * lock for up to ~16s against a stale endpoint, and the apply path
     * (injectExecPersistent) takes jdwpLock and THEN needs that same global
     * lock — so an apply blocked while holding jdwpLock and wedged every other
     * apply. It also produced false negatives that destroyed live connections
     * (observed: declared an endpoint dead, then a successful handshake on that
     * exact endpoint 31s later), and each call opened a new adb connection,
     * making Android flash "Wireless debugging connected" repeatedly.
     *
     * A plain TCP connect takes no adb lock, finishes in <=1.5s, and detects the
     * case that actually matters: wireless debugging being switched off closes
     * the port. A port that is open but no longer paired surfaces as a real
     * error at apply time instead of silently nuking the connection.
     */
    fun verifyConnection(): Boolean {
        val conn = connectionInfo ?: return false
        val alive = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(conn.host, conn.port), 1500)
                true
            }
        }.getOrDefault(false)
        JdwpDebugLog.d("verifyConnection(tcp): ${conn.host}:${conn.port} alive=$alive")
        if (!alive) {
            JdwpDebugLog.w("verifyConnection: ${conn.host}:${conn.port} unreachable — clearing")
            clearConnection()
        }
        return alive
    }

    // ---- Persistent JDWP session (reused across applies) --------------------
    // Attaching a JDWP debugger opens an adb transport, which makes Android post
    // the "wireless debugging connected" heads-up. Re-attaching on every apply
    // makes it pop every time. Instead we attach ONCE and keep the session open
    // (GA runs normally between injections — we always resume the VM), so the
    // notification fires only on the first attach. We re-attach only if the
    // session dies (e.g. GA restarts).

    private var persistentDebugger: com.wuyr.jdwp_injector.debugger.Debugger? = null
    private var persistentDebuggerPid: Int = -1
    private val jdwpLock = Any()

    /**
     * Inject `Runtime.getRuntime().exec(command)` into the target as system,
     * reusing a persistent JDWP session. [triggerAgent] is invoked (over the
     * shared shell) to wake the target so the watchpoint fires.
     *
     * Returns true on success. On any failure the session is dropped so the
     * next call re-attaches.
     */
    fun injectExecPersistent(
        targetPackage: String,
        command: String,
        currentPid: Int,
        triggerAgent: () -> Unit,
    ): Boolean {
        val conn = connectionInfo ?: return false
        // Bound how long an apply may hold jdwpLock. A wedged attach previously
        // held it indefinitely, so every later profile press queued behind it and
        // produced no log line at all — the UI simply stopped responding.
        if (!jdwpBusy.tryAcquire(JDWP_BUSY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            JdwpDebugLog.w("jdwp: another injection is still running; dropping this request")
            return false
        }
        try {
        synchronized(jdwpLock) {
            // (Re)attach if we have no session, or GA's pid changed (restarted).
            var debugger = persistentDebugger
            if (debugger == null || persistentDebuggerPid != currentPid) {
                runCatching { debugger?.close() }
                debugger = runCatching {
                    JdwpDebugLog.d("jdwp: connect2jdwp ${conn.host}:${conn.port} pid=$currentPid …")
                    com.wuyr.jdwp_injector.debugger.Debugger(
                        AdbClient.connect2jdwp(conn.host, conn.port, currentPid)
                    )
                }.getOrElse { error ->
                    // This used to be .getOrNull(), which swallowed the reason
                    // entirely — the apply just reported "Injection failed" with
                    // no clue why. Surface it.
                    JdwpDebugLog.w(
                        "jdwp: connect2jdwp FAILED for pid=$currentPid " +
                            "(${error.javaClass.simpleName}: ${error.message})",
                        error,
                    )
                    return false
                }
                persistentDebugger = debugger
                persistentDebuggerPid = currentPid
                JdwpDebugLog.d("jdwp: attached persistent session to pid=$currentPid")
            }
            return try {
                val threadId = debugger.setAndWaitForModificationEventArrive(
                    "android.os.MessageQueue", "mMessages", "android.os.Message"
                ) { triggerAgent() }
                val runtimeObjectId = debugger.invokeStaticMethod(
                    "java.lang.Runtime", "getRuntime",
                    returnTypeName = "java.lang.Runtime", threadId = threadId
                ).second as Long
                debugger.invokeInstanceMethod(
                    runtimeObjectId, "java.lang.Runtime", "exec",
                    returnTypeName = "java.lang.Process", threadId = threadId,
                    "java.lang.String" to command
                )
                debugger.resumeVM() // GA runs normally; session stays attached
                true
            } catch (t: Throwable) {
                JdwpDebugLog.w("jdwp: persistent inject failed; dropping session", t)
                runCatching { debugger.resumeVM() }
                runCatching { debugger.close() }
                persistentDebugger = null
                persistentDebuggerPid = -1
                false
            }
        }
        } finally {
            jdwpBusy.release()
        }
    }

    private fun invalidateJdwp() {
        synchronized(jdwpLock) {
            runCatching { persistentDebugger?.resumeVM() }
            runCatching { persistentDebugger?.close() }
            persistentDebugger = null
            persistentDebuggerPid = -1
        }
    }

    private var connectResolver: AdbWirelessPortResolver? = null
    private var wirelessConnectResolver: AdbWirelessPortResolver? = null

    private var connectOnConnected: ((AdbConnectionInfo) -> Unit)? = null
    private var connectOnUnavailable: (() -> Unit)? = null
    private var pairingResolver: AdbWirelessPortResolver? = null

    private var pairingHost: String? = null
    private var pairingPort: Int = 0

    // True while a pair() handshake is running or has just succeeded. When set,
    // the mDNS "pairing service lost" callback is expected (Android closes its
    // dialog on success) and must NOT be surfaced as a failure.
    @Volatile
    private var pairingInProgressOrDone: Boolean = false

    /** Provider to hand to [JdwpInjectionExecutionMethod]. */
    fun provider(): () -> AdbConnectionInfo? = { connectionInfo }

    /**
     * Begin discovering the wireless-debugging CONNECT port (device already
     * paired). Calls [onConnected] when a host/port is found.
     */
    /**
     * Start connect discovery the way wuyr does: BOTH resolvers, running
     * continuously, so whenever the _adb-tls-connect._tcp service appears
     * (which only happens after wireless debugging is fully active/paired) it
     * gets caught. Callbacks are idempotent — the first successful resolve wins.
     *
     * Safe to call repeatedly (e.g. when the screen opens and on each Connect
     * tap); it won't tear down a discovery that's mid-flight if already running.
     */
    fun startConnectDiscovery(
        onConnected: (AdbConnectionInfo) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        // If already discovering, just update the callbacks; don't restart
        // (restarting is what made us miss the service-appearance window).
        connectOnConnected = onConnected
        connectOnUnavailable = onUnavailable
        if (connectResolver != null || wirelessConnectResolver != null) {
            JdwpDebugLog.d("startConnectDiscovery: already running; keeping discovery alive")
            val existing = connectionInfo
            if (existing != null) {
                onConnected(existing)
            } else {
                // Not connected yet, but we may have already resolved an endpoint
                // that failed the handshake because pairing hadn't happened. Retry
                // it now — this is what makes "pair, then connect" work without
                // waiting for another mDNS event.
                lastResolvedEndpoint?.let { (host, port) -> validateAndConnect(host, port) }
            }
            return
        }
        JdwpDebugLog.d("startConnectDiscovery: starting continuous discovery (tcp + tls-connect)")
        val handle: (String, Int) -> Unit = { host, port -> validateAndConnect(host, port) }
        connectResolver = with(appContext) {
            resolveAdbTcpConnectPort { host, port -> handle(host, port) }
        }
        wirelessConnectResolver = with(appContext) {
            resolveAdbWirelessConnectPort(onLost = {
                JdwpDebugLog.d("startConnectDiscovery: connect service lost")
                connectOnUnavailable?.invoke()
            }) { host, port -> handle(host, port) }
        }
    }

    /**
     * mDNS resolution only tells us the device *advertises* an adb connect
     * endpoint — it is advertised whether or not this app has been paired. The
     * old code treated a successful resolve as "CONNECTED", so the UI claimed to
     * be connected before the user had even entered a pairing code, and the
     * failure only surfaced later as "handshake failed, wireless pairing is
     * required!" when a profile was applied.
     *
     * We now do what the port scan already did: attempt the real adb handshake
     * and only report connected if it succeeds. Runs off the main thread.
     */
    private fun validateAndConnect(host: String, port: Int) {
        lastResolvedEndpoint = host to port
        if (connectionInfo != null || validatingEndpoint) return
        validatingEndpoint = true
        Thread {
            val ok = runCatching {
                AdbClient.openShell(host, port, connectTimeout = 3000L, maxRetryCount = 1).use { }
                true
            }.getOrDefault(false)
            validatingEndpoint = false
            if (ok) {
                val info = AdbConnectionInfo(host, port)
                connectionInfo = info
                JdwpDebugLog.d("connect: adb handshake OK -> CONNECTED $host:$port")
                connectOnConnected?.invoke(info)
            } else {
                JdwpDebugLog.w(
                    "connect: resolved $host:$port but adb handshake FAILED " +
                        "(not paired yet) — staying disconnected",
                )
                connectOnUnavailable?.invoke()
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Begin discovering the PAIRING port. Calls [onPairingPortFound] with the
     * host/port to which [pair] should then send the code.
     */
    fun startPairingDiscovery(
        onPairingPortFound: (String, Int) -> Unit,
        onLost: () -> Unit = {},
    ) {
        stopPairingDiscovery()
        pairingInProgressOrDone = false
        pairingResolver = with(appContext) {
            resolveAdbPairingPort(onLost = {
                // Android stops advertising the pairing service the moment
                // pairing succeeds (it closes its own dialog). Only treat this
                // as "dialog closed" if the user hasn't started pairing yet.
                if (!pairingInProgressOrDone) {
                    JdwpDebugLog.d("startPairingDiscovery: pairing port lost (before pairing)")
                    onLost()
                } else {
                    JdwpDebugLog.d("startPairingDiscovery: pairing port lost (expected after pairing) — ignoring")
                }
            }) { host, port ->
                pairingHost = host
                pairingPort = port
                JdwpDebugLog.d("startPairingDiscovery: pairing port found $host:$port")
                onPairingPortFound(host, port)
            }
        }
    }

    /**
     * Perform SPAKE2 pairing with the 6-digit [code]. Blocking; call off the
     * main thread. On success, connect discovery is (re)started so
     * [connectionInfo] gets populated.
     */
    fun pair(
        code: String,
        onPaired: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val host = pairingHost
        val port = pairingPort
        if (host == null || port == 0) {
            onError(IllegalStateException("Pairing port not found yet"))
            return
        }
        pairingInProgressOrDone = true
        runCatching {
            JdwpDebugLog.d("pair: pairing with $host:$port ...")
            AdbWirelessPairing(host, port, code).use { it.start() }
        }.onSuccess {
            JdwpDebugLog.d("pair: SUCCESS")
            stopPairingDiscovery()
            onPaired()
        }.onFailure {
            JdwpDebugLog.w("pair: FAILED", it)
            // allow the user to retry pairing
            pairingInProgressOrDone = false
            onError(it)
        }
    }

    fun stopConnectDiscovery() {
        connectResolver?.stop(); connectResolver = null
        wirelessConnectResolver?.stop(); wirelessConnectResolver = null
    }

    fun stopPairingDiscovery() {
        pairingResolver?.stop(); pairingResolver = null
    }

    fun stopAll() {
        // Note: intentionally does NOT drop the persistent JDWP session or shell
        // — those must survive leaving the setup screen so profiles keep
        // applying. Only discovery is stopped here.
        stopConnectDiscovery()
        stopPairingDiscovery()
    }

    // ---------------------------------------------------------------------
    //  Fallback: find the adb connect port by scanning the device's own
    //  Wi-Fi IP. Used when mDNS discovery doesn't surface the connect
    //  service. No typing, no mDNS. `adb connect <wifiIp>:<port>` is known
    //  to work, so the port is open on the Wi-Fi interface.
    // ---------------------------------------------------------------------

    @Volatile
    private var scanning = false
    private val JDWP_BUSY_TIMEOUT_MS = 20_000L

    /** Last endpoint mDNS resolved, so we can re-validate after pairing completes. */
    @Volatile
    private var lastResolvedEndpoint: Pair<String, Int>? = null

    /** Guards against overlapping handshake validations. */
    @Volatile
    private var validatingEndpoint = false

    /** Serialises injections and refuses (rather than queues forever) if one is stuck. */
    private val jdwpBusy = java.util.concurrent.Semaphore(1, true)

    /**
     * Scan the device's Wi-Fi IP for the adb connect port and, if found,
     * populate [connectionInfo]. Runs off the main thread. [onResult] is
     * invoked with the connection info on success, or null if not found.
     */
    fun scanForConnectPort(onResult: (AdbConnectionInfo?) -> Unit) {
        if (scanning) return
        scanning = true
        Thread {
            val result = runCatching { doScan() }.getOrNull()
            scanning = false
            if (result != null) {
                connectionInfo = result
                connectOnConnected?.invoke(result)
            }
            onResult(result)
        }.also { it.isDaemon = true }.start()
    }

    private fun doScan(): AdbConnectionInfo? {
        val ip = wifiIpAddress()
        if (ip == null) {
            JdwpDebugLog.w("port-scan: could not determine Wi-Fi IP")
            return null
        }
        JdwpDebugLog.d("port-scan: scanning $ip for adb connect port…")

        // 1) Fast pass: find OPEN TCP ports in the adb ephemeral range.
        //    Android's wireless-adb connect port is a dynamic port; scan a
        //    broad-but-bounded range in parallel with short timeouts.
        val start = 30000
        val end = 49999
        val openPorts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        val futures = ArrayList<java.util.concurrent.Future<*>>()
        try {
            for (port in start..end) {
                val task = Runnable {
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(ip, port), 120)
                            openPorts.add(port)
                        }
                    } catch (_: Throwable) { /* closed */ }
                }
                futures.add(pool.submit(task))
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        JdwpDebugLog.d("port-scan: ${openPorts.size} open port(s): ${openPorts.sorted().joinToString().take(200)}")

        // 2) For each open port, try the actual adb handshake. The one that
        //    completes the adb protocol is the connect port.
        for (port in openPorts.sorted()) {
            try {
                AdbClient.openShell(ip, port, connectTimeout = 3000L, maxRetryCount = 1).use { _ ->
                    JdwpDebugLog.d("port-scan: adb handshake OK on $ip:$port")
                }
                return AdbConnectionInfo(ip, port)
            } catch (_: Throwable) {
                // not an adb port; keep looking
            }
        }
        JdwpDebugLog.w("port-scan: no adb connect port found in $start-$end")
        return null
    }

    private fun wifiIpAddress(): String? {
        // Prefer a real (non-loopback) site-local IPv4 address (Wi-Fi).
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
