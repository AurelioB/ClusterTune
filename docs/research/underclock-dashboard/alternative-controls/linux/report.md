# Linux-side follow-up for the separate CPUCP SGI9 policy

No Linux SGI9/request-buffer consumer or supported switch for private-policy enable word `0x17d2a71c` was identified in the bounded stock module/source inspection. This is a negative inventory result, not a proof of absence throughout the whole kernel. The parallel firmware investigation has independently located TZ consumers for the exact CPUCP request buffers, so interpreting the firmware writes as Linux scheduler hints would be unsupported. The TZ branch's ultimate hardware effects belong to that investigation.

Scope: host-only extraction from the existing stock vendor ramdisk, native module disassembly/symbol inspection, and pinned primary source. No device read/write, IRQ, MMIO, module load, sysfs setting/readback or actual firmware request. All new artifacts are under `/tmp/ct-c1dcvs-remaining/sgi9`; no repository edits.

## Stock module surfaces

Inspected ten modules: stock C1DCVS client/vendor, CPUFreq hardware driver; newly extracted PMU client/vendor/library, MSM performance, WALT scheduler, DCVS floating-point helper and Qualcomm DCVS. Hashes for the seven newly extracted modules are in `module-manifest.json`; existing C1/CPUFreq hashes were already recorded elsewhere.

- **C1DCVS** exposes its known configuration operations and `c1dcvs_enable` export, with no IRQ-registration import or SGI handler. Its six knobs belong to protocol `0x87`; the enable setter previously traced writes `0x17d2a718`, not the separate `0x17d2a71c` word.
- **PMU client/vendor/library** registers no IRQ/SGI handler. PMU vendor wrappers are `scmi_pmu_map` at `.text+0x1c0` (message 11, 72 bytes), `scmi_set_enable_trace` at `+0x380` (message 12) and `scmi_set_caching_enable` at `+0x3a8` (message 13). Protocol is `0x86`. This is map/trace/counter-caching configuration, not the private policy's direct enable control.
- **PMU library** consumes local perf/AMU counters and supports a DT-selected `pmu-base` region. Its shared-memory path caches counters for CPUCP while CPUs enter idle/power collapse/hotplug. It does not process the three SGI9 policy request areas. `rimps_pmu_init` at `.text+0x39c` establishes the protocol and map/caching setup. Stock probe includes `devm_ioremap` at `.text+0x1518`, but mapping a DT PMU cache resource is not evidence of mapping the firmware policy request buffers.
- **CPUFreq hardware** has `request_threaded_irq` and named `qcom_lmh_dcvs_handle_irq` at `.text+0x1aa8`, consistent with its LMH/throttling notification path. No SGI9 consumer or reference to the three request-buffer areas was identified here.
- **MSM performance** includes `cpucp_plh_init` at `.text+0x388`, which obtains its separate PLH protocol. Its visible operations concern performance load hints/notification/sample/log configuration. This is not a demonstrated getter/setter for `0x17d2a71c`, nor an identified handler for the SGI9 buffers.
- **WALT, Qualcomm DCVS and DCVS FP** did not expose IRQ registration for an SGI9 policy consumer in the examined symbols. WALT contains ordinary IRQ-work/scheduling trace machinery, which is not itself evidence of handling this firmware SGI.

`stock-consumer-summary.json` retains each module's IRQ-registration, CPUCP/C1 symbol and physical-map imports; `stock-consumer-excerpts.txt` retains selected native control/PMU/IRQ excerpts. Full native disassemblies and symbols remain temporary verification artifacts.

## PMU knobs do not establish a policy disable

Stock PMU-library sysfs handlers are `show_enable_counters` at `.text+0x2b20` and `store_enable_counters` at `+0x2b68`. The setter recognizes magic enable/disable strings, recreates or caches/releases performance counters, and updates its local state. The enabling branch calls setup at `+0x2bc8`, then `configure_cpucp_map` at `+0x2c5c`; the disabling branch caches event totals and releases kernel perf events. Thus an exposed file named `enable_counters` is not a firmware policy switch.

The pinned source puts those attributes under CPU `pmu_lib` and names the strings `BEEFDEAD` / `DEADBEEF`; no settings were applied. Disabling counters would perturb several algorithms' inputs and caches rather than isolate this separate policy. It is not a low-cost read-only observation or validated SRB workaround. The vendor firmware task already established PMU message 13's boolean is at `0x17d2a400`, which is distinct from private enable word `0x17d2a71c`.

## Relevant primary source

Pinned repository commit: `0d9a32622533fa6dbeeab60207977171c286c458` in LineageOS/android_kernel_ayn_qcs8550. Inspect these paths through the corresponding raw.githubusercontent.com URL:

- `drivers/firmware/arm_scmi/pmu_vendor.c`
- `drivers/soc/qcom/dcvs/pmu_scmi.c`
- `drivers/soc/qcom/dcvs/pmu_lib.c`
- `drivers/soc/qcom/msm_performance.c`
- Existing C1DCVS protocol/client paths from the prior report.

The primary source provides names and intended organization; native stock modules establish the actual exported operations/imports and cited instruction offsets. No claim of whole-source byte identity is made. The pinned recursive source tree contains no dedicated QCOM `cpucp` request-consumer driver; matching `cpucp_if.h` in Habana hardware is unrelated, while Qualcomm `trace_cpucp.h` defines a generic trace record. File-name inventory alone is not a kernel-wide semantic search.

Decision: prioritize the now-located secure firmware consumers before pursuing Linux-side knob experiments. There is no established Linux frontend here that directly disables the separate policy or applies a physical clock ceiling through these request buffers.
