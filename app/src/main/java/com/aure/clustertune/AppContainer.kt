package com.aure.clustertune

import android.content.Context
import android.util.Log
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.GpuPolicyDetector
import com.aure.clustertune.data.SharedPreferencesGpuCeilingStore
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.host.ClusterTuneHostClient
import com.aure.clustertune.data.retryTransientReads
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("AppContainer", "Settings observer stopped", error)
    })

    val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(appContext)
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

    /** Shared so mutating Auto Tune and the read-only HUD use one privileged-host lease. */
    val hostClient: ClusterTuneHostClient by lazy {
        ClusterTuneHostClient(appContext, privilegedExecutionResolver)
    }

    // Keep the repository delegate ahead of init so startup work can never observe a partially
    // initialized dependency graph when a container is created by a background component.
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

    /** Owners with a finite lifecycle release the settings observer without stopping the shared host. */
    override fun close() { appScope.cancel() }

    init {
        appScope.launch {
            settingsStorage.settings.retryTransientReads { error ->
                Log.w("AppContainer", "Retrying settings read", error)
            }.collect { settings ->
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
    }
}
