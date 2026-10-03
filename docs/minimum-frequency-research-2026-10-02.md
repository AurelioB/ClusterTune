# Minimum-frequency override research — AYN Thor

## Result

On the tested Thor firmware, changing governors does **not** prevent minimum-frequency overrides. AYN's `/system/bin/pservice` has its own `cpu_init` thread that periodically rewrites CPU minimums. We traced those writes and matched their values to the actual device executable.

A targeted alternative **worked in the bounded tests**: write a supported minimum, then remove all write bits from its sysfs node. The minimums stayed at 307.2 / 499.2 / 595.2 MHz across 40 samples per cluster, and through a separate sleep/wake check. Restoring writable permissions let the firmware raise them again. This is a candidate for controlled minimum ownership, not a universal ban on CPU boosts.

No production behavior was changed for this research. Governors, writable minimum-node modes, and the original requested baseline were restored. Firmware can subsequently update the effective minimums normally. No thermal service, power HAL, display service, root installation, or persistent performance property was changed.

## Device and method

- AYN Thor, Android 13, firmware fingerprint `qti/kalama/kalama:13/TKQ1.231222.001/eng.Thor.20260206.163241:user/release-keys`.
- Kernel `5.15.123-android13-8-gafd857749d1f`; driver `qcom-cpufreq-hw`.
- CPU policies 0 (cores 0–2), 3 (cores 3–6), 7 (core 7).
- Original governor: `walt` for every policy. Available: `walt conservative powersave performance schedutil`.
- Existing CPU ceilings preserved: 1,459,200 / 1,785,600 / 1,843,200 kHz. These are an existing underclock, so results are not a stock-performance benchmark.
- Initial observed minimums: 902,400 / 1,785,600 / 1,843,200 kHz; policy3 also naturally returned to the OEM's 1,651,200 kHz during observations.
- Prepared, bounded scripts ran through ClusterTune's existing PServer execution path, using a temporary instrumentation-only bridge. Restoration traps covered governor/minimum changes. The temporary test source and installed probe were removed afterward.
- ClusterTune's own automation was not running during the frequency experiments. 3C's main app was stopped for the controlled comparisons. Other installed utilities were not disabled; direct tracing and executable inspection establish the OEM writer independently.
- Samples used a 0.5-second sleep plus file-read overhead. They are sequential observations, not randomized benchmarks. No battery or gameplay-performance benefit was measured.

## Governor and 3C results

