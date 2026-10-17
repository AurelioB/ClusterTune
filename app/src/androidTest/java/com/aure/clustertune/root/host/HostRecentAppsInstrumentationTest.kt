package com.aure.clustertune.root.host

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.apps.PauseAppDetectionRule
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootShellExecutionMethod
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostRecentAppsInstrumentationTest {
    @get:Rule internal val exclusiveHost = PauseAppDetectionRule()

    @Test fun privilegedHistoryWorksWhileAppUsageAccessIsDenied() = assertHistory(privilegedHostTestResolver())

    @Test fun pServerHistoryWorksWhileAppUsageAccessIsDenied() {
        val method = PServerExecutionMethod()
        assumeTrue("PServer unavailable", method.probe().isAvailable)
        assertHistory(PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
    }

    @Test fun rootHistoryWorksWhileAppUsageAccessIsDenied() {
        val method = RootShellExecutionMethod()
        assumeTrue("app-facing root shell unavailable", method.probe().isAvailable)
        assertHistory(PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
    }

    @Test fun usageAccessIsNotDeclaredByTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.PACKAGE_USAGE_STATS"))
    }

    @Test fun failedHistoryLeavesTheFullAppPickerAvailableAndZeroLimitDoesNotQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var queries = 0
        val repository = InstalledAppRepository(context) {
            queries++
            Result.failure(IllegalStateException("host unavailable"))
        }
        assertTrue(repository.listRecentActiveApps(0).isEmpty())
        assertEquals(0, queries)
        assertTrue(repository.listRecentActiveApps().isEmpty())
        assertEquals(1, queries)
        assertTrue(repository.listLaunchableApps().isNotEmpty())
    }

    @Test fun recentListKeepsHostOrderAndFiltersUninstalledAppsBeforeApplyingTheLimit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val all = InstalledAppRepository(context) { Result.success(emptyList()) }.listLaunchableApps()
        assumeTrue(all.size >= 2)
        val first = all.last().packageName
        val second = all.first().packageName
        val repository = InstalledAppRepository(context) {
            Result.success(listOf("com.example.notinstalled.clustertune", first, first, second))
        }
        assertEquals(listOf(first), repository.listRecentActiveApps(1).map { it.packageName })
        assertEquals(listOf(first, second), repository.listRecentActiveApps(2).map { it.packageName })
    }

    private fun assertHistory(resolver: PrivilegedExecutionResolver) {
        assumeTrue("privileged execution unavailable", resolver.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val original = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        val originalMode = when (original) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        val client = ClusterTuneHostClient(context, resolver)
        var scenario: ActivityScenario<ComponentActivity>? = null
        try {
            hostTestShell("cmd appops set ${context.packageName} GET_USAGE_STATS ignore")
            val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            // Some versions retain DEFAULT when a permission is no longer requested.
            // DEFAULT is also denied when the app does not hold the underlying permission.
            assertTrue("ordinary app must have no Usage Access", mode == AppOpsManager.MODE_IGNORED ||
                mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_DENIED)
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
            var packages = client.readRecentAppPackages().getOrThrow()
            while (context.packageName !in packages && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100)
                packages = client.readRecentAppPackages().getOrThrow()
            }
            assertTrue("the newly opened app must be in privileged history", context.packageName in packages)
            val ordinaryAppCanReadEvents = try {
                context.getSystemService(UsageStatsManager::class.java)
                    .queryEvents(0, System.currentTimeMillis())?.hasNextEvent() == true
            } catch (_: SecurityException) { false }
            assertFalse("ordinary app must be unable to read the same history", ordinaryAppCanReadEvents)
            assertEquals(packages.distinct(), packages)
            assertTrue(packages.size <= HostProtocol.MAX_RECENT_APPS)
            assertTrue(InstalledAppRepository(context, client::readRecentAppPackages).listRecentActiveApps()
                .any { it.packageName == context.packageName })
        } finally {
            scenario?.close()
            client.stop()
            hostTestShell("cmd appops set ${context.packageName} GET_USAGE_STATS $originalMode")
        }
    }
}
