package com.aure.clustertune

import android.content.Context
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.GpuPolicyDetector
import com.aure.clustertune.data.SharedPreferencesGpuCeilingStore
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.host.ClusterTuneHostClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process-wide singleton. MainActivity, the overlay service and the boot
     * receiver each build their own AppContainer, so anything holding adb
     * connection state has to be shared or the instances silently disagree
     * about whether a connection exists.
     */
    val wirelessDebugConnectionManager: WirelessDebugConnectionManager
        get() = WirelessDebugConnectionManager.getInstance(appContext)

    val privilegedExecutionResolver: PrivilegedExecutionResolver
        get() = sharedResolver(appContext) {
            PrivilegedExecutionResolver.default(
            context = appContext,
            jdwpConnectionProvider = wirelessDebugConnectionManager.provider(),
            jdwpSharedShellProvider = { wirelessDebugConnectionManager.sharedShell() },
            jdwpShellInvalidator = { wirelessDebugConnectionManager.invalidateShell() },
            jdwpPersistentInjector = { pkg, command, pid, trigger ->
                wirelessDebugConnectionManager.injectExecPersistent(pkg, command, pid, trigger)
            },
                jdwpShellUseLock = wirelessDebugConnectionManager.shellUseLock,
            )
        }

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val installedAppRepository: InstalledAppRepository by lazy {
        InstalledAppRepository(appContext)
    }

    val profileStorage: ProfileStorage by lazy {
        ProfileStorage(appContext)
    }

    // Keep the repository delegate ahead of init so startup work can never observe a partially
    // initialized dependency graph when a container is created by a background component.
    /**
     * Hoisted out of [repository] so the UI can ask whether the privileged host
     * is up. The host and the adb connection are independent: once the host is
     * running it serves over Binder and needs no network, so connection state
     * alone would misreport a working setup as soon as Wi-Fi is turned off.
     */
    val hostClient: ClusterTuneHostClient
        get() = sharedHostClient(appContext, privilegedExecutionResolver)

    /** Cheap, non-blocking: does not attempt to start the host. */
    val isPrivilegedHostRunning: Boolean
        get() = hostClient.isRunning

    /**
     * Starts the privileged host if it is not already up.
     *
     * Blocking — call from a background dispatcher. On the jdwp path this
     * performs the JDWP attach and injection, then waits for the host's Binder
     * handoff broadcast, so it can take a couple of seconds.
     *
     * Safe to call repeatedly: [ClusterTuneHostClient.ensureStarted] returns
     * immediately when a live binder is already attached.
     */
    fun startPrivilegedHost(): Result<Unit> {
        val result = hostClient.ensureStarted()
        result.fold(
            onSuccess = {
                // Routed through the gated log so a release build stays silent.
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
                    "host: started via ${hostClient.selectedMethodId ?: "unknown"}",
                )
            },
            onFailure = {

                // Also to the in-app log: this is the copyable one, and a bare
                // "failed to start" in logcat told us nothing about why.
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.w(
                    "host: FAILED to start via ${hostClient.selectedMethodId ?: "no method"} — " +
                        "${it::class.java.simpleName}: ${it.message ?: "no message"}",
                )
            },
        )
        return result
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
            ),
            gpuDetector = GpuPolicyDetector(
                ceilingStore = SharedPreferencesGpuCeilingStore(appContext),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = profileStorage,
            settingsStorage = settingsStorage,
            hostClient = hostClient,
        )
    }

    init {
        // Adopt a host left running by a previous app process. Must happen before
        // anything asks whether a privileged executor is available, otherwise the
        // first check races the host's re-announcement and reports "not
        // available" for a host that is alive and well.
        // Detection must be able to see a host that is already running, which is
        // what makes Auto detect work with Wi-Fi off.
        privilegedExecutionResolver.runningHostMethodProvider = { hostClient.runningMethodId }

        runCatching {
            hostClient.listenForAdoption()
            // Ask an orphaned host to re-announce. Harmless when none is running:
            // the file simply sits there until a host consumes it or the next
            // launch overwrites it.
            com.aure.clustertune.jdwp.JdwpHostExecutionMethod.requestAdoption()
        }

        appScope.launch {
            settingsStorage.settings.collect { settings ->
                // Single point where the diagnostic switch is applied. Turning it
                // off also drops whatever the buffer already holds, so nothing
                // lingers in memory after the user opts out.
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.enabled = settings.wirelessDebugLoggingEnabled
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
    }

    private companion object {
        const val TAG = "AppContainer"

        /**
         * Process-wide singletons.
         *
         * MainActivity, the overlay service, the quick-settings tile and the boot
         * receiver each construct their own AppContainer. With per-instance
         * copies they each got their own resolver (so probe caches disagreed and
         * a freshly connected method could still read as "not selected") and
         * their own host client (so only the first could hold the host's lease —
         * the rest saw no binder, could not adopt because an *attached* host
         * never re-announces, and fell through to
         * `launchHost FAILED: Wireless debugging not connected` even though the
         * host was up and working).
         *
         * `WirelessDebugConnectionManager` was made a singleton for exactly this
         * reason; these two needed it just as much.
         */
        private val sharedLock = Any()

        @Volatile
        private var resolverInstance: PrivilegedExecutionResolver? = null

        @Volatile
        private var hostClientInstance: ClusterTuneHostClient? = null

        fun sharedResolver(
            context: Context,
            factory: () -> PrivilegedExecutionResolver,
        ): PrivilegedExecutionResolver = resolverInstance ?: synchronized(sharedLock) {
            resolverInstance ?: factory().also { resolverInstance = it }
        }

        fun sharedHostClient(
            context: Context,
            resolver: PrivilegedExecutionResolver,
        ): ClusterTuneHostClient = hostClientInstance ?: synchronized(sharedLock) {
            hostClientInstance ?: ClusterTuneHostClient(context, resolver).also {
                hostClientInstance = it
            }
        }
    }

}