# CPUCP vendor protocols and fast channels

Image: stock Thor cpucp_b.bin, SHA-256 19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808. Read-only ELF32 RV32+C decoding with Capstone 5.0.6; optional entirely offline function emulation with Unicorn 2.1.4. This analysis used no device commands, MMIO writes, reboot, module loading or device UI. Emulation memory writes affect synthetic host memory only.

Result: No newly established independent physical CPU ceiling or SRB-disable control. Protocol 0x80 provides actual memory/cache monitor configuration; 0x86 configures performance counters; 0x84 exposes telemetry. Standard PERF advertises fast channels but its stock descriptor callback supplies no descriptor. Advertised vendor 0x83 remains unresolved.

## Registration and coverage

The vendor dispatcher at 0x17d06808 handles 0x80..0x88 from outer dispatcher 0x17d06210. Its nine-entry runtime table is at 0x17d0d528, entries {u32 protocol, u32 handler}; all 18 file-backed words initially zero. For vendor messages >=3, it looks for the registered protocol and calls handler(protocol, command, request+0x1c). Generic commands 0,1,2 are answered before table lookup and therefore do not prove a working vendor module. Unmatched vendor messages produce status -1 through response helper 0x17d068d2.

The registration helper at 0xd82e75d4 takes protocol in a0, handler in a1. Scanning every executable ELF segment resolves these four callers, including direct and compressed JAL encodings missed by an AUIPC/JALR-only scan:

| Protocol | Handler | Registration call |
|---|---|---|
| 0x87 C1DCVS | 0x17d201a8 | 0x17d206fc AUIPC/JALR |
| 0x86 PMU | 0xd82e5736 | 0xd82e588a JAL |
| 0x80 MEMLAT | 0xd82e60fe | 0xd82e687e JAL |
| 0x84 CPUFreq statistics | 0xd82e714a | 0xd82e71aa c.JAL |

Protocol list at 0x17d28658 is [0x13,0x83,0x86,0x87,0x80,0x84]. No resolved registration for 0x83 was found, and no literal pointer to the registration helper occurs in the complete image. This cannot exclude arbitrarily computed calls, later initialization, runtime-loaded code, or undocumented transport. Keep 0x83 classified as advertised with unidentified handler; do not call every possible 0x83 operation unsupported.

## 0x80 MEMLAT

The stock jump table at 0x17d28288 has 36 entries covering commands 3..38, with 5..15 falling into the unsupported branch. Its command IDs exactly match the related immutable [memlat_vendor.c](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/memlat_vendor.c). Protocol ID and meaning are explicit in [scmi_memlat.h](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/include/linux/scmi_memlat.h).

Relevant scalar command request is little-endian {u32 hw_type, u32 monitor_index, u32 value}. Command 33 MIN_FREQ enters 0xd82e65f4; 34 MAX_FREQ enters 0xd82e6650; 35 CURRENT_FREQ enters 0xd82e66ac. Lookup functions at 0xd82e6022 (group) and 0xd82e607c (monitor) search registered group/monitor structures by hardware type and monitor index, not CPU performance-domain index. Unknown group/monitor gives status -2, 4 response bytes. Valid min/max setters write monitor offsets +0x24/+0x26 as u16; for hw_type !=3, value is divided by 1000, matching kHz-to-MHz representation. Type3 stores the raw value. Min/max setters also clamp that monitor's current vote (+0x38) if needed and call group application 0x17d2372a. Their success response is status0, 4 bytes; CURRENT_FREQ returns status0 plus the monitor's current u16 value multiplied by1000, 8 bytes.

Application at 0x17d2372a selects the highest current monitor vote in a group (0x17d2376e..0x17d2377c). If group hardware type is2, branch 0x17d23884 leads to read/modify/write of L3 desired-performance low6bits at 0x17d90470 (instructions 0x17d23988, 0x17d2399a). For group types0/1/3, it constructs an ordinary DCVS vote and calls 0x17d2299a from 0x17d238dc. Thus even a max_freq setter is a monitor's internal vote constraint. These handlers do not identify CPU cluster ceiling registers or an SRB-disable switch. Changing monitor max could reduce a memory/cache vote and indirectly change CPU behavior; that is not established physical CPU cap control.

