package com.aure.clustertune.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time-per-boot setup for the no-root "Wireless debug" execution method.
 *
 * Includes an on-screen live log (JdwpDebugLog) so pairing/connection issues
 * can be diagnosed on-device without adb or rebuilding.
 *
 * Split-screen + pairing approach adapted from
 * github.com/wuyr/jdwp-injector-for-android (Apache-2.0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDebugSetupScreen(
    connectionManager: WirelessDebugConnectionManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var devOptionsEnabled by remember { mutableStateOf(isDevOptionsEnabled(context)) }
    var status by remember { mutableStateOf("Not connected") }
    var pairingReady by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    // Start pessimistic: do not trust a possibly-stale connectionInfo. We verify
    // the transport is actually alive below and only then show "connected". This
    // is what fixes the contradictory "Not connected" + "✓ Ready" state after a
    // connection was lost or deleted in system settings.
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Set only when a connection is established *during this visit*. The
    // auto-return below must not fire just because we were already connected
    // when the screen opened — that made the screen flash "Connected" and bounce
    // straight back to the main screen, leaving no way to redo setup.
    var connectedThisVisit by remember { mutableStateOf(false) }

    // On open, do a real liveness check (off the main thread). If the connection
    // died externally (wireless debugging switched off) this clears it so the
    // pairing steps are shown again instead of a dead "you're ready" state.
    LaunchedEffect(Unit) {
        if (connectionManager.connectionInfo != null) {
            status = "Checking existing connection…"
            val alive = withContext(Dispatchers.IO) { connectionManager.verifyConnection() }
            if (alive) {
                connected = true
                connectionManager.connectionInfo?.let { info ->
                    status = "Connected (${info.host}:${info.port}). You're ready."
                }
            } else {
                connected = false
                status = "Previous connection is no longer active. Reconnect below."
            }
        }
    }

    // Once connected, briefly show success then return to the app automatically
    // (also brings ClusterTune back to fullscreen out of the split view).
    LaunchedEffect(connected, connectedThisVisit) {
        if (connected && connectedThisVisit) {
            kotlinx.coroutines.delay(900)
            onBack()
        }
    }

    // Live diagnostic log: mirror JdwpDebugLog into Compose state via its listener.
    var logLines by remember { mutableStateOf(JdwpDebugLog.snapshot()) }
    DisposableEffect(Unit) {
        JdwpDebugLog.setListener { logLines = JdwpDebugLog.snapshot() }
        onDispose { JdwpDebugLog.setListener(null) }
    }

    DisposableEffect(Unit) {
        devOptionsEnabled = isDevOptionsEnabled(context)
        JdwpDebugLog.d("setup screen opened; devOptions=$devOptionsEnabled")
        // Start connect discovery immediately and leave it running (wuyr does
        // this) so _adb-tls-connect._tcp is caught whenever it appears after
        // wireless debugging becomes active.
        connectionManager.startConnectDiscovery(
            onConnected = { info ->
                connected = true
                connectedThisVisit = true
                status = "Connected (${info.host}:${info.port}). You're ready."
            },
        )
        onDispose { connectionManager.stopAll() }
    }

    fun startConnect() {
        status = "Looking for wireless debugging…"
        JdwpDebugLog.d("startConnect() requested")
        connectionManager.startConnectDiscovery(
            onConnected = { info ->
                connected = true
                connectedThisVisit = true
                status = "Connected (${info.host}:${info.port}). You're ready."
            },
            onUnavailable = {
                status = "Wireless debugging not found yet. Make sure it's ON, then pair below."
            },
        )
        // mDNS connect discovery is unreliable on some networks, so if it hasn't
        // resolved within 3 seconds, fall back to the direct port scan (the
        // reliable path). Every caller of startConnect() — including the
        // automatic connect after a successful pairing — gets this fallback.
        scope.launch {
            var waited = 0
            while (waited < 3000 && !connected) {
                kotlinx.coroutines.delay(500)
                waited += 500
            }
            if (!connected) {
                JdwpDebugLog.d("startConnect(): mDNS timed out; falling back to port scan")
                status = "mDNS didn't respond; scanning directly…"
                connectionManager.scanForConnectPort { info ->
                    if (info != null) {
                        connected = true
                        connectedThisVisit = true
                        status = "Connected (${info.host}:${info.port}). You're ready."
                    } else {
                        status = "Couldn't connect. Make sure Wireless debugging is ON."
                    }
                }
            }
        }
    }

    fun startPairing() {
        status = "Waiting for the pairing dialog…"
        JdwpDebugLog.d("startPairing() requested")
        connectionManager.startPairingDiscovery(
            onPairingPortFound = { _, _ ->
                pairingReady = true
                status = "Pairing service found. Enter the 6-digit code below."
            },
            onLost = {
                pairingReady = false
                status = "Pairing dialog closed. Reopen 'Pair device with pairing code'."
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wireless debug setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This lets ClusterTune apply profiles without root, using Android's " +
                    "built-in Wireless debugging. You only need to pair once per boot."
            )

            Text(
                "Status: $status",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )

            if (connected) {
                Text("✓ Ready. ClusterTune can now apply profiles. Return and select a profile.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
                Spacer(Modifier.height(8.dp))
                // Safety net: never leave the user stuck on a "connected" screen.
                // If the connection is actually dead (e.g. wireless debugging was
                // switched off) this drops it and reveals the pairing steps again.
                OutlinedButton(
                    onClick = {
                        // clearConnection() closes adb/JDWP sockets. Doing that on
                        // the main thread froze the UI ("ClusterTune isn't
                        // responding") when the sockets were wedged, so it runs
                        // off-thread and the UI updates immediately.
                        connected = false
                        connectedThisVisit = false
                        status = "Connection cleared. Pair/connect again below."
                        scope.launch {
                            withContext(Dispatchers.IO) { connectionManager.clearConnection() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusHighlight(),
                ) {
                    Text("Reconnect / redo setup")
                }
            } else {
                if (!devOptionsEnabled) {
                    Text("1. Turn on Developer options first.")
                    OutlinedButton(
                        onClick = {
                            openBuildNumberForDeveloperUnlock(context)
                            devOptionsEnabled = isDevOptionsEnabled(context)
                        },
                        modifier = Modifier.fillMaxWidth().focusHighlight(),
                    ) {
                        Text("Open About phone (tap Build number 7×)")
                    }
                    OutlinedButton(
                        onClick = { devOptionsEnabled = isDevOptionsEnabled(context) },
                        modifier = Modifier.fillMaxWidth().focusHighlight(),
                    ) {
                        Text("I've enabled Developer options")
                    }
                } else {
                    Text("1. Open Wireless debugging (opens beside ClusterTune).")
                    OutlinedButton(
                        onClick = {
                            openWirelessDebugging(context)
                            startConnect()
                        },
                        modifier = Modifier.fillMaxWidth().focusHighlight(),
                    ) {
                        Text("Open Wireless debugging (split screen)")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "2. First time on this boot? In the system pane tap 'Pair device with " +
                            "pairing code', then tap Start pairing. ClusterTune connects " +
                            "automatically once pairing succeeds:",
                    )
                    OutlinedButton(
                        onClick = { startPairing() },
                        modifier = Modifier.fillMaxWidth().focusHighlight(),
                    ) {
                        Text("Start pairing")
                    }

                    if (pairingReady) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Enter the 6-digit code shown in the system pane:")
                                OutlinedTextField(
                                    value = pairingCode,
                                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                                    label = { Text("6-digit pairing code") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().focusHighlight(),
                                )
                                Button(
                                    onClick = {
                                        busy = true
                                        status = "Pairing…"
                                        scope.launch {
                                            var paired = false
                                            var errorMsg: String? = null
                                            withContext(Dispatchers.IO) {
                                                connectionManager.pair(
                                                    code = pairingCode,
                                                    onPaired = { paired = true },
                                                    onError = { errorMsg = it.message ?: "pairing failed" },
                                                )
                                            }
                                            busy = false
                                            if (paired) {
                                                pairingReady = false
                                                status = "Paired. Connecting…"
                                                // adbd re-announces the connect
                                                // service (on a NEW port) right
                                                // now. Relisten so mDNS actually
                                                // catches it — a discovery session
                                                // started earlier will never see
                                                // an announcement it already
                                                // missed. Also drop the stale
                                                // connection so the old port
                                                // can't short-circuit the connect.
                                                connected = false
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        connectionManager.clearConnection()
                                                    }
                                                }
                                                connectionManager.restartConnectDiscovery(
                                                    onConnected = { info ->
                                                        connected = true
                                                        connectedThisVisit = true
                                                        status = "Connected (${info.host}:${info.port}). You're ready."
                                                    },
                                                )
                                                startConnect()
                                            } else {
                                                status = "Pairing failed: ${errorMsg ?: "check the code and try again"}"
                                            }
                                        }
                                    },
                                    enabled = pairingCode.length == 6 && !busy,
                                    modifier = Modifier.fillMaxWidth().focusHighlight(),
                                ) {
                                    Text("Pair")
                                }
                            }
                        }
                    }
                }
            }

            // ---- live diagnostic log (so we can debug without adb/rebuilds) ----
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val clipboard = LocalClipboardManager.current
                    Row2(
                        left = { Text("Diagnostic log", style = MaterialTheme.typography.titleSmall) },
                        right = {
                            androidx.compose.foundation.layout.Row {
                                TextButton(onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(logLines.joinToString("\n")))
                                }) { Text("Copy") }
                                TextButton(onClick = { JdwpDebugLog.clear() }) { Text("Clear") }
                            }
                        },
                    )
                    val logText = logLines.joinToString("\n").ifEmpty { "(no log yet)" }
                    Text(
                        text = logText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun Row2(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        left()
        right()
    }
}

private fun isDevOptionsEnabled(context: Context): Boolean {
    return Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        if (Build.TYPE == "eng") 1 else 0,
    ) != 0
}

/**
 * Try to open the Wireless debugging page directly (device-specific), falling
 * back to the Developer options screen. Launched in split screen next to
 * ClusterTune so the pairing dialog stays visible.
 */
/**
 * Opens Android's Wireless debugging page.
 *
 * Order matters. The explicit ComponentName route (tried first previously) does
 * not resolve on some vendor ROMs — including the AYN Odin build, where the log
 * showed "direct wireless-debugging page not found" — so we now try the public
 * action string first, which is the documented way to reach this page on
 * Android 11+.
 *
 * NOTE: the ":settings:fragment_args_key" extra used in the fallbacks is an
 * undocumented AOSP internal (it is what Settings search uses to scroll to and
 * highlight a row). It is best-effort: harmless if the ROM ignores it.
 */
private fun openWirelessDebugging(context: Context) {
    val candidates = listOf(
        // 1. Public action for the wireless-debugging screen (Android 11+).
        Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
        // 2. Explicit AOSP component, for ROMs that don't declare the action.
        Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$WirelessDebuggingActivity",
            )
        },
        // 3. Developer options, scrolled to + highlighting the Wireless
        //    debugging row so the user only has to tap it.
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(
                ":settings:show_fragment_args",
                android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                },
            )
        },
        // 4. Plain developer options.
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )
    for ((index, intent) in candidates.withIndex()) {
        if (context.packageManager.resolveActivity(intent, 0) != null) {
            JdwpDebugLog.d("openWirelessDebugging: using candidate #${index + 1}")
            openAdjacent(context, intent)
            return
        }
    }
    JdwpDebugLog.w("openWirelessDebugging: no settings activity resolved")
}

