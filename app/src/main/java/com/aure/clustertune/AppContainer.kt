package com.aure.clustertune

import android.content.Context
import android.util.Log
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.root.ExecutionMethodSysfsLister
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.PServerSysfsReader
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class AppContainer(context: Context) {
    private companion object {
        const val TAG = "AppContainer"
        const val SYSFS_REPAIR_ATTEMPTS = 5
        const val SYSFS_REPAIR_INITIAL_DELAY_MS = 250L
    }

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(appContext)
    }

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val installedAppRepository: InstalledAppRepository by lazy {
        InstalledAppRepository(appContext)
    }

    // Keep the repository delegate ahead of init so startup work can never observe a partially
    // initialized dependency graph when a container is created by a background component.
    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = PServerSysfsReader(
                    context = appContext,
                    executionResolver = privilegedExecutionResolver,
                ),
                privilegedLister = ExecutionMethodSysfsLister(privilegedExecutionResolver),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = ProfileStorage(appContext),
            settingsStorage = settingsStorage,
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(
                context = appContext,
                executionResolver = privilegedExecutionResolver,
            ),
        )
    }

    init {
        appScope.launch {
            settingsStorage.settings.collect { settings ->
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
        appScope.launch {
            repairSysfsMinimumsWithRetry()
        }
    }

    /**
     * Repairs stale minimum nodes independently from apply-on-boot. The first attempt waits
     * until the persisted execution method has been loaded, otherwise a freshly-created
     * container can race the settings collector and incorrectly conclude that no executor is
     * available. Failures are retried briefly because the privileged service may be starting
     * at the same time as an overlay, tile, or boot receiver.
     */
    private suspend fun repairSysfsMinimumsWithRetry() {
        // Ensure all repository dependencies are initialized only on the worker dispatcher and
        // that the resolver sees the configured method before probing it.
        var delayMs = SYSFS_REPAIR_INITIAL_DELAY_MS
        var settingsLoaded = false
        repeat(SYSFS_REPAIR_ATTEMPTS) { attempt ->
            val result = try {
                if (!settingsLoaded) {
                    val settings = settingsStorage.settings.first()
                    privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
                    settingsLoaded = true
                }
                repository.repairSysfsMinimumsIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.isSuccess) {
                Log.i(TAG, "Sysfs minimum repair completed")
                return
            }

            val error = result.exceptionOrNull()
            Log.w(
                TAG,
                "Sysfs minimum repair attempt ${attempt + 1}/$SYSFS_REPAIR_ATTEMPTS failed: " +
                    (error?.message ?: "unknown error"),
                error,
            )
            if (attempt < SYSFS_REPAIR_ATTEMPTS - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        Log.e(TAG, "Sysfs minimum repair was not completed in this app process")
    }
}
