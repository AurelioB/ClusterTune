# Privileged app detection — October 15, 2026

ClusterTune no longer declares or uses an accessibility service. Automatic app profiles,
Auto Tune ownership, app pickers, and the HUD get their app/display information from the
existing PServer/root host. Recent-app sorting also uses that host; the app no longer requests
Usage Access.

## Event flow

1. `AppDetectionService` owns the app-side profile coordinator and shares the existing
   foreground notification. Opening ClusterTune or an overlay starts it; boot and package
   replacement restart it when assignments, Auto Tune, or the edge picker need it.
2. The service subscribes through the owner-checked host Binder protocol. The privileged host
   registers an Android task-stack listener, using its own Binder identity.
3. Task changes schedule a query after 50 ms, coalescing event bursts without postponing an
   already scheduled query. Two finite settling queries run after 250 and 750 ms. Startup,
   reconnect, screen changes, and display changes also request observations. Healthy idle
   detection has no recurring app poll or wake lock.
4. A dedicated host thread queries visible tasks for the app owner's Android user. A separate
   query identifies the globally focused display, since multiple displays can each report a
   focused task. Slow reads do not hold the subscription/shutdown lock or block the app's main
   thread.
5. Bounded snapshots travel to the app through one-way callbacks. App-side normalization keeps
   the existing `VisibleAppSnapshot` contract, including apps on multiple displays and in
   split-screen, per-display picker targets, and refresh-rate information. The profile rules
   and hardware tuning algorithms remain shared with the previous implementation.

System UI, the keyboard, vendor assistants, and ClusterTune itself are filtered from targets.
If a permission dialog or vendor panel temporarily becomes the top activity in a game's task,
the known base app can preserve that task's identity. Missing refresh-rate information retains
the configured Auto Tune target instead of assuming 60 Hz or dropping the app. Off/disconnected
displays cannot contribute app windows.

## Recovery and lifecycle

- Host loss retires the subscription generation immediately. A success callback already queued
  by the old host cannot mark detection healthy or cancel reconnection.
- Failures clear app observations and pause Auto Tune. Transient host reads have three bounded
  retries; exhausted read retries remain unhealthy until a new event or reconnection succeeds.
- The app retries broken connections with backoff from 500 ms to 30 seconds. These are failure
  recovery attempts, not steady-state foreground-app polling. The first observation also has
  a ten-second deadline.
- Closing a subscription unregisters its listener without starting a host. App process/lease
  death and explicit host shutdown release detection resources along with the host's existing
  tuning cleanup. The app foreground service uses Android's sticky restart behavior.
- History records connection attempts, successful connections and failures. Native connection/error
  diagnostics use the `AppDetection` and `ClusterTuneHistory` tags. Settings has no app-detection
  status row or Retry button. Accessibility
  permission checks, settings links, binding-state warnings, the old service, and its XML
  declaration are removed. No accessibility grant list is rewritten.

Host protocol 14 adds observation/stop-observation operations and replaces older hosts using
the existing generation/version handoff. The hidden framework adapter resolves the device's
own listener transaction numbers rather than hardcoding Android's Binder transaction IDs.
It supports the three-argument Android 12/13 and four-argument Android 14–16 task query forms.
The restricted API lint suppression is limited to this privileged adapter.

## Recent-app history

Host protocol 15 adds an owner-checked recent-app query and replaces older hosts through the
same version handoff. The host clears the app's Binder identity and calls Android's usage
history service with its own privileged identity, for the app owner's Android user. It keeps
the latest activity-resumed event per package over the past seven days and returns at most
512 unique package names, most recent first. The app filters these against installed,
launchable apps before selecting the first five.

This is an on-demand read when the app list is loaded or refreshed, off the app's main thread.
It adds no timer, listener, or polling loop. History reads do not hold the hardware/lifecycle
lock. If history is temporarily unavailable, the full alphabetical app picker remains usable.
The manifest permission, Usage Access checks, and settings links have been removed; no grant
is added to the ordinary app.

