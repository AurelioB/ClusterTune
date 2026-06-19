package com.aure.clustertune

import android.content.Context
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.root.OdinScriptHandoff
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.PServerSysfsReader
import com.aure.clustertune.root.RootCommandRunner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val odinScriptHandoff: OdinScriptHandoff by lazy {
        OdinScriptHandoff(appContext)
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = PServerSysfsReader(appContext),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = ProfileStorage(appContext),
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(appContext),
        )
    }
}
