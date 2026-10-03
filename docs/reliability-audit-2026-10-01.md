# Reliability audit, October 1, 2026

This audit covers the current working tree, including the existing uncommitted Auto Tune, foreground-window, HUD, and notification work. Those changes were preserved. It is a regression assessment, not a claim that every OEM kernel and Android version has been certified.

## GitHub issues reviewed

All 12 open issues in `AurelioB/ClusterTune` were read. The actionable reliability reports are:

| Issue | Assessment |
| --- | --- |
| [#28: accessibility malfunction after removing the app from recents](https://github.com/AurelioB/ClusterTune/issues/28) | Enabled access and a connected accessibility service are different states. Added live connection tracking, cleanup on unbind, a three-second binding grace period, and a distinct recovery message. A normal recents swipe on the Thor retained both the grant and the connection. The RP5-specific failure is not reproduced or declared fixed. |
| [#19: quick tuner closes or selects the vendor assistant](https://github.com/AurelioB/ClusterTune/issues/19) | Existing working-tree changes already filter vendor assistants and preserve foreground identity across transient panels. Foreground resolver, picker, overlay, and UI regressions were exercised. |
| [#21: last profile not restored after boot](https://github.com/AurelioB/ClusterTune/issues/21) | Existing bounded startup retries and explicit execution-method selection have regression coverage. No physical reboot was performed in this audit. |
| [#26: CPU maximum mutation fails](https://github.com/AurelioB/ClusterTune/issues/26) | Current host reports the failing operation and node rather than an empty shell output, verifies writes, and rolls back failures. Unit coverage includes rejected writes and OEM minimum conflicts. The Zenfone/KernelSU and RP5 custom-kernel cases still require those devices. |
| [#30: Odin 3 CPU minimums rise after profile application](https://github.com/AurelioB/ClusterTune/issues/30) | Auto Tune is maximum-only; live Thor tests verify that its adaptive writes preserve minimums. Manual profile application is a separate path that can repair a minimum which exceeds a requested maximum. This does not establish the cause of the Odin 3 firmware behavior; that report remains unresolved. |

The other open issues are feature/support requests: [#29](https://github.com/AurelioB/ClusterTune/issues/29) per-app refresh rate, [#27](https://github.com/AurelioB/ClusterTune/issues/27) governor/minimum controls, [#25](https://github.com/AurelioB/ClusterTune/issues/25) controller keepalive, [#22](https://github.com/AurelioB/ClusterTune/issues/22) Shizuku, [#13](https://github.com/AurelioB/ClusterTune/issues/13) GPU/power-target controls, [#6](https://github.com/AurelioB/ClusterTune/issues/6) watt limits, and [#3](https://github.com/AurelioB/ClusterTune/issues/3) Odin 2 Mini access. No issues were closed or commented on.

## GameNative comparison

Compared the previous audit revision `627bf6f8d90fc4cca507e89664ed4df26f06a38e` with current master `375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9` (92 intervening commits). There are no new changes to the cluster decision engine in this interval.

* [#1934 / `27350bff`](https://github.com/utkarshdalal/GameNative/commit/27350bffc25438980ce093e356c946ed75eef347): makes CPU discovery complete before use, moves initialization off the UI thread, and avoids stopping an uninitialized driver. ClusterTune already serializes host startup/discovery and runs repository hardware I/O on its I/O dispatcher; its topology is host-instance state.
* [#2022 / `962ae85d`](https://github.com/utkarshdalal/GameNative/commit/962ae85d1e7aad3f3f6d220e6d2898441f78d5fd): detaches the recovery watchdog, works around PServer's 255-byte command limit, and recovers dirty sessions. Applied the relevant detachment behavior to ClusterTune's host: `nohup setsid`, redirected stdin/stdout/stderr, with arguments in the existing script. Added a UTF-8 byte-length guard on the short PServer launch command. A shell integration test verifies separate session IDs, detached stdin, and literal arguments/classpaths containing quotes and shell syntax.
* ClusterTune already has a host-owned heartbeat watchdog, lease-death cleanup, and retries which retain ownership/checkpoint evidence until restoration completes. Its recovery remains ownership-aware rather than copying GameNative's unconditional disk restore commands.
* [#2027 / `4e38a49e`](https://github.com/utkarshdalal/GameNative/commit/4e38a49ed094773fd824fa0e5ce91b4eb005d425): power telemetry for analytics/configuration reporting is not an auto-tuning correction and was not imported.

No upstream source was copied. GameNative-specific frame limiting, fan control, Wine affinity, telemetry uploads, and container-specific warm-start persistence were not introduced.

## Efficiency cores and responsiveness

[GameNative's ClusterTuner](https://github.com/utkarshdalal/GameNative/blob/375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9/app/src/main/java/app/gamenative/powercontrol/autotuning/ClusterTuner.kt) explicitly leaves efficiency cores and minimum frequencies alone. ClusterTune already excludes the unique lowest stable maximum-frequency policy on a confidently identified three-or-more-policy topology. On the Thor this is policy0; performance/prime policies and the GPU remain adjustable. Ambiguous and one/two-policy topologies retain their supported policies rather than treating policy0 as universally untouchable.

This is a conservative default, not proof that efficiency-core underclocking never saves energy. Android distributes work using scheduler load/capacity and vendor task policies; touch and UI work are not exclusively owned by the little cluster. [Android's capacity-jank documentation](https://source.android.com/docs/core/tests/debug/jank_capacity) describes foreground/touch boosts toward big cores, while [Linux EAS documentation](https://kernel.org/doc/html/latest/scheduler/sched-energy.html) explains workload-dependent tradeoffs.

It is also not a rule for every Snapdragon: [8 Gen 2](https://www.qualcomm.com/news/onq/2022/11/new-snapdragon-8-gen-2-8-extraordinary-mobile-experiences-unveiled) has prime, performance, and efficiency cores, whereas [Oryon/8 Elite](https://www.qualcomm.com/processors/oryon) uses prime and performance cores.

The exclusion keeps the efficiency cluster at its assigned baseline, not necessarily Stock. A manually chosen underclock profile can still lower it. No saved profiles or bundled presets were silently rewritten in this audit.

## Persistent notification

Overlay/HUD, sleep monitoring, and boot restoration now use the same foreground-start helper, notification ID, and channel. The existing single-notification work is retained and updates alert only once. Android's service manager keeps a shared-ID notification until its last foreground service stops; see [ActiveServices](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/services/core/java/com/android/server/am/ActiveServices.java).

New device integration tests start the actual overlay and sleep services together, check that exactly one notification is present, then stop the services in both orders. Each checks that the notification survives the first stop and disappears after the last. Test settings are restored in `finally`.

## Verification and remaining limits

* 546 JVM tests pass with no failures or skips. Coverage includes profile serialization/import/export, saved assignments and multi-display selection, boot retry, sleep-state resolution, CPU/GPU detection and transactional rollback, auto-tune recovery and stale-frame handling, host protocol/lifecycle, HUD/overlay presentation, tile state, settings, and update selection/validation.
* Debug app and instrumentation APK builds pass. Android lint passes. The installed Gradle 9.5.1 distribution and the existing JDK 21 daemon toolchain were used; project toolchain files were not changed.
* The Thor's PServer handoff tests pass with live SurfaceFlinger sampling, a real adaptive reduction, unchanged CPU/GPU minimums, efficiency-policy exclusion, and restoration. Root-shell execution is unavailable on this Thor and is skipped, not counted as verified.
* The final device suite has 68 passes and one root-only skip. A separately added watchdog integration test also passes: it lowers a GPU ceiling, stops heartbeats, waits seven seconds without session calls, and verifies that the host independently restored the exact baseline and reports an expired, fully restored session. Total executed device tests: 69 passes, one skip, zero outstanding failures. CPU reductions were unavailable under the Thor's current minimum votes, so that watchdog test exercised GPU restoration.
* Initial device UI failures occurred after the device's 60-second sleep timeout; the rerun uses a temporary longer timeout. The first lint attempt also encountered an internal Kotlin analysis error; a separate serial lint run passes without disabling rules.
* After instrumentation disconnected accessibility, the real main screen displayed the new service-specific guidance. Reconnecting the already-authorized service cleared the warning without reopening the activity; the enabled-service list was preserved. The installed APK and app data were backed up before testing. The tested build remains installed, the original 60-second timeout is restored, and the Thor was returned to sleep. No crash-buffer entries appeared during the checks.
* Device-specific RP5/Odin 3/Zenfone reports, cold boot, long gaming sessions, and battery/latency improvements need separate hardware/workload measurements. Passing synthetic frame tests is not a game benchmark.