## Validation of the Accessibility replacement

| Check | Result |
| --- | --- |
| JVM suite | 546 passed, 0 failures |
| Debug app and instrumentation builds | Passed |
| Android lint | 0 errors, 56 warnings; none in the new detector |
| Thor hardware checks, Android 13 | 20 passed, 1 conditional skip, 0 failures |
| Final installed-build recheck on Thor | 7 passed, 0 failures |
| Android 14 full instrumentation suite | 77 passed, 16 conditional skips, 0 failures |
| Android 16 detection/recovery suite | 8 passed, 2 conditional skips, 0 failures |

Per-test outcomes and skip reasons are retained in [validation.json](research/app-detection/validation.json).
The Thor's protected-frequency fixture was not opted into in this run. The normal PServer and
app-facing root handoff, telemetry, and watchdog fixtures ran successfully. An earlier broad
Thor UI run was interrupted by sleep-button events and also exposed test clients competing
with normal automation for the host. Direct host tests now pause that owner; the full UI
regression suite ran on the emulator, and the focused physical suite passed afterward.

The host tests cover real app transitions, multiple physical displays, PServer and app-facing
root execution, recovery after explicit host stops, sleep/wake, and a real app assignment that
uses the existing frequency ceilings. That assignment is restored afterward. Direct host
fixtures pause normal app automation while choosing their own execution method so the two
owners cannot replace each other's hosts.

Fault-injection tests cover transient and persistent read failures, recovery after a new
event, denied listener registration, listener cleanup, bounded wire payloads, no periodic
idle reads, and unsubscription while a framework query is blocked. JVM tests check display
ownership, split-screen contributors, removal of closed apps, transient panels, and unknown
refresh rates against the existing profile/Auto Tune selection rules.

Android 14 and 16 emulator task tests launch the unmodified production host as root through
an instrumentation shell bridge. Those emulator images provide adb root but do not have an
app-facing root manager. This validates the framework adapter on those versions; it does not
substitute for the physical app-facing `su` launch test. Android 12's interface shape was
checked against AOSP, but no Android 12 device was available for runtime testing.

Other firmware can still deny the required framework calls through its security policy. Such
a failure is reported as unavailable detection and can be retried; there is no accessibility
fallback. Android can also stop the app itself, including an explicit user force-stop.

The final build is installed on the Thor. Normal Settings reported app detection running, and
the original Large Underclock profile was reapplied after host-stop tests, restoring all three
CPU minimum-file modes to 0440. The other apps' accessibility grants were preserved.

## Validation of the Usage Access replacement

| Check | Result |
| --- | --- |
| JVM suite | 551 passed, 0 failures |
| Debug app and instrumentation builds | Passed |
| Android lint | 0 errors, 56 warnings; none in the new history adapter |
| Thor, Android 13: history, permissions, app-detection regression | 14 passed, 0 failures |
| Thor: final explicit ordinary-app denial check | 6 passed, 0 failures |
| Android 14: history, task transitions, permission screens | 9 passed, 4 conditional skips, 0 failures |
| Android 16: same focused checks | 9 passed, 4 conditional skips, 0 failures |

Both PServer and app-facing root returned history on the Thor while Usage Access was denied.
The final tests also query history as the ordinary app and verify that it cannot read events.
Android 14 and 16 exercise the same production host through the emulator shell bridge; their
PServer, app-facing root, and physical dual-display fixtures are conditional skips. Filtering,
ordering, duplicate removal, the response bound, and the alphabetical-picker fallback are
covered as well. Per-test outcomes and the initial test-environment corrections are retained
in [recent-apps-validation.json](research/app-detection/recent-apps-validation.json).

Framework references: [task manager interface](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/IActivityTaskManager.aidl),
[task notifications](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/app/ITaskStackListener.aidl),
and [task information](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/app/TaskInfo.java).
The history adapter uses [the usage service interface](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/usage/IUsageStatsManager.aidl).
