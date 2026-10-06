# Stock EPSS configuration and CPUCP's separate adaptive policy

No supported SRB-disable command or independent physical CPU ceiling was identified. The strongest new result identifies the separate CPUCP periodic algorithm's prime-core output as cache/prefetch configuration. It must not be treated as an SRB switch.

This analysis used the previously extracted stock CPUCP, TrustZone and embedded ARM64 XBL images. All decoding and simulation occurred on the host. No device firmware command, MMIO access, setting change, module load, reboot or UI interaction was used.

## Boot configuration

Related `kalama.dtsi` maps Linux CPUFreq domains 0/1/2 to `0x17d91000`, `0x17d92000`, `0x17d93000`. Stock TZ independently contains a named domain table at `0x157afc00`, with 0x38-byte entries:

| Firmware domain | Name | EPSS base | PLL base |
| --- | --- | --- | --- |
| 0 | L3 | `17d90000` | `17a84000` |
| 1 | Silver | `17d91000` | `17a80000` |
| 2 | Gold | `17d92000` | `17a82000` |
| 3 | Gold_Plus | `17d93000` | `17a86000` |

This explains the numbering difference between Linux policies and firmware domains. Inspected TZ initialization at `15707b28..15707cd4` uses the PLL member, rather than an identified SRB register.

XBL functions `1486f1a4`, `1486f1dc`, `1486f20c` and TZ function `1570f250` set or clear bits in the common block `17d98000/04/08`. These are real startup controls, but their exact meanings remain undocumented in the inspected sources. They are not established SRB-disable or boost-ceiling bits.

The related [EPSS debug driver](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/qcom-cpufreq-hw-debug.c) calls offset `0xbc` `EPSS_DEBUG_SRB`; it reads the field and provides neither bit definitions nor a write ABI. C1's actuator instead modifies the low six bits at EPSS offset `0x88`. Its saved table at CPUCP `17d0c37c` has file-initializer values `[0,3,0,0]`. These are not live readings; indirect initialization writes remain possible. Neither that table nor the unspecified field can be presented as a frequency cap.

## The separate periodic algorithm

CPUCP callback `17d20000` checks C1 gate `17d2a718` before calling `17d2031c`, then independently checks `17d2a71c` before calling `17d24c36`. Both gates initialize to 1 in `d82e7270`. The second algorithm inspects per-core counters and threshold history and submits changed requests through `17d246d2(request, CPU, type)`; its last-request cache is `17d2a750 + CPU*6 + type*2`.

| Request | Selector | Payload | Eligibility |
| --- | --- | --- | --- |
| Type 0 | `17d08c48`, value 0 | `17d08c50` | CPU cluster mapping <=1 |
| Type 1 | `17d08c58`, value 1 | `17d08c48 + (cluster-1)*8` | CPU cluster mapping >2 |
| Type 2 | `17d08d10`, value 2 | `17d08d18` | CPU cluster mapping 3 |

Helper `17d246c0` writes `0x200` to `17190200 + CPU*0x40000`. The related GICR mapping and [Linux GIC register definitions](https://github.com/torvalds/linux/blob/v6.1/include/linux/irqchip/arm-gic-v3.h#L233), together with the [SGI-frame offset](https://github.com/torvalds/linux/blob/v6.1/drivers/irqchip/irq-gic-v3.c#L133), identify this as setting pending SGI9. It is an interrupt trigger to an AP core, not a frequency-register write.

## TrustZone readers resolve the prime-core effects

PC-relative literal loads in stock TZ reference the exact request buffers. MIDR dispatch `d8317c94`, mask `ffffffffff0ffff0`, selects these readers for event argument `0x45`:

| Masked MIDR | Consumer | Observed register field |
| --- | --- | --- |
| `410fd460` (Cortex-A510) | `d8319dac` | Type 0: `S3_0_C15_C1_4[16:6]` |
| `410fd470` (Cortex-A710) | `d831a870` | Type 1: `S3_0_C15_C1_4[63:61]` |
| `410fd4e0` (Cortex-X3) | `d831b7bc` | Type 1: `S3_0_C15_C1_4[63:61]`; type 2: `S3_0_C15_C1_5[14:11]` |

The middle reader selects payload `17d08c60/68/70/78` from MPIDR affinity 1 values 4/5/6/7. The X3 reader uses `17d08c78` and `17d08d18`, writing acknowledgement selectors 8 and 16 after applying them. Exact reader/write instructions are retained in `tz-excerpts.txt`.

Core identifiers agree with [Linux's Arm CPU definitions](https://github.com/torvalds/linux/blob/v6.6/arch/arm64/include/asm/cputype.h#L75). The `D48`/`D82` cases in this dispatch target a self-loop at `d831ba78`, rather than a no-op return. No device command was issued to exercise any of these paths.

The [Cortex-X3 technical reference manual](https://documentation-service.arm.com/static/62bb289431ea212bb6625392), sections A.1.15/A.1.16, defines the X3 fields:

- `IMP_CPUECTLR_EL1.CMC_MIN_WAYS[63:61]` controls L2 cache allocation for correlated-miss caching.
- `IMP_CPUECTLR2_EL1.PF_MODE[14:11]` controls prefetch aggressiveness.

Thus the inspected prime-core path adjusts cache/prefetch behavior, rather than a CPU-frequency ceiling or SRB-enable bit. These settings can affect performance and power indirectly. The exact A510/A710 field meanings were not established; X3 semantics must not be transplanted to another core. The A715 MIDR `410fd4d0` is absent from this exact dispatch, so no A715-specific consumer was established. The exact live SGI9-to-event-0x45 routing was also not reproduced; static matching establishes the buffers and effects without claiming a complete runtime interrupt trace.

No supported frontend for independently gating `17d2a71c` was found. Clearing that internal flag would not restore the last values already applied to per-core registers. This report does not propose writing it.

## Reproduction and limits

`extract_epss_boot_evidence.py` requires Capstone 5.0.6 and takes explicit `--stock`, `--ownership`, `--output` directories. The stock directory contains `tz_b.bin`; the ownership directory contains `cpucp_b.bin` and the previously extracted `xbl-embedded-0x55f94.elf`. It decodes ELF program headers and bounded address ranges, records SHA-256 hashes and generates the included excerpts, manifest and decoded tables. It never executes firmware. Compare generated hashes with `manifest.json` before interpreting a different image.

Candidate-reference scans are not full data-flow analysis. Unknown fields, indirect addressing, runtime-loaded code and unrecognized firmware services remain outside the conclusions. Raw firmware stays outside the repository.
