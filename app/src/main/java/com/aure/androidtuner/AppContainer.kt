package com.aure.androidtuner

import android.content.Context
import com.aure.androidtuner.data.BundledPresetProvider
import com.aure.androidtuner.data.CpuPolicyDetector
import com.aure.androidtuner.data.PerformanceRepository
import com.aure.androidtuner.data.ProfileStorage
import com.aure.androidtuner.root.PerformanceCommandBuilder
import com.aure.androidtuner.root.RootCommandRunner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(),
            bundledPresetProvider = BundledPresetProvider(),
            profileStorage = ProfileStorage(appContext),
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(appContext),
        )
    }
}
