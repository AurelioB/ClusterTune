# Wireless-debugging execution method

This document explains the no-root execution path added to ClusterTune, for a
reader who has never seen this code.

---

## 1. The problem

ClusterTune applies CPU/GPU frequency caps by writing sysfs nodes such as
`/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq`. Those writes need
privilege. Upstream provides two ways to get it:

- **root-shell** — `su`, on a rooted device.
- **pserver-stdout** — a vendor daemon present on some AYN and Retroid handhelds.

The AYN Odin 2 Mini has neither. It ships unrooted, and although its PServer
service is registered, every binder call to it is refused by SELinux:

```
avc: denied { call } scontext=u:r:untrusted_app tcontext=u:r:pservice
     tclass=binder permissive=0
```

So on that device ClusterTune had no way to apply anything.

## 2. The mechanism

The device's own vendor app, `com.odin2.gameassistant`, ships with
`android:debuggable="true"` while running as `sharedUserId="android.uid.system"`
— i.e. uid 1000. A debuggable process accepts a JDWP debugger, and a debugger
can make its target execute code. So: attach to that app, ask it to run a
command, and the command runs as uid 1000.

Nothing here is an exploit of the app. `debuggable` is a flag the vendor set, and
JDWP is Android's ordinary debugging protocol. The only unusual part is that the
debugger is running on the same device as its target, over Android's own
**Wireless debugging** feature (Settings → Developer options), rather than from a
PC.

```
ClusterTune ──adb over Wireless debugging──▶ adbd
     │                                        │
     └────── JDWP attach ─────────────────────┴──▶ com.odin2.gameassistant (uid 1000)
                                                      │
                                                      └── Runtime.exec("sh …") as uid 1000
```

## 3. What actually gets executed

Since ClusterTune 1.2.x, an execution method does **one** job: start the
privileged host process. It does not write sysfs itself.

```kotlin
interface PrivilegedExecutionMethod {
    val id: String
    fun probe(): ExecutionProbeResult
    fun launchHost(request: HostLaunchRequest): Result<Unit>
}
```

`ClusterTuneHostEntry` is launched via `app_process`, runs at the privileged uid,
and exposes a Binder interface. All reads, writes and applies then travel over
Binder. So this method injects exactly one command — a shell launcher — and
everything afterwards is upstream's existing host protocol.

`ClusterTuneHostEntry` already accepts uid 0 **or** uid 1000, so a system-uid
host is supported by design and needed no change.

### Why the launcher is rewritten

Upstream's launcher points `CLASSPATH` at dex files extracted into
`context.codeCacheDir`. That works for root, which bypasses permission checks
entirely. At uid=system it cannot work, for two independent reasons:

- **DAC** — `/data/user/0/<pkg>` is mode 0700 owned by the app's own uid. uid
  1000 is not the owner and has no `CAP_DAC_OVERRIDE`, so it cannot traverse into
  `code_cache` no matter how readable the dex files themselves are.
- **SELinux** — the injected process inherits GameAssistant's context,
  `u:r:system_app:s0`, which has no read access to `app_data_file`.

`JdwpHostExecutionMethod` therefore rewrites **only** the `CLASSPATH` assignment
to point at the APK (`applicationInfo.sourceDir`), which is labelled
`apk_data_file`, is world-readable, and sits on a traversable path. The APK
already contains the host class, so no dex extraction is needed on this path.
Every other argument the client computed — service name, owner uid, generation,
method id, package, handoff nonce — is copied through unchanged, so this keeps
working if upstream changes them.

The working directory moves to `Documents/ClusterScripts/host` because the
launcher redirects into `./host-startup.log` and uid=system must be able to
create that file.

## 4. Lifetime and adoption

Wireless debugging is only needed to **start** the host. Once running, the host
talks Binder and needs no network — so profiles keep applying with Wi-Fi off.

Two problems follow from that, both solved here:

**The host used to exit with the app.** It links a death recipient to the app's
lease binder and stops when the app dies. That is right for root, where starting
a replacement is free. It is wrong here, because a replacement needs wireless
debugging that the user has probably switched off. So a host started by this
method stays alive when its lease dies.

**An orphaned host could not be found again.** `HostRendezvous` is entirely
in-process (its map, its receiver and its nonce all die with the app) and the
host never registers with `ServiceManager`, so its one launch-time broadcast was
the only announcement.

The fix is a file handshake, borrowed from an earlier iteration of this project:

```
app starts ──▶ creates Documents/ClusterScripts/host/adopt-request
                            │
host (unattached) stats that file every 1.5s ──▶ deletes it, re-broadcasts once
                            │
app's rendezvous receiver picks up the binder ──▶ sends a new lease ──▶ attached
```

An **attached** host blocks on `wait(0)` and polls nothing at all, so this costs
nothing in the normal case.

## 5. Finding the connection

`NsdManager.discoverServices` is a passive listener. On this device nothing
answers its query, so it only ever reports `_adb-tls-connect._tcp` at the moment
adbd *announces* it — which in practice means when the user opens the Wireless
debugging screen. Measured: the service appeared 15s and 41s after discovery
started in two runs, tracking the user's actions, and never at all in a third.

Three strategies are therefore used in order:

1. **`MdnsQuery`** — sends a real DNS-SD query (a 45-byte packet to
   224.0.0.251:5353) with the unicast-response bit set, and parses the SRV
   record for the port. This asks rather than waits.
2. **Remembered ports** — adbd keeps the same connect port until wireless
   debugging is toggled or the device reboots, so ports that previously
   completed an adb handshake are tried first and forgotten when one fails.
3. **Port scan** — 30000–49999. Correct but expensive: 20,000 connect attempts,
   each of which the platform logs, so it is genuinely the last resort.

On this device (1) is currently unanswered, so (2) carries the common case.

## 6. Diagnostics

All diagnostics are **off by default**. `JdwpDebugLog.enabled` gates both the
in-memory buffer and the logcat mirror at a single choke point, so a release
build emits nothing.

Users turn it on with **Settings → Execution → Wireless debugging execution
method logging**, which only appears while this execution method is selected.
Two buttons then appear: **View log** (live, with copy and clear) and **Download
log**, which writes a timestamped file with device/OS/uid headers to
`Documents/ClusterScripts/logs/`. Turning the toggle off clears the buffer.

## 7. Files

| File | Role |
|---|---|
| `jdwp-injector/` | Vendored adb + JDWP transport (from `wuyr/jdwp-injector-for-android`, Apache-2.0) |
| `jdwp/JdwpHostExecutionMethod.kt` | The execution method: probe, launcher rewrite, injection |
| `jdwp/WirelessDebugConnectionManager.kt` | Pairing, discovery, connection state, shared adb shell |
| `jdwp/MdnsQuery.kt` | Active DNS-SD query |
| `jdwp/AdbConnectionInfo.kt` | Host/port of the local adbd |
| `ui/WirelessDebugSetupScreen.kt` | Pairing and connection UI |
| `ui/diagnostics/DiagnosticLog.kt` | Opt-in log viewer and export |

## 8. Limitations

- **Wireless debugging must be enabled once per boot.** Android refuses to enable
  it without a network, and this device has no mobile data or hotspot, so the
  first connection of each boot needs Wi-Fi. Everything after that works offline.
- **No GPU control.** Every GPU node on this device is `root:root` with no
  world-write bit, so uid=system can neither write nor chmod them. The host
  detects this at runtime and simply does not advertise a GPU domain; root and
  PServer users are unaffected.
- **This is device-specific in practice.** It requires a debuggable system-uid
  app to attach to. It is written generically, but the only confirmed target is
  `com.odin2.gameassistant`.
