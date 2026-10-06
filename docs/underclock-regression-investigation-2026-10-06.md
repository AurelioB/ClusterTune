# AYN dashboard's false 3.19 GHz reading below 1 GHz

Status: the dashboard symptom was reproduced on the available Thor. The extracted dashboard has a confirmed unit-conversion bug. **Independent hardware measurements also found above-cap clock rates that driver readings and 3C do not reveal**, in both public 1.3.0 and 1.2.2. The display bug does not establish that frequency enforcement is perfect. No ClusterTune runtime workaround was applied.

Latest firmware result: the staged investigation located the stock TrustZone LMH/DCVSH command handler. **The older FCAP transport resolves to a constant “not supported” stub on the inspected stock firmware.** The [proof and staged plan](research/underclock-dashboard/direct-controls/fcap-proof-plan.md) supersede the earlier description of FCAP as an unresolved candidate; no firmware cap was written.

## Confirmed result

Testing a debug-signed build from the exact 1.3.0 source reproduced the supplied video: selecting underclock profiles made AYN's dashboard show 3.19 GHz while direct prime-core frequency reads repeatedly returned 595,200 kHz. The new low minimum exposes an existing dashboard bug.

The dashboard initializes its ring's unit and maximum from `cpuinfo_max_freq`: GHz and approximately 3.19 on this Thor. When updating the current reading, it independently chooses a divisor based on the *current* frequency:

- At or above 1,000,000 kHz, it divides by 1,000,000, producing GHz.
- Below 1,000,000 kHz, it divides by 1,000, producing MHz, but leaves the ring in GHz.
- The ring clamps its input to its maximum. Thus 595,200 kHz becomes the number 595.2 in a ring capped at 3.19, producing a false **3.19 GHz** label instead of **0.595 GHz**.

The relevant extracted classes are `S0.c.a` (CPU7 sampling), `CpuRingView` (initial units and per-sample conversion), and `B0.d` (`g` clamps the progress; `setProgressWithAnimation` uses it). Extracted APK SHA-256: `b199e43eb8b3ff63045da955a996693103d7e6d2bc6b7830d079421f47729822`.

### Physical comparison

AYN's dashboard was active on the lower screen, with Standard Performance retained. Each phase collected 30 paired observations per cluster, including effective minimum, maximum, both current-frequency nodes, and node permissions.

| Phase | Prime-core maximum (GHz) | Prime driver-reported frequency (GHz) | Dashboard capture |
| --- | ---: | ---: | --- |
| Medium, protected minimum | 2.0928 | 0.5952–2.0928 | 3.19 GHz |
| Large, protected minimum | 1.8432 after transition | 0.5952–1.8432 after transition | 3.19 GHz |
| Medium, minimum made writable | 2.0928 | Firmware restored a 1.8432 GHz floor | 1.84 GHz |
| Medium, protected floor above 1 GHz | 2.0928 | 1.248–2.0928 | 2.09 GHz |
| Medium, protected low floor restored | 2.0928 | 0.5952–2.0928 | 3.19 GHz again |

The above-1-GHz experiment requested 1,228,800 kHz; the kernel rounded it up to the available 1,248,000 kHz bin. Protection remained 0440 in both protected comparison phases. This distinguishes the display's unit boundary from a permission failure.

Stock → Small → Medium → Large → Medium and the three minimum comparisons produced **720 paired policy observations**, with **zero current readings above the contemporaneously read maximum**, for both `scaling_cur_freq` and `cpuinfo_cur_freq`. Profile application is asynchronous, so initial samples can retain the previous profile's maximum until the transaction reaches that policy. Those transient samples must not be compared against the next profile's target as if it had already applied.

There was also an independent MSM performance vote of 2,016,000 kHz on the efficiency cluster and 2,707,200 kHz on the middle cluster while the dashboard was active. Those clusters' effective minimums were clamped to the selected maximums. Minimum-node protection does not suppress separate system QoS votes. The prime-core MSM vote was zero when checked. This separate behavior is not evidence that the prime core operated at 3.19 GHz under an underclock.

These initial sampled tests did not measure battery life, physical cycle-counter frequency, or sustained gaming performance, and do not establish that every user's battery complaint has the same cause. The reporter's firmware was not available. The supplied symptom and its unit-conversion mechanism are nevertheless reproduced on the tested firmware. The independent measurements below supersede any inference that these readbacks prove a strict physical ceiling.

The older public 1.2.2 lets firmware restore the prime floor above 1 GHz, which explains why returning to it can remove the false display reading. Forcing ClusterTune's floor above 1 GHz would hide this display bug by giving up the lower operating range; it is not a fix for the dashboard's conversion.

[Raw phase observations](research/underclock-dashboard/profile-and-minimum-comparisons.csv), [profile transitions](research/underclock-dashboard/profile-measurements.txt), [minimum comparisons](research/underclock-dashboard/minima-comparison.txt), and [Stock restoration](research/underclock-dashboard/stock-restoration.txt) are retained. Captures include the [Medium limits](research/underclock-dashboard/dashboard-medium-top.png), [false Medium dashboard reading](research/underclock-dashboard/dashboard-medium-dashboard.png), [writable-minimum reading](research/underclock-dashboard/comparison-writable-dashboard.png), [protected floor above 1 GHz](research/underclock-dashboard/comparison-protected-above-1ghz-dashboard.png), and [false reading after restoring the low protected floor](research/underclock-dashboard/comparison-protected-below-1ghz-dashboard.png).

### Restoration

Stock restored maximums to 2,016,000 / 2,803,200 / 3,187,200 kHz and released every minimum node to its original 0660 mode. Thirty additional observations per cluster cover that transition; the combined dataset contains 810 policy observations with no current readback above its contemporaneous maximum.

The original pre-test development APK was then reinstalled with data preserved, and the original Large Underclock profile reapplied. The dashboard was closed, the 60-second screen timeout restored, and the Thor returned to its initial sleeping state. [Final verification](research/underclock-dashboard/final-restoration.txt) confirmed minimums 307,200 / 499,200 / 595,200 kHz, maximums 1,459,200 / 1,785,600 / 1,843,200 kHz, original minimum/maximum permissions, `walt` on all policies, GPU maximum 680 MHz, Standard performance mode, and cleared MSM performance minimum votes. No governor, thermal setting, display performance property, or persistent experimental frequency was left changed. Profile test switches remain in the app's history.

## Independent double-check: driver diagnostics miss physical excursions

The user's concern about fast changes was justified. This pass checked the installed 3C All-in-One Toolbox (3.2.3b), continuous kernel events, a hardware cycle counter, and Qualcomm's hardware clock measurement. It used the **published production APKs**, re-signed with the existing installation's debug key to preserve app data; both retained their production, non-debuggable manifests. The original release APK hashes were:

- 1.3.0: `daa6730ae9f756a2773289cecf0f2395ff0b584ac12fa8e367758ad2653c6391`.
- 1.2.2: `3e18505e1ce709324a3e8c85478cb1d2cd3ea6326a90fd49a77f8b6e09026ac5`, matching the release's published checksum.

### Independent UI and continuous driver events