Additional command families are event maps, monitor creation, adaptive frequency parameters, memory latency thresholds, frequency scaling, sample timer, and logs/timestamp. See vendor-analysis.json for all25 recognized command branches. PMU configuration accompanies these memory-monitor functions.

## 0x86 PMU

The stock handler 0xd82e5736 recognizes11,12,13 exactly as [pmu_vendor.c](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/pmu_vendor.c) and [scmi_pmu.h](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/include/linux/scmi_pmu.h): 11 SET_PMU_MAP, 12 SET_ENABLE_TRACE, 13 SET_ENABLE_CACHING.

11 accepts72 map bytes (8 CPUs times9 events) mapping counters. It iterates available CPUs, skips special/reserved event indices and absent counters, and marks map initialized at0x17d2a090. 12 accepts u32 enable; nonzero calls trace enable0x17d24d1e, zero calls trace disable0x17d24d4a. 13 accepts u32 enable and stores Boolean flag0x17d2a400 at0xd82e5822/nonzero or0xd82e5830/zero. These commands return status0/4bytes; unknown messages store-1. The protocol and source identify the caching control as PMU caching, not processor hardware cache or SRB control. No frequency ceiling setter was found here.

## 0x84 statistics shared memory

0xd82e714a handles3 as no-op successful status0/4bytes, and16 as memory discovery. Command16 calls callback through0x17d0d518 (the initializer table gives0x17d07712), then returns five words: status0, attributes0, address_low0x17d0a800, address_high0, size0x800; handler returns20bytes. Callback0x17d07712 sets length0x800 and returns0x17d0a800 irrespective of caller's mode argument. The related [CPUFreq statistics protocol](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/cpufreq_stats_vendor.c) uses command16 for telemetry discovery. Discovery does not establish writable cap controls in this buffer.

## Standard PERF fast channels

Domain attributes flags are formed at0x17d06334..0x17d06340: shift return from helper0x17d06286 (constant0) by31, OR0x4c0c0000. Result0x4c0c0000 has BIT31clear but BIT27set. Related [perf.c](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/perf.c) defines BIT27 as fast-channel support, so firmware advertises support.

PERF_DESCRIBE_FASTCHANNEL command11 enters0x17d064d4, checks domain against runtime count0x17d0d524, and invokes callback0x17d0d50c. Initialization copies this callback from0x17d28610+0x30, whose value is0x17d20dc6. Callback's complete implementation is c.li a0,0; c.jr ra, and it never writes the supplied descriptor pointer. Dispatcher interprets return0 as success, appends status0, then copies eleven u32 words from its stack offsets+4 through+0x2c (44bytes) to response. These are uninitialized descriptor data, not an established address, size, or doorbell.

Offline reproduction emulates the unmodified firmware dispatcher starting0x17d0628a, with request{protocol0x13,command11,domain0,described_message5}, firmware initializer callback table, synthetic four-domain count, and known stack fill. Both runs return after228instructions with a0=0 and SCMI channel length52 (header4+status4+descriptor44). All11 descriptor words are0xa5a5a5a5 in one run and0x3c3c3c3c in the second. The callback0x17d20dc6 was visited. This proves the descriptor data depends on prior stack content for this path. It does not discover usable cap-address information, and no live firmware command was sent. Report the advertised/no-op mismatch rather than claiming bit27clear or usable fast channels.

## Reproduction

- inspect_vendor.py IMAGE OUTDIR requires Capstone5.0.6, checks image hash, regenerates vendor-analysis.json and bounded vendor-excerpts.txt from executable ELF segments. It resolves direct JAL/c.JAL and adjacent AUIPC/JALR calls to the registration helper.
- emulate_fastchannel.py IMAGE --poison 0xa5 or --poison 0x3c with Unicorn 2.1.4 reproduces JSON results.
- Reproduce from this directory with `python3 inspect_vendor.py /path/to/cpucp_b.bin /path/to/output` and `python3 emulate_fastchannel.py /path/to/cpucp_b.bin --poison 0xa5` (repeat with `0x3c`).
- source-manifest.json records immutable source URLs and SHA-256 hashes. Full downloaded related sources stay in this temporary directory and need not be committed. Firmware image stays outside these artifacts.
