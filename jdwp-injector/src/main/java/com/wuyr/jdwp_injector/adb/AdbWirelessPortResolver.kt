package com.wuyr.jdwp_injector.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.ServiceInfoCallback
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.wuyr.jdwp_injector.debug.JdwpDebugLog

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-04-13 6:07 PM
 */
class AdbWirelessPortResolver private constructor(private val onLost: () -> Unit, private val onResolved: (String, Int) -> Unit) : NsdManager.DiscoveryListener {

    private lateinit var nsdManager: NsdManager

    companion object {

        fun Context.resolveAdbWirelessConnectPort(onLost: () -> Unit = {}, onResolved: (String, Int) -> Unit) = resolveAdbPort("_adb-tls-connect._tcp", onLost, onResolved)

        fun Context.resolveAdbTcpConnectPort(onLost: () -> Unit = {}, onResolved: (String, Int) -> Unit) = resolveAdbPort("_adb._tcp", onLost, onResolved)

        fun Context.resolveAdbPairingPort(onLost: () -> Unit = {}, onResolved: (String, Int) -> Unit) = resolveAdbPort("_adb-tls-pairing._tcp", onLost, onResolved)

        private fun Context.resolveAdbPort(serviceType: String, onLost: () -> Unit, onResolved: (String, Int) -> Unit) = AdbWirelessPortResolver(onLost, onResolved).apply {
            nsdManager = getSystemService(NsdManager::class.java).also {
                it.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, this)
            }
        }
    }

    fun stop() {
        if (discoveryStarted) {
            discoveryStarted = false
            nsdManager.stopServiceDiscovery(this)
        }
    }

    private var discoveryStarted = false

    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        discoveryStarted = false
        JdwpDebugLog.w("discovery start FAILED for $serviceType (errorCode=$errorCode)")
    }

    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        discoveryStarted = false
        JdwpDebugLog.w("discovery stop failed for $serviceType (errorCode=$errorCode)")
    }

    override fun onDiscoveryStarted(serviceType: String) {
        discoveryStarted = true
        JdwpDebugLog.d("discovery started: $serviceType")
    }

    override fun onDiscoveryStopped(serviceType: String) {
        discoveryStarted = false
        JdwpDebugLog.d("discovery stopped: $serviceType")
    }

    private var foundServiceName = ""

    /**
     * Service names with a resolve currently in flight. NsdManager only permits
     * one resolve at a time, and onServiceFound is routinely delivered more than
     * once for the same service — the duplicates were each starting a resolve,
     * colliding with FAILURE_ALREADY_ACTIVE and then failing outright. Skipping
     * duplicates removes that self-inflicted collision entirely.
     */
    private val resolvesInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        foundServiceName = serviceInfo.serviceName
        JdwpDebugLog.d("service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
        if (!resolvesInFlight.add(serviceInfo.serviceName)) {
            JdwpDebugLog.d("resolve already in flight for ${serviceInfo.serviceName}; skipping duplicate")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, object : ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    JdwpDebugLog.w("serviceInfo callback registration failed (errorCode=$errorCode)")
                    resolvesInFlight.remove(serviceInfo.serviceName)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: "127.0.0.1"
                    JdwpDebugLog.d("resolved (14+): $host:${serviceInfo.port}")
                    onResolved(host, serviceInfo.port)
                    resolvesInFlight.remove(serviceInfo.serviceName)
                    nsdManager.unregisterServiceInfoCallback(this)
                }

                override fun onServiceLost() {
                    resolvesInFlight.remove(serviceInfo.serviceName)
                    nsdManager.unregisterServiceInfoCallback(this)
                }

                override fun onServiceInfoCallbackUnregistered() {
                }
            })
        } else {
            resolveLegacy(serviceInfo, attempt = 0)
        }
    }

    /**
     * Legacy resolveService path (Android < 14). NsdManager can only resolve
     * one service at a time; concurrent resolves fail with errorCode 3
     * (FAILURE_ALREADY_ACTIVE). Retry a few times with backoff to ride that out.
     */
    private fun resolveLegacy(serviceInfo: NsdServiceInfo, attempt: Int) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (errorCode == 3 /* FAILURE_ALREADY_ACTIVE */ && attempt < 8) {
                    JdwpDebugLog.d("resolve busy (ALREADY_ACTIVE), retry ${attempt + 1} for ${serviceInfo.serviceName}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        resolveLegacy(serviceInfo, attempt + 1)
                    }, 300L)
                } else {
                    JdwpDebugLog.w("resolve FAILED for ${serviceInfo.serviceName} (errorCode=$errorCode, attempt=$attempt)")
                    resolvesInFlight.remove(serviceInfo.serviceName)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                JdwpDebugLog.d("resolved: $host:${serviceInfo.port}")
                resolvesInFlight.remove(serviceInfo.serviceName)
                onResolved(host, serviceInfo.port)
            }
        })
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        if (discoveryStarted && foundServiceName == serviceInfo.serviceName) {
            onLost()
        }
    }
}