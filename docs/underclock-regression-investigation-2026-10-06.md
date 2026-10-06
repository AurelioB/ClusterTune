# AYN dashboard's false 3.19 GHz reading below 1 GHz

Status: the dashboard symptom was reproduced on the available Thor. The extracted dashboard has a confirmed unit-conversion bug. **Independent hardware measurements also found above-cap clock rates that driver readings and 3C do not reveal**, in both public 1.3.0 and 1.2.2. The display bug does not establish that frequency enforcement is perfect. No ClusterTune runtime workaround was applied.

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
