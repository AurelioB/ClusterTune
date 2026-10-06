# Remaining firmware controls: 2026-10-06

Three parallel investigations examined stock CPUCP vendor protocols, EPSS/SRB initialization and the remaining C1DCVS controls. None established a supported independent physical CPU ceiling or SRB-disable interface for ClusterTune. These conclusions concern the extracted Thor firmware, not every Qualcomm device or build.

| Route | Result | Evidence |
| --- | --- | --- |
| Vendor commands/shared memory | Identified memory/cache monitor limits, PMU configuration and telemetry; advertised protocol `0x83` still has no resolved handler. | [Vendor report](vendor/report.md) |
| Standard PERF fast channels | Advertised capability, but the descriptor callback is a no-op; two host simulations return descriptors made from prior stack contents. | [Vendor report](vendor/report.md#standard-perf-fast-channels) |
| EPSS startup controls | Located real common-block initialization writes; exact register meanings and a supported SRB toggle remain unproved. | [EPSS report](epss/report.md) |
| Second CPUCP adaptive algorithm | Traced shared requests to TZ readers. Documented X3 output controls cache allocation and prefetch aggressiveness. | [EPSS report](epss/report.md#trustzone-readers-resolve-the-prime-core-effects) |
| C1 thresholds/modes | Actual C1 enable gate bypasses their algorithm; previously measured overshoot persisted with it disabled. IPC handlers are unsupported on this image. | [C1 report](c1dcvs/report.md) |
| Linux frontend | No identified SGI9 request consumer or switch for the separate policy in ten inspected stock modules. | [Linux report](linux/report.md) |

All firmware decoding and emulation took place on the host. Device checks in this pass were file listings and cached readbacks only; no firmware SET/GET request, tracing activation, memory/register write, profile change or reboot was issued.

## Cached frontend inventory

Stock `memlat.ko` show handlers for `cpucp_log_level` and `cpucp_sample_ms` format local cached fields, rather than invoking firmware commands. The corresponding readbacks were 0 and 8. Stock instructions are in [frontend excerpts](frontends/stock-frontend-excerpts.txt); [cached readbacks](frontends/cached-interface-state.txt) also list an existing L3 prime-monitor frontend. The timestamp-offset show handler does send a GET request, so its contents were not read. Logs were not enabled or flushed.

The pinned related [MEMLAT driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/dcvs/memlat.c), stock setter and CPUCP implementation agree that monitor `max_freq` constrains an individual memory/cache vote. Firmware chooses the highest monitor vote in a group; this is not an independent CPU ceiling, or even a guarantee of the effective group ceiling. The related [DCVS driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/dcvs/dcvs.c) uses `boost_freq` as a floor. Neither name identifies a hidden CPU cap.

## Remaining leads

The unresolved advertised protocol `0x83` and undocumented EPSS startup/register fields remain research candidates. Neither currently supplies a command or setting suitable for a device experiment. A supported runtime control, its exact meaning and its release/restoration behavior must be identified before claiming a strict ceiling. The earlier coordinated-cluster experiment remains the only measured workaround in this investigation; no bundled profiles were changed.

Reproduction scripts, hashes, bounded instruction excerpts and host-simulation results are included beside each report. Full downloaded source, module disassemblies and proprietary raw images remain outside the repository. [Final read-only snapshot](final-readonly-device-state.txt) checks the existing CPU limits, permissions, governors, GPU ceiling, system minimum votes and performance mode. C1 sysfs getters were deliberately omitted because they invoke firmware GET commands; no C1 setting was changed in this pass.