/**
 * Opens the device-info page with the Build number row highlighted, so the user
 * can immediately tap it seven times to unlock Developer options. Android has no
 * public API to jump straight to Build number; the highlight extra below is the
 * same undocumented mechanism Settings search uses, so treat it as best-effort.
 */
private fun openBuildNumberForDeveloperUnlock(context: Context) {
    val args = android.os.Bundle().apply {
        putString(":settings:fragment_args_key", "build_number")
    }
    val candidates = listOf(
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "build_number")
            putExtra(":settings:show_fragment_args", args)
        },
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for ((index, intent) in candidates.withIndex()) {
        if (context.packageManager.resolveActivity(intent, 0) != null) {
            JdwpDebugLog.d("openBuildNumber: using candidate #${index + 1}")
            openAdjacent(context, intent)
            return
        }
    }
}

/**
 * Launch [intent] in split screen adjacent to ClusterTune (windowingMode=3),
 * so the system pairing dialog stays visible while the user types the code.
 */
private fun openAdjacent(context: Context, intent: Intent) {
    // FLAG_ACTIVITY_LAUNCH_ADJACENT requires NEW_TASK + MULTIPLE_TASK to place
    // the launched activity in the adjacent split-screen slot. (Removing
    // NEW_TASK breaks split screen -> it opens full screen.)
    intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
    )
    val options = Bundle().apply { putInt("android.activity.windowingMode", 3) }
    runCatching { context.startActivity(intent, options) }
        .onFailure {
            JdwpDebugLog.w("openAdjacent failed; falling back to plain Settings", it)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}
