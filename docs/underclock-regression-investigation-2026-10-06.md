# AYN dashboard's false 3.19 GHz reading below 1 GHz

Status: the dashboard symptom was reproduced on the available Thor. CPU driver readings remained within the selected limits. The extracted dashboard has a unit-conversion bug that explains the symptom; no ClusterTune runtime workaround was applied.

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

The tests do not measure battery life, physical cycle-counter frequency, or sustained gaming performance, and do not establish that every user's battery complaint has the same cause. The reporter's firmware was not available. The supplied symptom and its unit-conversion mechanism are nevertheless reproduced on the tested firmware.

The older public 1.2.2 lets firmware restore the prime floor above 1 GHz, which explains why returning to it can remove the false display reading. Forcing ClusterTune's floor above 1 GHz would hide this display bug by giving up the lower operating range; it is not a fix for the dashboard's conversion.

[Raw phase observations](research/underclock-dashboard/profile-and-minimum-comparisons.csv), [profile transitions](research/underclock-dashboard/profile-measurements.txt), [minimum comparisons](research/underclock-dashboard/minima-comparison.txt), and [Stock restoration](research/underclock-dashboard/stock-restoration.txt) are retained. Captures include the [Medium limits](research/underclock-dashboard/dashboard-medium-top.png), [false Medium dashboard reading](research/underclock-dashboard/dashboard-medium-dashboard.png), [writable-minimum reading](research/underclock-dashboard/comparison-writable-dashboard.png), [protected floor above 1 GHz](research/underclock-dashboard/comparison-protected-above-1ghz-dashboard.png), and [false reading after restoring the low protected floor](research/underclock-dashboard/comparison-protected-below-1ghz-dashboard.png).

### Restoration

Stock restored maximums to 2,016,000 / 2,803,200 / 3,187,200 kHz and released every minimum node to its original 0660 mode. Thirty additional observations per cluster cover that transition; the combined dataset contains 810 policy observations with no current readback above its contemporaneous maximum.

The original pre-test development APK was then reinstalled with data preserved, and the original Large Underclock profile reapplied. The dashboard was closed, the 60-second screen timeout restored, and the Thor returned to its initial sleeping state. [Final verification](research/underclock-dashboard/final-restoration.txt) confirmed minimums 307,200 / 499,200 / 595,200 kHz, maximums 1,459,200 / 1,785,600 / 1,843,200 kHz, original minimum/maximum permissions, `walt` on all policies, GPU maximum 680 MHz, Standard performance mode, and cleared MSM performance minimum votes. No governor, thermal setting, display performance property, or persistent experimental frequency was left changed. Profile test switches remain in the app's history.

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