3C All-in-One Toolbox (`ccc71.at.free`, 3.2.3b, with its Pro companion installed) successfully changed policy0 to `schedutil`; a direct kernel read confirmed it. Selecting a 441.6 MHz minimum also changed the underlying setting. Thus this installation has working privileged controls, regardless of the ordinary ADB shell's `su` access. 3C's [CPU Manager documentation](https://3c71.com/wp/toolbox/online-help/cpu-manager/) describes these controls as requiring root.

After that change, policy0's minimum node was read-only: our first direct PServer write failed with `Permission denied`. This first exploratory pass is not treated as a successful minimum-write test. Explicitly restoring writable mode before each subsequent write made those writes succeed.

The longer controlled run changed all three governors together, set supported low minimums once, then observed 24 samples per governor:

| Governor | policy0 effective minimum range (MHz) | policy3 range (MHz) | policy7 range (MHz) |
| --- | ---: | ---: | ---: |
| walt | 307.2–902.4 | 499.2–1,651.2 | 595.2–1,843.2 |
| schedutil | 307.2–902.4 | 499.2–1,651.2 | 595.2–1,843.2 |
| conservative | 307.2–902.4 | 499.2–1,651.2 | 595.2–1,843.2 |
| powersave | 307.2–902.4 | 499.2–1,651.2 | 595.2–1,843.2 |

See [governor samples](research/minimum-frequency/long-governors.csv). One Home-key interaction occurred during the sequence; the purpose is to demonstrate overrides under each governor, not compare their override rates. `performance` was not needed: its purpose is to request the highest permitted frequency.

A governor chooses operating frequencies inside the applicable policy limits. `powersave` can select the lowest allowed frequency but cannot lower a floor imposed elsewhere. `schedutil` may also boost on scheduler signals. See the [Linux CPUFreq documentation](https://docs.kernel.org/admin-guide/pm/cpufreq.html).

## Identified firmware writer

An isolated ftrace instance recorded `pm_qos_update_target`, `cpu_frequency_limits`, and process creation. The processes raising the three floors were shells spawned directly by `pservice` thread 1503, in process 1439. The [focused trace](research/minimum-frequency/pservice-writer-trace.txt) records the changes from 307,200 to 902,400, 499,200 to 1,651,200, and 595,200 to 1,843,200 kHz.

The executable copied from the device has SHA-256:

`8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4`

Its embedded debug-symbol data names `cpu_init` at address `0x3d08`. Inspection of the Thumb instructions and referenced strings shows:

1. Wait for boot completion, then an initial delay controlled by `persist.sys.performance` (default 10 seconds). That property is **not** an off switch for the subsequent enforcement loop.
2. Poll `persist.vendor.debug.mode` and `debug.tracing.screen_state` every second.
3. While screen state is 1, skip profile writes. Otherwise, apply immediately on a mode change and approximately every ten iterations when the mode is unchanged.
4. Apply these CPU minimums, plus CPU maxima, GPU power-level bounds and DDR requests:

| AYN mode property | policy0 minimum (MHz) | policy3 minimum (MHz) | policy7 minimum (MHz) |
| --- | ---: | ---: | ---: |
| 0 | 902.4 | 1,651.2 | 1,843.2 |
| 1 | 1,228.8 | 2,054.4 | 2,476.8 |
| 2 | 2,016.0 | 2,803.2 | 3,187.2 |

The device was already in mode 0. These writes are in the service's own periodic loop; a third-party Binder request is not required to trigger them. This provides a concrete cause for this Thor's behavior without attributing it to 3C or another installed utility.

The binary also skips the frequency-write cases for other mode values. Assigning an undocumented value, falsifying screen state, or stopping the whole service is not an appropriate general fix: other firmware components consume these properties, and ClusterTune itself uses PServer. None of those workarounds was applied.

## Read-only minimum protection

With `walt` retained throughout, we compared the same low minimums with mode 0444 versus mode 0660:

| Minimum-node mode | Samples per policy | policy0 (MHz) | policy3 (MHz) | policy7 (MHz) |
| --- | ---: | ---: | ---: | ---: |
| 0444, read-only | 40 | 307.2 throughout | 499.2 throughout | 595.2 throughout |
| 0660, writable | 40 | rose to 902.4 | rose to 1,651.2 | rose to 1,843.2 |

See [permission comparison samples](research/minimum-frequency/locked-minima.csv). The read-only phase spans multiple expected OEM rewrite intervals. A separate check preserved all three low minimums and mode 0444 while asleep for four seconds and twelve seconds after wake. Ceilings and governors remained unchanged in that check.

Why this can block a root process: sysfs enables `KERNFS_ROOT_EXTRA_OPEN_PERM_CHECK`. The open path rejects a new writable open when the inode has no write bits, independently of the usual root DAC bypass. Relevant related-device kernel sources:

- [sysfs root creation](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/fs/sysfs/mount.c#L101)
- [kernfs extra open check](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/fs/kernfs/file.c#L615)

These sources explain the observed mechanism; the LineageOS branch is not asserted to be a byte-identical build of this stock kernel. The direct failed writes and protected/unprotected device comparison are the evidence for this firmware.

This is limited protection. A writer that first restores permissions, an already-open descriptor, node recreation, or another kernel frequency request can bypass it. ClusterTune would need to restore write access before its own future changes and manage ownership and restoration explicitly. A read-only-node failure must not be presented as a lost privilege grant.

## Independent frequency votes and other leads

`scaling_min_freq` is the effective policy minimum, not a reliable record of only our last request. In the related kernel, writing it updates one frequency-QoS request; reading it reports the policy value after requests are combined and validated. A stronger minimum request can remain despite a successful lower write. See [CPUFreq request handling](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/cpufreq.c#L737).

Thor's actual Qualcomm resource configuration maps CPU minimum boosts to `/sys/kernel/msm_performance/parameters/cpu_min_freq`, a separate node. The related [MSM performance driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/msm_performance.c#L323) maintains separate QoS requests. The [WALT input booster](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/kernel/sched/walt/input-boost.c#L61) does too. On the device, its configured input boost was 1,228,800 kHz for CPU0 for 100 ms. Read-only protection of `scaling_min_freq` does not disable these mechanisms, or thermal limits elsewhere.

[Jesty Thor Fix](https://github.com/JestyLabs/Jesty-Thor-Fix) reports a distinct AYN Dashboard high-clock problem and uses `vendor.display.disable_system_load_check=1`, followed by a compositor restart. The Thor's `libsdmextension.so` contains that property, `CheckSystemLoad`, and performance-hint acquisition/release paths, so the mechanism exists on this firmware. Its authors report improvement, but we did not apply this fix or restart the displays. It should not be conflated with the periodic PServer minimum writes proven here.

[Wayfinder's current performance controller](https://github.com/Thor-Wayfinder/thor-wayfinder/blob/main/app/src/main/java/app/wayfinder/PerfProfiles.kt) explicitly avoids changing AYN performance mode when ClusterTune/Pulse is installed; its README says compatibility changed in 1.4. Old Wayfinder/ThorSwapper packages are present on this device, but we did not update them or attribute the traced PServer timer to them. Avoiding concurrent owners remains useful independently of the firmware fix.

## Recommended ClusterTune direction

1. Keep `walt` as the default. Governor replacement did not solve the identified override, and no game-performance evidence supports changing it.
2. Preserve maximum-only Auto Tune. Do not turn minimum fluctuations into permission-loss warnings or repeatedly chase them with writes.
3. For manual profiles that intentionally repair/own a minimum, evaluate explicit minimum protection using the same reversible ownership model as ceilings: save modes, unlock once, write once, protect, verify later, restore on release/failure. Scope initial support to confirmed firmware and make the behavior visible to the user. The present manual repair path leaves minimums writable, so it permits this OEM loop to undo a repair.
4. Separate write failure, changing permissions, an external writer, and a higher independent QoS constraint in diagnostics. Saving an effective boosted minimum and later rewriting it can itself turn a transient vote into a lasting request; avoid treating every observed minimum as our own saved configuration.
5. Do not add a rapid reapply loop, globally disable performance/thermal services, fake screen state, or ship a binary patch. The bounded read-only approach is both more targeted and empirically supported here.

Before enabling minimum protection broadly, validate crash/watchdog restoration, profile transitions, external-tool conflicts, reboot/node recreation, real games, and battery impact. This research does not establish the same service implementation or protection behavior on Odin 3.

## Follow-up protection validation

At the user's request, ran a broader physical-device test matrix on the same Thor and firmware. The starting minimums were 902,400 / 1,651,200 / 1,843,200 kHz, all minimum modes 0660; governors and ceiling values matched the earlier research.

| Check | Observation |
| --- | --- |
| Direct privileged write against each protected node | All three failed with `Permission denied`, as expected. Privileged reads and subsequent unlock operations still worked. |
| Sustained hold | 60 samples per policy over 94.32 seconds: effective minimums stayed at 307,200 / 499,200 / 595,200 kHz and mode 0444 throughout. |
| Upward frequency scaling | Frequencies could still reach the existing ceilings with minimum protection enabled. This is not a responsiveness benchmark: one of the three attempted per-core load affinities was rejected, and the privileged service had a restricted CPU affinity. |
| Three sleep/wake cycles | Every asleep and post-wake sample retained the low minimums, mode 0444, original ceilings and `walt`. Each cycle included three seconds asleep and eleven seconds after wake. |
| Deliberate reconfiguration | Unlocking, writing the next supported step, and relocking succeeded for every policy. After twelve seconds the effective minimums were 441,600 / 614,400 / 729,600 kHz. |
| Release | Restoring mode 0660 allowed AYN's original 902,400 / 1,651,200 / 1,843,200 kHz floors to return within the twelve-second observation window. |
| Abrupt controller termination | Killed the temporary controller with SIGKILL. Its separate watchdog started restoration after 0.83 seconds and completed after 1.52 seconds, restoring all three original minimums and modes, with ceilings and governors intact. |
| Normal ClusterTune screen | Opened successfully while the minimums were protected; no permission/attention warning appeared in the captured UI. The accessibility binding was restored first to exclude instrumentation's force-stop side effect. |
| Production Auto Tune integration | `RootHostHandoffInstrumentationTest#pServerHostBinderHandoff_roundTripsWhenAvailable` passed in 7.773 seconds while protected. It verified live frame telemetry, a controller-driven ceiling reduction, fixed-policy preservation, unchanged minimums during that transaction, and ceiling restoration. Post-test reads confirmed all three minimum nodes remained 0444. |

[Full sampled data](research/minimum-frequency/protection-validation.csv) and [abrupt-stop recovery timing](research/minimum-frequency/protection-recovery.txt) are retained. The recovery watchdog was an **experimental test fixture**; this does not establish that production ClusterTune already owns or restores protected minimums. That feature is not implemented by this research.

A separate app-launch observation returned a higher effective policy0 minimum (1,459,200 kHz) while its node still had mode 0444. The source of that individual increase was not separately traced. This reinforces the limit of the guarantee: blocking the OEM's direct file writes does not promise that the effective minimum can never rise through other frequency constraints.

The device reported external AC power during the run. No unplugged energy comparison, game-FPS comparison, reboot, thermal-stress run, or long-duration soak was performed. Initial hold/load observations also showed high current frequencies on policy0/policy3 despite low minimums; lower floors alone do not establish an energy saving.

The temporary instrumentation bridge, scripts and test workers were removed afterward; the previous test APK, accessibility grant list, screen timeout and minimum settings/modes were restored. No experimental minimum protection was left enabled, and no production source was changed for these tests.


## Production integration

Implemented after the experimental validation above. The earlier statement that the experimental watchdog was not production code remains specific to that experiment; the app now has its own minimum ownership and recovery implementation.

### Behavior and scope

- Enabled only in a root-owned host when `/system/bin/pservice` matches SHA-256 `8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4`, the binary extracted from this Thor. Other binaries retain the existing behavior. Existing recovery records are still processed if the binary changes.
- Applying a manual underclock sets that CPU policy's lowest supported minimum once and removes write bits while preserving read permissions (0660 → 0440 on this device). It protects only underclocked policies. An already read-only minimum belonging to another tool is rejected rather than claimed.
- Reapplying an owned profile changes ceilings without repeatedly rewriting minimums. Returning a policy to Stock applies its Stock ceiling first, then restores the minimum's original permissions. AYN's next timer pass supplies the selected performance-mode floor.
- Auto Tune remains maximum-only. Protection acquired by a manual baseline profile remains active through an Auto Tune session; stopping Auto Tune alone does not release that baseline protection.
- Minimum permission ownership is recorded before mutation in an atomic, root-private, boot-specific journal. A lifetime file lock excludes simultaneous owners. Release failures keep their recovery record and prevent a normal host shutdown from abandoning recovery.
- App Binder-lease death and normal host shutdown release minimum permissions. After a privileged-host SIGKILL, the next host recovers them before serving discovered capabilities or applying another profile. **There is no independent production watchdog process:** a dead privileged host cannot immediately repair itself. Until replacement or reboot, its read-only minimums can remain protected.
- Recovery restores permissions, not a captured effective minimum value. A captured value could contain another boost and replaying it could create an unintended lasting floor. An unrelated permission change from another privileged tool is preserved.
- A successful minimum write plus protected permissions is accepted even if readback reflects a stronger independent QoS vote. Such a boost is not reported as lost privilege. Acquisition failures, changed ownership and pending permission recovery have distinct errors.
- GPU minimums and governors are unchanged by this new protection feature. No battery, game-performance, or responsiveness benefit is claimed from these tests.

The host protocol version advances to 12 so an older running host is replaced before using the updated behavior.

### Production device checks

Installed the updated debug app on the same Thor, preserving its app data and Large Underclock ceiling values (1,459,200 / 1,785,600 / 1,843,200 kHz; GPU 680 MHz).

| Check | Result |
| --- | --- |
| Manual underclock across firmware reset passes | 24 consecutive one-second observations held minimums at 307,200 / 499,200 / 595,200 kHz with mode 0440. All three ceilings stayed at the selected values. |
| Stock | All minimum modes returned to 0660 immediately. After 12 seconds, Stock ceilings were 2,016,000 / 2,803,200 / 3,187,200 kHz and AYN minimums were 902,400 / 1,651,200 / 1,843,200 kHz. |
| Explicit privileged-host stop | All three original minimum modes restored. |
| Privileged-host SIGKILL and replacement | Modes remained protected while the host was dead, as expected. A new host epoch recovered all original minimum modes from the durable journal. |
| Auto Tune with owned protection | Live SurfaceFlinger/controller integration passed in 8.658 seconds: a ceiling reduction, fixed-policy preservation, unchanged minimums, Auto Tune ceiling restoration, protection retained through session stop, and minimum permission release on host stop. |
| App process termination | The opt-in fixture verified all three protected modes before instrumentation ended. After the client process terminated, all three modes were 0660 and the privileged host had exited. |

The primary production profile/recovery test passed in 53.386 seconds. Raw observations and instrumentation summaries are retained under `docs/research/minimum-frequency/production-*`. The automated physical tests restored the original profile ceilings, minimum permissions, and GPU setting afterward. The final normal-UI check then selected Stock and reapplied the existing Large Underclock profile. The device is left on that profile with all three minimums protected (0440), unchanged ceiling values, and no permission/attention warning in the UI. Accessibility was verified bound and the screen timeout restored to 60 seconds. The app remains updated.

The JVM tests cover partial acquisition failure, retaining earlier ownership, failed-release retries, rejected foreign locks, independent boost readback, selective Stock release, reboot records, malformed recovery records, write-ahead failure, unsupported firmware, and concurrent journal owners. Physical reboot, long-duration soak, thermal stress, unplugged power measurement, and real-game performance comparisons remain untested.

Final verification: **565 JVM tests passed**, lint reported **0 errors / 58 existing warnings**, and the complete Thor instrumentation suite finished in **113.279 seconds** with **69 passed / 5 conditional skips / 0 failures**. The opt-in profile/recovery, protected Auto Tune, and client-death fixtures were also run separately as described above.


### Closing the main window

A follow-up on the Thor verified that closing the window does not end the app process or its privileged-host lease. Home and Back left the main activity stopped while the background services continued. Removing only ClusterTune's activity task then removed its activity record entirely; the same app PID (16937) and privileged-host PID (17182) remained alive, and all three minimum files remained protected at 0440. The existing overlay foreground service (shared notification ID 41) and bound accessibility service continued running. This check used the user's enabled background features; it does not establish process retention with every background feature disabled.

References to app shutdown in the earlier explanation mean termination of the app process, not closing its main window. The minimum recovery path is tied to Binder lease death or explicit privileged-host shutdown, not Activity destruction.


### General availability follow-up

The firmware fingerprint restriction above described the initial rollout. The current implementation no longer checks the device model, OEM, presence of `pservice`, or its hash. A root-owned privileged host can use protection on compatible CPU frequency nodes, subject to supported minimums, readable/changeable permissions, and the durable ownership journal. Failed acquisition rolls back newly owned permission changes; read-only nodes owned by another tool remain protected. Host protocol 13 replaces earlier hosts before using this policy.

Stock still restores maximum ceilings before restoring minimum write permissions. It does not replay the previous effective minimum, which may have contained an independent boost. On firmware with a periodic minimum writer, that service can apply its chosen floor afterward. Without such a writer, the supported low minimum remains until the system or another owner changes it. This is permission release, not a promise to reconstruct every platform's original minimum request. Kernel QoS votes and privileged writers that bypass file permissions remain outside the protection guarantee.

Regression coverage includes Stock without a vendor writer, ownership recovery followed by reacquisition, missing minimum candidates, and unreadable node permissions, alongside the prior rollback and recovery cases. Physical evidence above remains specific to the Thor; general availability is not a claim that other devices have been physically validated.