With Large Underclock selected, [3C showed the prime core at 595 MHz and its maximum at 1.84 GHz](research/underclock-dashboard/hardware-double-check/large-idle-3c.png), while [AYN's dashboard showed 3.19 GHz](research/underclock-dashboard/hardware-double-check/large-idle-ayn.png). This is an independent UI comparison, not an independent measurement of the physical clock.

A dedicated trace instance streamed `power/cpu_frequency`, `power/cpu_frequency_limits`, WALT demands, and DCVSH events for 344.283 seconds. It captured 1,017 driver frequency records, 31 limit records, and 176,319 WALT demand records. Every CPU's trace buffer reported **zero overruns and zero dropped events**. There were 743 driver frequency records in settled Stock/Small/Medium/Large windows, with no recorded frequency above the corresponding cap. The first 0.628 seconds include Stock application without its start marker; they are not interpreted against an assumed initial cap. Later application windows are also excluded until their explicit `SETTLED` marker.

WALT demands frequently exceed the cap, but the [related governor source](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/kernel/sched/walt/cpufreq_walt.c) emits `waltgov_next_freq` before resolving/clamping the demand. Those records must not be labelled committed over-cap transitions. Likewise, driver transition events do not record every autonomous hardware clock change. No DCVSH events were emitted during this capture; that is not proof that hardware never changed the clock independently.

[Driver/limit events and markers](research/underclock-dashboard/hardware-double-check/driver-events.csv), [trace summary](research/underclock-dashboard/hardware-double-check/trace-summary.json), and [all-CPU buffer statistics](research/underclock-dashboard/hardware-double-check/trace-stats.txt) are retained. The full 42 MB stream remains in `/tmp/ct-double-check/frequency-events.txt`; its SHA-256 is recorded in the summary. The checked-in CSV omits the bulk pre-clamp demands and process names.

### Hardware counters and clock measurement

Initial `simpleperf` cycle/task-clock estimates were above the underclock caps. A separate [native probe](research/underclock-dashboard/hardware-double-check/cycle_probe.c) then opened its own `PERF_COUNT_HW_CPU_CYCLES` counter, pinned itself to CPU7, and measured counter differences against both thread CPU time and monotonic wall time during an eight-second arithmetic workload. This eliminates the initial command's inherited child counters as the explanation. Each probe produced 32 approximately 250 ms windows; the counter's enabled/running times matched. Repeating with tracing disabled retained the discrepancy.

During separate probe runs, root read `/sys/kernel/debug/clk/measure_only_apcs_goldplus_post_acd_clk/clk_measure` ten times per profile, alongside minimum, maximum and `cpuinfo_cur_freq`. This is a different hardware measurement from the driver's requested performance-state readback. The [related Qualcomm measurement implementation](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/clk/qcom/clk-debug.c) counts a clock against a reference oscillator over an approximately 27 ms window; it is not an instantaneous sample. That implementation and the [Kalama clock wiring](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/clk/qcom/debugcc-kalama.c) provide context; neither is established to be byte-identical to the stock kernel. These tests did not change governors or thermal controls. The measurement node internally switches and enables debug clocks temporarily. The counter-only repeats reproduced the discrepancy without reading that node.

| Release | Profile | Configured prime cap (GHz) | Cycle/wall-time average (GHz) | Hardware clock measurement range (GHz) |
| --- | --- | ---: | ---: | ---: |
| 1.3.0 | Stock | 3.1872 | 3.1107 | 2.9568–3.1872 |
| 1.3.0 | Medium | 2.0928 | 2.1876 | 2.0927–2.3424 |
| 1.3.0 | Large | 1.8432 | 1.9581 | 1.8836–2.0928 |
| 1.2.2 | Stock | 3.1872 | 3.1074 | 2.9849–3.1872 |
| 1.2.2 | Medium | 2.0928 | 2.1981 | 2.0928–2.3424 |
| 1.2.2 | Large | 1.8432 | 1.9534 | 1.8566–2.0928 |

Every driver's current-frequency read in these 60 comparisons equalled the selected cap, while the independent hardware measurement often exceeded it. The two methods have different measurement windows; their numbers should not be compared as simultaneous instantaneous samples. Small differences around a nominal frequency can be measurement precision, but the roughly **250 MHz excursions** under Medium/Large and the elevated integrated cycle rates cannot be dismissed that way.

Making 1.3.0's minimum nodes writable, waiting eleven seconds for firmware to restore the 1.8432 GHz prime floor, and repeating the native probe also retained above-cap estimates. Public 1.2.2 kept writable minimum nodes and showed the same behavior. This points away from the new minimum permission protection as the cause; it does not identify the hardware/firmware mechanism or exclude a pre-existing app limitation. Original-probe affinity failures were retried after a bounded warm-up; failed attempts were not used as measurements.

[All 60 hardware comparisons](research/underclock-dashboard/hardware-double-check/hardware-clock-comparison.csv), per-release cycle CSVs, policy states, the no-trace repeats, and the writable-minimum repeat are retained in [the evidence directory](research/underclock-dashboard/hardware-double-check).

### What is and is not established

- The AYN 3.19 GHz display error is confirmed independently of the clock-enforcement discrepancy.
- Underclock profiles materially reduce measured prime-core clock rates compared with Stock in both releases.
- Neither the sampled frequency files, 3C's matching readings, nor the lossless driver trace establishes a strict physical cap. The hardware measurements directly contradict that stronger claim on this Thor.
- The discrepancy occurs in public 1.2.2 as well as 1.3.0 and with writable minimums. A regression caused by the new minimum protection was not demonstrated.
- These bounded measurements do not exclude arbitrarily brief 3.19 GHz pulses between hardware measurement windows, establish sustained gaming behavior or battery impact, or identify the mechanism causing the above-cap clock rates. That remains open.

The original development APK was reinstalled with app data preserved. Initial cleanup caused a system crash and recovery incident, detailed below. After the user rebooted out of recovery, [Stock was applied to release minimum ownership](research/underclock-dashboard/hardware-double-check/restore-stock.txt), then [the original Large profile was reapplied](research/underclock-dashboard/hardware-double-check/restore-large.txt). [Final post-sleep verification](research/underclock-dashboard/hardware-double-check/final-restoration.txt) confirmed the original CPU minimums/maximums/permissions, `walt`, GPU maximum 680 MHz, Standard performance mode, screen timeout 60 seconds, stay-awake setting zero, sleeping screens, and zero MSM minimum votes. The temporary native probe and its results file were removed; the dedicated trace instance had already been removed. ClusterTune's accessibility service was bound after the reboot. This verifies the test's explicit settings; it does not establish that Android's rescue operation preserved every unrelated user setting.

### Cleanup incident: invalid display Home event triggered recovery

The test cleanup incorrectly assumed the lower screen had logical display ID 1 and sent `input -d 1 keyevent 3` (Home) without verifying the active display IDs. The screenshot's stable physical display ID does not imply logical ID 1. At **14:30:08.959 local time**, Android's `system_server` crashed in `RootWindowContainer.startHomeOnDisplay` with a null display container, reached through `DisplayHomeButtonHandler` and `handleShortPressOnHome`. This points directly to that cleanup action, not to a frequency write or a kernel panic. [The crash stack is retained](research/underclock-dashboard/hardware-double-check/system-server-home-crash.txt).

Android restarted its system process at approximately 14:30:24. AYN's persistent `com.odin.settings` app then recorded six crashes between 14:30:14.137 and 14:30:16.285, failing to initialize an audio equalizer effect (`Error: -3`). The [persistent rescue log](research/underclock-dashboard/hardware-double-check/rescue-escalation.txt) explicitly records escalation through `RESET_SETTINGS_UNTRUSTED_DEFAULTS`, `RESET_SETTINGS_UNTRUSTED_CHANGES`, `RESET_SETTINGS_TRUSTED_DEFAULTS`, `WARM_REBOOT`, and `FACTORY_RESET`, all for `com.odin.settings` at 14:30. Thus the recovery transition is confirmed as Android's rescue response, rather than an unexplained wireless disconnect. The user confirmed seeing recovery and manually rebooting out of it; the subsequent `SYSTEM_BOOT` was recorded at 14:33:51.047 with boot reason `recovery`. The generic restart record's timestamp follows some app crashes because records are collected asynchronously; it is not the precise instant the system process resumed.

[Android documents Rescue Party](https://source.android.com/docs/core/tests/debug/rescue-party) as escalating persistent system-app crash loops into settings resets and a recovery prompt. The logged `FACTORY_RESET` level means the recovery prompt was requested; it is not evidence that the user accepted a data wipe. Installed apps and ClusterTune data remained available after the user's reboot. However, rescue settings-reset stages actually ran, so preservation of every unrelated Android preference cannot be claimed.

This was a test cleanup error. Future hardware interaction must discover and verify logical display IDs immediately before using them, and must not assume the second screen is display 1. After reboot, the device exposed logical IDs 0 and 4; the valid secondary ID was checked before the final Home action. The invalid-display action was not repeated to reproduce the crash. No command to enter recovery or wipe data was issued by the test.

## Narrow profile entirely above 1 GHz

At the user's request, a follow-up test set the prime core's maximum through ClusterTune's manual override to **1,248,000 kHz**, retaining the Large profile's other CPU/GPU ceilings. Because profiles currently configure ceilings rather than user-selected floors, root then temporarily raised the already-managed prime minimum to the supported **1,132,800 kHz** bin and restored its protected 0440 mode. Lowering only the maximum would not prevent the dashboard's below-1-GHz conversion error. This used the user's original development APK, which identifies as 1.2.2 but implements the minimum protection; the public-release comparisons are the separate tests above.

The AYN dashboard was opened/closed using its own button-event broadcast. No Home event was sent to an assumed secondary display. Standard performance and `walt` remained in place. The first two frequency bins above 1 GHz are 1,132,800 and 1,248,000 kHz; the supported 998,400 kHz bin supplied a comparison just below the boundary.

| Test phase | Prime minimum (GHz) | Prime maximum (GHz) | Driver readbacks (GHz) | AYN screenshot |
| --- | ---: | ---: | --- | --- |
| Above boundary, before load | 1.1328 | 1.2480 | 1.1328–1.2480 | 1.13 GHz |
| Above boundary, after load | 1.1328 | 1.2480 | 1.1328 | 1.13 GHz |
| Below boundary, same ceiling | 0.9984 | 1.2480 | 0.9984 | **3.19 GHz** |

Each idle phase recorded 30 observations, all retaining the requested minimum, maximum and protected permissions. 3C's paired captures showed approximately 1.13 GHz above the boundary and 998 MHz below it. Changing the floor from 1.1328 to 0.9984 GHz brought back the false dashboard maximum without changing the ceiling.

However, the narrow range was **not a strict physical range under load**:

- Fifteen independent hardware-clock measurements ranged from **1.2480 to 2.0928 GHz**, while every paired driver-current read reported 1.2480 GHz.
- Thirty-two roughly 250 ms cycle-counter windows averaged **1.5962 GHz**, ranging from 1.3309 to 1.9838 GHz.
- A finer counter-only run produced **5,977 approximately 1 ms windows**, ranging from **1.1352 to 2.0913 GHz**, with a mean of 1.6082 GHz. Every window ran on CPU7 with matching enabled/running counter times. Wall-time brackets enclose both counter reads in this finer probe, avoiding an overstated rate from counting outside the measured wall interval. Window durations ranged from 1.000729 to 1.070208 ms.
- No counter window or hardware-clock observation reached 3 GHz. These window averages still cannot exclude an arbitrarily short pulse above 3 GHz inside a window. They do rule out claiming that the hardware remained continuously within the requested 1.1328–1.2480 GHz range.

Thus maintaining the requested minimum above 1 GHz removes the false 3.19 GHz dashboard reading, but does **not** remove the independently measured cap-enforcement discrepancy. This lower ceiling produced a larger discrepancy than the previous Medium/Large tests; the mechanism remains unresolved.

[Summary](research/underclock-dashboard/above-1ghz-test/summary.json), [hardware clock comparisons](research/underclock-dashboard/above-1ghz-test/above-load-clocks.csv), [the 1 ms counter data](research/underclock-dashboard/above-1ghz-test/above-cycles-1ms.csv), and the [finer probe source](research/underclock-dashboard/above-1ghz-test/cycle_probe_1ms.c) are retained. The [above-boundary dashboard](research/underclock-dashboard/above-1ghz-test/above-idle-ayn.png) and [below-boundary dashboard](research/underclock-dashboard/above-1ghz-test/below-idle-ayn.png) captures provide a direct display comparison, alongside 3C captures and policy CSVs in the same directory.

Stock and then Large were applied through the app to release/reacquire minimum ownership and restore the original low floors. The dashboard and 3C were closed, the 60-second timeout restored, both temporary probes/results removed, and the screens returned to sleep. [Final verification](research/underclock-dashboard/above-1ghz-test/restoration.txt) matches the [starting CPU/GPU state](research/underclock-dashboard/above-1ghz-test/baseline.txt), including permissions and `walt`; MSM minimum votes were zero. Uptime continued normally through the test and restoration. No new APK, permanent profile, governor change, or recovery incident was introduced in this pass. The manual override and restoration switches remain in the app's history.

## Cap overshoot: hardware raises the selected state above the software request

A follow-up isolated a cross-cluster dependency. **The leading explanation is Qualcomm shared rail boost (SRB)**: autonomous clock management can select a higher state than Linux requests when another clock domain requires a higher shared supply voltage. This is strongly supported by the controlled comparison below, but the exact stock-firmware algorithm and disable control have not been identified. A patent describing SRB is context, not proof that this firmware implements every described condition.

The stock kernel exposes the read-only `/sys/kernel/qcom-cpufreq-hw/print_cpufreq_debug_regs` interface. Its [related source](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/qcom-cpufreq-hw-debug.c) distinguishes `PERF_STATE_DESIRED` (offset 0x320) from `PSTATE_STATUS` (0x20) and includes an `EPSS_DEBUG_SRB` register. The related CPUFreq driver's getter reads the desired index, while its throttle-frequency helper reads the low eight bits of domain state and multiplies by the reference clock. On this Thor that multiplier is 19.2 MHz, matching the exported frequency bins. The index field at bits 8–15 is inferred from matching observations, rather than an independently obtained register specification.

During an initial six-second CPU7 workload with the existing Large profile, all twenty sampled software requests remained index 10 (1,843,200 kHz). Ten hardware-status samples instead contained `0x2f800c6d`: multiplier 109, corresponding to **2,092,800 kHz**, instead of the requested multiplier 96 (1,843,200 kHz). The cycle counter independently reached approximately 2.0913 GHz. This establishes that the disagreement is between the software request and the hardware-selected state; a fixed lookup-table mislabelling alone does not explain the alternating hardware states for the same request.

### Middle-cluster comparison

With the screens asleep, Standard performance retained, and the prime maximum fixed at 1,843,200 kHz, a baseline → changed → restored comparison temporarily reduced **only** policy3's maximum from 1,785,600 to 1,056,000 kHz. This used the supported frequency node, not direct hardware-register writes. The original permissions were restored before each workload; a cleanup trap restored the original maximum and removed temporary probes. CPU minimums, governors, prime maximum, GPU ceiling, and firmware performance mode were unchanged. Each phase ran the same six-second CPU7 arithmetic workload and collected twenty hardware-status samples plus 5,983 approximately one-millisecond cycle-counter windows. No debug clock measurement node was read during this comparison.

| Middle-cluster maximum (GHz) | Prime maximum/request (GHz) | Prime hardware-status frequencies (GHz) | Highest cycle/window rate (GHz) | Over-cap status samples |
| --- | --- | --- | ---: | ---: |
| 1.7856, baseline | 1.8432 | 1.8432 / 2.0928 | 2.0913 | 5 / 20 |
| 1.0560, temporarily lowered | 1.8432 | 1.8432 | 1.8417 | 0 / 20 |
| 1.7856, restored | 1.8432 | 1.8432 / 2.0928 | 2.0913 | 5 / 20 |

Every sampled prime desired index remained 10. Every counter window ran on CPU7 with matching enabled/running times. Firmware-supplied SCMI residency counters also increased at 2.0928 GHz during the baseline/restored phases and did not increase there during the lowered-middle phase. Their units are retained as raw values, not assumed to be nanoseconds. The [related SCMI statistics reader](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/dcvs/cpufreq_stats_scmi.c) reads firmware-maintained shared-memory records rather than deriving these values from Linux transition events.

Qualcomm's [SRB patent application](https://patents.justia.com/patent/20260099460) describes raising a domain's final performance state above its software vote based on shared voltage requirements and other operating conditions, independently of the scheduler/kernel. This fits both the hardware/software split and the reversible middle-cluster dependency. `EPSS_DEBUG_SRB` was 1 for the middle/prime domains even in the phase without prime overshoot; its precise bit semantics are undocumented in the inspected source, so it is not treated as a per-sample indication that a boost occurred.

**What is established:** under these conditions, the hardware selects an above-request state, and reducing the middle-cluster ceiling removes those observed excursions; restoring that ceiling brings them back. **What remains unverified:** the exact voltage relationship and SRB decision thresholds, whether another hardware voter participates, and a supported control that prevents the behavior without unnecessarily limiting other cores. This is not evidence that changing governors or protecting sysfs permissions would disable autonomous hardware coordination. Earlier public-release and writable-minimum comparisons already showed that the new minimum protection is not required for overshoot to occur.

The software ceiling is therefore not a demonstrated hard physical ceiling on this firmware. That finding remains separate from the dashboard's false 3.19 GHz conversion below 1 GHz. No runtime workaround or profile change was added to ClusterTune.

[Hardware observations and summary](research/underclock-dashboard/cap-cause/summary.json), [raw register/residency readings](research/underclock-dashboard/cap-cause/hardware-status.txt), and the three full counter runs ([baseline](research/underclock-dashboard/cap-cause/baseline-cycles-1ms.csv), [lowered middle](research/underclock-dashboard/cap-cause/lower_middle-cycles-1ms.csv), [restored middle](research/underclock-dashboard/cap-cause/restored_middle-cycles-1ms.csv)) preserve the evidence. [Restoration](research/underclock-dashboard/cap-cause/restoration.txt) confirms the original Large CPU/GPU limits, minimum/maximum permissions, `walt`, Standard performance, sleeping screens, and zero MSM minimum votes. The original installed APK was retained and no UI navigation, reboot, governor change, or direct register write was needed.

## Available controls: C1DCVS and ordinary CPU boost do not stop the excursions

Read-only inspection found two writable candidate switches on this Thor: `/sys/devices/system/cpu/c1dcvs/enable_c1dcvs` and `/sys/devices/system/cpu/cpufreq/boost`, both initially 1. No dedicated SRB enable/limit interface was identified in the inspected CPU, CPUFreq, hardware-debug, module-parameter, and platform-device paths. This is an inventory result, not proof that no undiscovered firmware control exists.

The [related C1DCVS driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/dcvs/c1dcvs_scmi.c) sends the enable setting through a vendor SCMI protocol; its source does not establish that this switch disables SRB. The [related CPUFreq core](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/cpufreq.c) implements the ordinary boost switch by changing frequency-table availability and policy QoS limits; the inspected hardware driver does not provide a dedicated hardware boost callback. Thus the presence of a file named `boost` does not establish control over autonomous shared-rail behavior.

Two independent enabled → disabled → restored comparisons retained Standard mode, sleeping screens, `walt`, low protected minimums, and the original Large ceilings (1,459,200 / 1,785,600 / 1,843,200 kHz). Each phase used the same six-second CPU7 workload, twenty register snapshots, and approximately 5,983 one-millisecond counter windows. Every snapshot retained the prime software request at index 10 and its ceiling at 1,843,200 kHz. Both switches' readbacks confirmed the intended values throughout their comparison phases.

| Control | Enabled: above-cap status samples | Disabled: above-cap status samples | Restored: above-cap status samples | Highest disabled cycle/window rate (GHz) |
| --- | ---: | ---: | ---: | ---: |
| C1DCVS | 8 / 20 | 9 / 20 | 8 / 20 | 2.0913 |
| Ordinary CPU boost | 4 / 20 | 8 / 20 | 10 / 20 | 2.0913 |

All six phases contained hardware-selected 2,092,800 kHz states. Counts in these short sampled windows are not estimates of boost duty cycle or evidence that disabling a switch worsens the behavior. Both switches failed to prevent above-cap operation under the tested conditions. All counter windows remained on CPU7 with matching enabled/running times.

Changing ordinary boost initially reset the three effective software maximums rather than preserving the requested Large ceilings. The first attempt therefore stopped before starting the disabled workload, restored boost, and was excluded from the comparison. The corrected experiment reapplied the original three ceilings and modes after each boost toggle, including final restoration, before taking measurements. This side effect is another reason not to expose the ordinary switch as a presumed SRB fix.

Other inspected interfaces do not currently provide a demonstrated hardware ceiling: `dcvsh_freq_limit` and the hardware register dump are read-only; the [related Qualcomm CPU cooling driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/thermal/qcom/qti_cpufreq_cdev.c) places software frequency-QoS requests. Those cooling controls were inspected, not changed, and are not established to be identical to all stock cooling implementations.

**The currently demonstrated workaround is coordinated cluster ceilings**, based on the middle-cluster comparison above. The tested 1.056 GHz middle ceiling is a proof of dependency, not a recommended default: a less restrictive pair still needs to be measured, and neither universality across firmware nor a strict ceiling under every workload is established. A direct SRB control or hardware ceiling would require further firmware/kernel investigation. No such runtime change, automatic workaround, or global boost setting was added to ClusterTune.

[Control summaries and all observations](research/underclock-dashboard/cap-controls/summary.json), [C1DCVS register snapshots](research/underclock-dashboard/cap-controls/c1dcvs-hardware-status.txt), [ordinary-boost snapshots](research/underclock-dashboard/cap-controls/boost-hardware-status.txt), and six full counter runs in that directory preserve the measurements. [Final restoration](research/underclock-dashboard/cap-controls/restoration.txt) verifies both switches back at 1, C1DCVS optimization mode unchanged at 0, original CPU/GPU ceilings and minima, original permissions, original CPU-info maxima, `walt`, Standard mode, sleeping screens, zero MSM minimum votes, and removed temporary probes. Thermal controls, firmware properties, the APK, and user profiles were not changed.

## Direct firmware limits and parallel project research

At the user's request, parallel investigations examined the Qualcomm control source, Thor custom ROM/kernel changes, and community tuning projects. **A concrete firmware-cap candidate was found, but no verified SRB disable or independent physical ceiling ready for ClusterTune was found.** This pass made read-only device queries and extracted stock artifacts; it did not load a kernel module, send a limit command, change clocks/thermal controls, flash a ROM, or contact project maintainers.

### Firmware cap candidate: LMH/DCVSH FCAP

The following describes the initial lead. The later [dispatch proof](#fcap-dispatch-proof-and-staged-test-result) identifies a nonfunctional stock command handler, so this older ABI is not a usable cap route on the inspected firmware.

An [older Qualcomm thermal driver](https://android.googlesource.com/kernel/msm.git/+/6d464a5c1b01f89743d1a320b7247574495cfb9d/drivers/thermal/qcom/msm_lmh_dcvs.c) implements `lmh_set_max_limit()` through a secure firmware command, separate from ordinary CPUFreq policy maximums. Its `FCAP` setting (`0x46434150`) uses a six-word payload `[THML, 0, FCAP, 2, max_kHz, enabled]`, the DCVS node type and cluster affinity, and version 0. The same path releases the vote with `U32_MAX` and enable 0. This proves an implementation existed on older Qualcomm platforms; it does not prove that the Thor's secure firmware accepts that ABI or that SRB observes this cap.

The Thor's **actual stock** `qcom-scm.ko`, extracted from the active `_b` vendor boot ramdisk, exports `qcom_scm_lmh_limit_dcvsh`. Its disassembly confirms a secure-call wrapper accepting a caller-provided payload/length and node identifiers. This matches the mechanism of the [related source helper](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/qcom_scm.c#L2115), rather than relying solely on a different ROM's source. However, the stock `msm_lmh_dcvs.ko` has no FCAP setter or secure-cap call in its inspected implementation. No userspace interface exposing the helper was identified. The simpler `qcom_scm_lmh_dcvsh()` source helper builds five payload words and does not directly encode the older two-value FCAP request.

A compatible kernel bridge or kernel change would therefore be needed for this candidate; escalation of ClusterTune's existing service alone does not make a kernel-exported function callable from userspace. The stock configuration enables module versioning, strict CFI, and module signature protection, so compatibility/loading must be established rather than assuming any newly compiled module can run. Nothing was loaded. The initial useful probe would query command/protocol availability without changing limits; availability of the generic secure call would still not establish support for its FCAP setting. A limit test needs a matching firmware ABI, correct cluster identifiers, and a proven vote-release path first.

Read-only extraction also retained active AOP, AOP configuration, TrustZone, XBL and XBL configuration images in `/tmp/ct-direct-controls-stock`. Limited strings/constant inspection did not identify a usable SRB configuration switch or establish FCAP support. Absence of a string is not proof that the command is absent. Proprietary firmware/module binaries are not committed; their sizes and SHA-256 hashes are in the evidence manifest.

### Non-destructive FCAP feasibility follow-up

The follow-up requested by the user examined query and restoration semantics without invoking a secure firmware command. Device access was limited to reading the existing interfaces, boot logs and final configuration. No module was loaded, no cap or thermal command was sent, and no clocks, permissions, firmware, profiles or applications were changed.

The stock `qcom-scm.ko` contains these specific query helpers, verified against its disassembly:

| Helper | Stock function offset | What a successful query would establish |
| --- | --- | --- |
| `qcom_scm_lmh_dcvsh_available()` | `0x4590` | Availability of LMH service `0x13`, command `0x10`; not support for the FCAP setting, the chosen cluster ID, or enforcement against SRB. |
| `qcom_scm_is_lmh_debug_get_type_available()` | `0x4ac4` | Availability of the separate debug-type enumeration command, `0x0b`. |
| `qcom_scm_lmh_get_type()` | `0x40c8` | A page of debug interface type identifiers, using a firmware-writable DMA buffer, byte capacity, category and starting index. |

The [older Qualcomm debug frontend](https://android.googlesource.com/kernel/msm.git/+/d616c9a54122060c1d23b0de1302746b362dd4b2/drivers/thermal/qcom/lmh_dbg.c) documents category 0 as read/data types and category 1 as configuration types, with ten `u32` entries per page. This is enumeration for the **debug** interface, whose setter is command `0x08`. FCAP is a setting within the different DCVSH command `0x10`. Finding or failing to find FCAP in a debug-type list would therefore not prove or disprove FCAP support. No existing userspace frontend for these queries was identified on the stock device, and none of these firmware queries was actually executed in this pass.

**The old clear operation is not a demonstrated restoration mechanism.** In the inspected older implementation, the thermal driver aggregates its per-CPU votes in software and sends one cluster cap through `[THML, 0, FCAP, 2, max_kHz, enabled]`. The payload has no separate caller identity. Its release payload is `[THML, 0, FCAP, 2, U32_MAX, 0]`, but no supported operation to read the prior FCAP vote was found. An independent writer could replace a cap used by another thermal actor; subsequently clearing it would not necessarily restore that cap. Firmware-side ownership or aggregation on the Thor remains unknown. A write followed by a clear is consequently not an acceptable non-destructive discovery test.

A limited static search of the stock TrustZone and AOP executable segments found no literal FCAP/DCVS/THML identifiers or the selected complete LMH/DCVSH call identifiers, including selected immediate constructions. It did recover three unrelated `ENBL` constructions in TrustZone as a check that split AArch64 immediates were being recognized. This is a narrow constant search, not a complete handler/control-flow analysis: different encodings, dispatch structures or firmware components remain possible. The extracted XBL file's outer ELF header does not identify an ARM code architecture, so its contents were searched for literal constants only; its outer segments were not treated as Thumb executable code. **These negative searches do not establish that FCAP is unsupported.**

The remaining non-setting probe would require a correctly built kernel bridge calling only the availability/type-query helpers. The stock module exports `qcom_get_scm_device()` (`0x82e4`), permitting coherent DMA against the actual SCM device if enumeration is attempted. Such a bridge must match the stock kernel's build, symbol versions and strict CFI requirements, respect its module signature protection, bound returned counts/pages, and free DMA on every error path. It should expose no arbitrary secure-call passthrough and import no cap, debug-set or profile-change helper. Loading an unverified module would add kernel crash risk, so no bridge was loaded or compatibility checks bypassed. Even a successful query would leave FCAP-specific support, three-cluster identifiers, cap ownership/restoration and physical enforcement unverified.

Evidence: [query ABI and release analysis](research/underclock-dashboard/direct-controls/fcap-query-feasibility.txt), [static search results](research/underclock-dashboard/direct-controls/fcap-constant-scan.json), and [reproducible host-only scanner](research/underclock-dashboard/direct-controls/scan_fcap_constants.py). The scanner uses Capstone 5.0.6 and accepts an artifact directory and an output JSON path; it performs no device access. [The final read-only snapshot](research/underclock-dashboard/direct-controls/fcap-readonly-final-device-state.txt) confirms active slot `_b`, increasing uptime, the original Large minima/maxima and permissions, `walt`, GPU ceiling, boost/C1DCVS enabled, Standard mode, sleeping screens and zero MSM minimum votes. FCAP remains an unconfirmed firmware lead; no ClusterTune implementation or claim of a strict physical ceiling was added.

### Standard SCMI performance limits

The [standard SCMI performance implementation](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/perf.c#L356) supports firmware min/max requests with protocol `0x13`, conditional on per-domain advertised limit support. The stock kernel has SCMI performance code, but the Thor's live `/sys/bus/scmi_protocol/devices/` binds only vendor PMU (`0x86`), C1DCVS (`0x87`), memory-latency (`0x80`) and frequency-statistics (`0x84`) protocols. Device-tree children also include vendor `0x81`; there is no configured `protocol@13` child or activated standard performance device. Thus a stock userspace SCMI limit route is not established. The full firmware discovery list remains internal to the kernel, so this inventory alone cannot prove the firmware lacks protocol `0x13`.

### Firmware ownership and restoration follow-up

The next pass examined stock secure-call dispatch and CPU-control firmware to investigate whether FCAP has independent owners, a prior-value getter, or a reliable restoration operation. Device activity remained read-only: additional active-slot `hyp_b`, `uefisecapp_b`, and `cpucp_b` images were copied for host analysis. No secure command was executed, module loaded, control changed, application replaced, or reboot performed. The findings below distinguish the old FCAP ABI from a separate standard SCMI protocol.

**FCAP ownership/restoration is still unverified on this Thor.** Qualcomm's [secure-call declaration](https://github.com/edk2-porting/edk2-msm/blob/e1952621f419f8db60ed28271264e1b5184c571d/Silicon/Qualcomm/QcomPkg/Include/Protocol/scm_sip_interface.h) documents LMH/DCVSH command `0x02001310`. The [older firmware request structure](https://github.com/Rivko/android-firmware-qti-sdm670/blob/20bb8ae36c93fc16bbadda0e0a83f930c0c8a271/trustzone_images/ssg/securemsm/trustzone/qsee/include/tzbsp_syscall_priv.h) carries a buffer address/length, node type, node identifier and version. Together with the old driver's six-word FCAP payload, these inspected fields provide no explicit per-application owner token, previous-vote response, or restore token. This does not exclude implicit firmware ownership, aggregation, or an undiscovered getter.

Stock HYP has a 44-entry local secure-call table at virtual addresses `0x218000..0x218420`. Its INFO availability handler (`0xdc10`, lookup at `0xdc58`) scans that table; no local LMH entry was found. However, a separate forwarding path places the caller/client identity in register `x7` at `0x8d738` and invokes `smc` at `0x8d748`. Therefore the local table does not enumerate every command reachable through secure firmware. The forwarded identity establishes a client context; it does **not** establish that two Android apps, both acting through the same kernel service, receive independent FCAP votes. These instructions were disassembled, never executed by the investigation.

Tracing the stock TrustZone `LMH Isense Init done` code led to current-sensing initialization and OEM configuration lookups, not an identified FCAP setter/getter. The `/dev/oem/limits` string is used in firmware configuration lookup; it is not an identified Linux device interface. Missing full SMC identifiers in this image are weak evidence: the same literal search also misses ordinary INFO, peripheral-authentication and I/O commands. Thus neither absent constants nor the HYP local table justifies declaring FCAP unsupported. The actual FCAP handler, if implemented on this build, has not been located sufficiently to establish its ownership, arbitration or disable behavior.

The earlier thermal-overwrite example remains a **conditional risk**, not a demonstrated conflict on the Thor. Its stock CPU cooling module uses frequency QoS rather than the old FCAP setter. We have not shown that a Thor thermal component currently owns an FCAP vote, that a new writer would overwrite such a vote, or that firmware clearing would remove thermal protection. We also have not demonstrated a safe private vote that ClusterTune could release. Simply saving an effective cap and writing it back would not solve ownership: another actor could change its request during the test.

**A separate standard SCMI route is present but its ordinary limit commands reject requests.** The active CPUCP image identifies itself as `CPUCP.FW.1.0-00085-KAILUA.EXT-1` and contains RISC-V executable code. Initialization installs a six-protocol discovery list, `13 83 86 87 80 84`, and the discovery callback enumerates it. Protocol `0x13` is therefore included in this image despite the missing live device-tree binding described above. Its dispatcher routes protocol `0x13` to `0x17d0628a`; the command table at `0x17d0e0f4` maps the standard performance messages to these handlers:

| Message | Handler | Static behavior in this stock image |
| --- | --- | --- |
| `PERF_LIMITS_SET` (`0x05`) | `0x17d06434` | Calls the constant-return helper at `0x17d06282`, appends status `-3`, and returns without parsing or applying a requested cap. |
| `PERF_LIMITS_GET` (`0x06`) | `0x17d06440` | Appends status `-3` and returns without a maximum/minimum result. |
| `PERF_DOMAIN_ATTRIBUTES` (`0x03`) | `0x17d06322` | Uses a constant-zero helper for bit 31, leaving the standard limit-setting capability clear. |

The response helper at `0x17d068e0` copies the status word unchanged. In the [Linux SCMI status definitions](https://github.com/torvalds/linux/blob/v6.1/drivers/firmware/arm_scmi/driver.c), `-3` means **invalid access/permission denied**, not “not supported” (`-1`) or “invalid parameters” (`-2`). The [performance protocol implementation](https://github.com/torvalds/linux/blob/v6.1/drivers/firmware/arm_scmi/perf.c) identifies messages `0x05/0x06` and bit 31. The constant failure here is not a sysfs file-permission problem that Android root access fixes; there is no caller-dependent permission check in these handlers to pass by escalating the app. No runtime request was sent, and the reason for Qualcomm's fixed denial is unknown. This establishes an obstacle to the ordinary SCMI limit commands, not the absence of every possible vendor or fast-channel mechanism, and not the behavior of the separate LMH/DCVSH FCAP command.

**Outcome:** this investigation found no verified FCAP prior-vote getter, app-specific ownership or restoration mechanism, and no usable ordinary SCMI limit setter/getter in the inspected CPUCP image. It does not establish a safe independent physical ceiling for ClusterTune. No runtime implementation was added.

[Additional image hashes](research/underclock-dashboard/direct-controls/ownership-stock-manifest.json), [CPUCP dispatch/limit excerpts](research/underclock-dashboard/direct-controls/ownership-cpucp-excerpts.txt), [HYP forwarding/lookup excerpts](research/underclock-dashboard/direct-controls/ownership-hyp-excerpts.txt), and the [complete local HYP dispatch table](research/underclock-dashboard/direct-controls/ownership-hyp-dispatch.json) preserve the bounded evidence without committing raw firmware. The [host-only extractor](research/underclock-dashboard/direct-controls/inspect_limit_handlers.py) verifies the recorded image hashes and regenerates those excerpts with Capstone 5.0.6. [The final read-only snapshot](research/underclock-dashboard/direct-controls/ownership-readonly-final-device-state.txt) shows uptime increasing from 5,465 to 8,096 seconds and unchanged CPU minima/maxima, modes, governors, GPU ceiling, Standard mode, boost/C1DCVS switches, sleeping screens and zero MSM minimum votes.

### FCAP dispatch proof and staged test result

After the user requested a plan and execution, a [five-stage investigation](research/underclock-dashboard/direct-controls/fcap-proof-plan.md) was recorded: locate the implementation; establish ownership/release; prepare observation/recovery; test an independent physical ceiling; verify other requests survive release and restore the full configuration. The first stage produced a concrete result that prevents the later cap experiments through this interface.

**The actual stock TZ command lookup was located at `0x156c4934`.** It uses a generated comparison tree and halfword jump table, explaining why complete SMC-ID literal searches were uninformative. The LMH table at `0x1577cb24` covers IDs `0x02001301..0x02001312`. Command `0x02001310` selects branch `0x156c6060`, argument descriptor `0x15`, and handler `0x156edf1c`. This is the documented LMH/DCVSH config transport used by the older FCAP code, and matches the stock kernel wrapper's request ABI. The handler is `bti c; mov w0, #-4; ret`. It does not read a request, store a vote, choose an owner, call CPUCP or set/release any cap. In the [Qualcomm SCM error definitions](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/qcom_scm.h), `-4` denotes not supported. This is separate from SCMI's status namespace and the previously established CPUCP limit-command denial.

An independent bounded host simulation of the **unmodified stock lookup bytes** confirmed its selected function and ABI. Known INFO, peripheral-authentication and I/O command lookups calibrated the interpretation without executing those handlers. Exact-matched LMH/DCVSH, debug-set, debug-type-query and sensor-init stubs were then simulated individually: all returned `-4` in three instructions, with no writes and no request access. Only code, the read-only table and a scratch response were mapped; execution bounds, a finite instruction count and a write guard prevented general firmware execution or hardware access. No SMC instruction was executed, no module loaded and no real firmware command sent. Static tracing of the generic invocation path separately confirms that the selected pointer becomes the called function.

The generic INFO availability implementation checks **recognition by lookup**, rather than calling the selected setter. It therefore cannot distinguish this registered stub from a functioning FCAP implementation. A positive availability result would not establish a usable cap. The debug-type enumeration command itself also selects a fixed-error stub in this image, removing the practical value of the proposed query bridge for this particular lead. No bridge was built or loaded.

**Decision:** there is no cap ownership/restoration behavior to test in this identified implementation, because the setter never creates a vote. The FCAP route was stopped at the first prerequisite instead of attempting a cap-and-clear experiment. This conclusion concerns the extracted stock image and this documented command, not every possible vendor service, hardware control, firmware build or runtime modification. Live firmware execution was not inspected; a real runtime call would add confirmation of routing, but cannot turn the inspected stub into a supported setter. No ClusterTune runtime change was added.

[The plan and detailed derivation](research/underclock-dashboard/direct-controls/fcap-proof-plan.md), [host simulator](research/underclock-dashboard/direct-controls/emulate_lmh_dispatch.py), [results with instruction traces](research/underclock-dashboard/direct-controls/fcap-proof-dispatch.json), and [static dispatch/handler/invocation excerpts](research/underclock-dashboard/direct-controls/fcap-proof-tz-excerpts.txt) preserve the evidence. [The final snapshot](research/underclock-dashboard/direct-controls/fcap-proof-final-device-state.txt) matches the previous configuration with increasing uptime: original CPU limits/modes, `walt`, GPU ceiling, boost/C1DCVS switches, Standard mode, sleeping screens and zero MSM minimum votes. Raw firmware remains outside the repository.

### Stock driver verification

Stock module disassembly confirms that `qcom_cpufreq_hw_get()` reads the same performance-state offset that `fast_switch()` writes, then returns the corresponding frequency-table entry. The kernel module identifies the exact stock build `5.15.123-gafd857749d1f`. This strengthens the earlier explanation for why `cpuinfo_cur_freq` can report the request while independent hardware status/counters show a higher clock. Stock `qti_cpufreq_cdev.ko` calls `freq_qos_update_request()` for its cooling cap; it does not introduce a separate secure hardware limit. The hardware debug module's exported register dump remains read-only. These particular conclusions now rest on stock binary inspection, not an assumption that all related LineageOS source is identical.

### Existing projects and reports

The relevant current Thor ROM project is [LineageOS's AYN Thor tree](https://github.com/LineageOS/android_device_ayn_odin2thor/tree/lineage-23.2); no applicable LegionOS Thor implementation was identified. Searches were scoped to this handheld, avoiding the unrelated Xiaomi phone also codenamed `thor`. The inspected LineageOS Thor/common configuration, AynParts/hardware controls, and Thor kernel/device-tree/module change lists contained no direct SRB disable or independent CPU hardware-cap implementation. C1DCVS remains in first-stage module loading. A seemingly relevant [v2 power-level change](https://review.lineageos.org/c/LineageOS/android_kernel_ayn_qcs8550-devicetrees/+/492008) is GPU bin selection, not CPU limiting.

| Project inspected | Relevant implementation | Result for this issue |
| --- | --- | --- |
| [ROCKNIX SM8550](https://github.com/ROCKNIX/distribution/tree/next/projects/ROCKNIX/devices/SM8550/patches/linux) / [Thorch](https://github.com/thorch-os/thorch) | Custom Linux hardware/kernel patches | No SRB/direct CPU-cap fix identified in the inspected patch stack. |
| [PULSE prime-core boost limit](https://github.com/keiretrogaming/pulse/blob/0d2893e67cee0497e3fe624237679d104dd9c472/app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt#L304) | Chooses the second-highest supported prime frequency and applies ordinary min/max nodes | A software ceiling, not a hardware SRB switch. |
| [8Gen2 Frequency Limiter](https://github.com/TatshSiow/8Gen2-Frequency-Limiter/blob/4e03ceb40140bcfca48d8327650c55f2c3d12c3b/service.sh), [Kelvin](https://github.com/Anonym0usWork1221/Kelvin/blob/3d342c79819d24a82c81e10e9447331610a6478f/daemon/soc/socutil.c#L130), [Calibrate-SoC](https://github.com/mayusi/Calibrate-SoC/blob/b7f6b93d66dee1a2dd9dec581feefc7ba47a0bac/app/src/main/java/io/github/mayusi/calibratesoc/data/tunables/Tunables.kt#L26-L28) | CPUFreq sysfs limits, sometimes protected permissions | No additional hardware ceiling identified. |
| [Jesty Thor Fix](https://github.com/JestyLabs/Jesty-Thor-Fix/blob/bcfa03b99f53c90f5dbc7bf6b4ffd5570b173eba/src/com/thor/displaypowertest/CpuFixController.java#L594) | Disables display system-load checking and restarts compositor | Addresses a distinct low-load LITTLE/BIG pinning problem; not the demonstrated prime overshoot. |
| [PICO4 EPSS investigation](https://github.com/hhhbwc/pico4-cpu-dvfs-investigation/blob/main/source/cpu_oc_k1.c) | Failed speculative overclock write to the desired-Pstate register on SM8250 | Does not implement a successful hardware limiter/SRB disable; its undocumented register interpretations cannot be transplanted to SM8550. |

[Reddit Odin3/Thor scripts](https://www.reddit.com/r/OdinHandheld/comments/1snp9xd/easy_way_to_improved_odin_3_temps_battery_life/), [OnePlus underclock measurements](https://www.reddit.com/r/oneplus/comments/1i8tp0c/root_oneplus_13_cpugpu_tuning_guides/), [Motorola performance-companion disabling](https://xdaforums.com/t/guide-for-rooted-edge-2023-on-how-to-reduce-performance-and-increase-battery-life.4732243/), and [Armada's Thor clocks discussion](https://github.com/armada-os/armada/issues/175) were also checked. They describe software caps/service changes or benchmark/power benefits, rather than an independently validated SRB disable. Useful underclocking is not equivalent to a strict physical ceiling. No inspected project's claim establishes the latter. This negative result does not cover private, unindexed, or undiscovered firmware implementations.

[Stock artifact hashes](research/underclock-dashboard/direct-controls/stock-artifact-manifest.json), [SCMI inventory](research/underclock-dashboard/direct-controls/scmi-inventory.txt), [kernel configuration excerpt](research/underclock-dashboard/direct-controls/kernel-config-excerpt.txt), targeted stock module disassembly, and [community source findings](research/underclock-dashboard/direct-controls/community-findings.txt) preserve the investigation. [Final device state](research/underclock-dashboard/direct-controls/final-device-state.txt) confirms the original Large profile limits, modes, governors, GPU ceiling, boost/C1DCVS enabled, Standard performance, sleeping screens, and zero MSM minimum votes. No new profile or ClusterTune runtime implementation was added.

## Report and recording

[Reddit report](https://www.reddit.com/r/AynThor/comments/1wz3yqe/so_i_just_updated_clustertune_it_was_successfully/): an AYN Thor owner reports that updating ClusterTune stops the CPU from dropping below 3.19 GHz. Several replies describe similar behavior and improvement after returning to 1.2.2. These are user observations, not controlled measurements of energy use or proof of a particular cause.

The supplied 16-second recording shows:

- Stock initially selected; ClusterTune's ceiling readbacks are approximately 2.02 / 2.80 / 3.19 GHz, while the AYN dashboard reports a current CPU frequency of 1.84 GHz.
- Selecting Small Underclock changes ClusterTune's ceilings to approximately 1.79 / 2.32 / 2.48 GHz and shows a success toast. The AYN reading changes to 3.19 GHz.
- Medium, Large, and Medium selections change the displayed ceilings to the corresponding preset limits. The dashboard continues to show 3.19 GHz.
- The AYN performance mode remains Standard. The recording does not show minimum-frequency readbacks, the governor, independent frequency votes, firmware version, or an independent measurement of operating frequency.

The `Now` row displays configured maximum readbacks, not current operating frequency. `Override` beside it is the manual-edit button, not a warning about firmware overriding the profile.

## Dashboard inspection

Read-only extraction of `/system/app/DualScreenAssistant/DualScreenAssistant.apk` from the available Thor and decompilation show that its CPU ring:

- Polls `/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_cur_freq` every 1,500 ms.
- Reads `cpuinfo_max_freq` separately for the ring's scale.
- Attempts to make the current-frequency node readable when a read fails; it does not substitute the hardware maximum as a fallback frequency in this code path.

This rules out assuming that this dashboard merely shows the configured maximum. The reporter's firmware/dashboard build has not been extracted or compared.

In the [related Qualcomm kernel driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/qcom-cpufreq-hw.c), `cpuinfo_cur_freq` ultimately resolves a performance-state register index through the frequency table. This is driver-reported frequency, not an independent cycle-counter measurement of physical clock rate. The related source is not established to be identical to either stock device kernel.

## Initial read-only observations

Read-only observations on the previously authorized Thor:

- Firmware: `Thor_V1.0.0.377_20260206_165408_user`.
- Installed local development build identifies itself as 1.2.2, but contains the minimum-protection implementation tested before the 1.3.0 version bump. It must not be used as evidence about the public 1.2.2 behavior.
- Existing Large Underclock profile: CPU maximums 1,459,200 / 1,785,600 / 1,843,200 kHz.
- Protected CPU minimums: 307,200 / 499,200 / 595,200 kHz, permissions 0440, governor `walt`.
- [Thirty sequential samples per policy](research/minimum-frequency/reddit-regression-readonly.csv) found no `scaling_cur_freq` or `cpuinfo_cur_freq` reading above the respective maximum. The prime core varied between 595,200 and 1,843,200 kHz.
- MSM performance CPU minimum requests were zero when checked; maximum requests were unrestricted.
- The lower screen was off during the screen capture. This does not reproduce the reporter's active-dashboard setup.

No profiles, permissions, governors, firmware properties, or applications were changed in this pass.

## Other leads considered

1.3.0 introduces ownership and write protection of manual-profile minimum nodes. This was the initial regression suspect, before the active-dashboard comparison isolated the conversion defect. Production builds disable code minification, so a release-only shrinker omission was not supported by the build configuration.

An [older similar report](https://www.reddit.com/r/AynThor/comments/1vfcr8v/clustertune_not_working/) was initially described as bad dashboard information, but the developer later acknowledged a real issue fixed in 1.0.2. Commit `675b557` repaired stale/read-only minimums before setting ceilings. The apparent similarity is a reason to investigate, not proof that the same defect returned.

[Jesty Thor Fix](https://github.com/JestyLabs/Jesty-Thor-Fix) separately describes dashboard/display-induced high clocks and a compositor property workaround. That is distinct from the confirmed prime-core display conversion. Its workaround restarts the display/UI and was not applied.

For a report with actual over-cap current readbacks, or a different dashboard implementation, collect the affected device's firmware version and simultaneous minimum/maximum/current reads. The supplied video alone did not establish physical over-cap operation; the active-dashboard reproduction supplied the missing comparison.
