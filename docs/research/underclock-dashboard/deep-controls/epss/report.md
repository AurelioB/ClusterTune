# Stock EPSS control ownership and startup, 2026-10-06

No verified, reversible independent frequency ceiling or SRB-disable ABI emerges from this pass. The deeper result is an exact owner and startup writer for the register currently called `EPSS_DEBUG_SRB`, and firm CPUCP boot ownership for the common block. No device access, firmware invocation, MMIO read/write, reboot or module load occurred. Bounded evidence and reproduction scripts accompany this report.

## EPSS +0xbc: a real startup-programmed field

CPUCP getter `0xd82e09bc` returns `0xd82f7a58 + domain*0x18`. File-backed records' member +0x14 contains:

| Firmware domain | Object VA | EPSS base | Domain initialization +0xbc bit 0 | Store VA |
|---|---|---|---|---|
| 0 L3 | 0xd82f7a58 | 0x17d90000 | clear | 0xd82e0e00 |
| 1 Silver | 0xd82f7a70 | 0x17d91000 | clear | 0xd82e108a |
| 2 Gold | 0xd82f7a88 | 0x17d92000 | set | 0xd82e10a8 |
| 3 Gold_Plus | 0xd82f7aa0 | 0x17d93000 | set | 0xd82e0de8 |

Initializer `0xd82e0d04` calls that getter at `0xd82e0d12`, copies the object to s0 and the requested domain to s1, and each cited store obtains the actual EPSS address from s0+0x14. Domain 1 branches from `0xd82e0dbe` to `0xd82e1074`; domain 2 branches from `0xd82e0dc6` to `0xd82e1090`; domain 3 reaches `0xd82e0dd0`; domain 0 reaches `0xd82e0dee`. Each read-modify-write preserves other bits.

The domain activation function `0xd82e0222` checks object+0xc and skips the initialization path when that flag is already nonzero. Calls to the initializer at `0xd82e026c/02f6/031e` are followed by setting object+0xc=1. Observed activation callers include a domain loop at 0xd82e5d8a and dispatcher cases at 0x17d008b2/08ca/08d6/08e2. This is guarded domain activation/initialization, not proof of a periodic runtime toggle. The register address is the very same `base+0xbc` that the pinned related Linux debug driver calls `EPSS_DEBUG_SRB`.

This demonstrates that bit 0 is software writable and deliberately configured differently for domains. It also demonstrates why a persistent read value of 1 need not identify an active boost event. It **does not establish** that bit 0 is the SRB-enable bit, whether zero disables only SRB, whether changing it after boot is permitted, or which release/restoration sequence preserves firmware state. Calling it an actionable switch would exceed the evidence.

An offset-form scan of the available CPUCP disassembly found these four stores and their reads as the only identified EPSS+0xbc accesses. The other apparent `+0xbc` load at `0x17d03ad4` resolves to a RAM policy entry at `0x17d0c2b0 + domain*4 + 0xbc`, not MMIO. This is a bounded scan result, not completeness proof: generic writes, indirect offsets, alternate firmware paths or unresolved pointers can evade it. The observed writes are in CPUCP; no runtime public frontend was identified in this pass.

## EPSS +0x88 remains undocumented

C1 helper `0x17d24656` resolves the same domain object, checks presence, and accesses member+0x14+0x88. For value!=1 it ORs the low six bits with 0x3f; for value==1 it sets low six bits from indexed file-backed table `0x17d0c37c`, with values [0,3,0,0], while preserving the rest. The only direct table-address reference found remains `0x17d246a8`. No sampled save of the hardware register or initialization write to that table was identified. Treat it as a static policy/default table, with indirect runtime mutation still unexcluded.

Old OSM code names offset0x88 `DROOP_RELEASE_TIMER_CTRL`, but that code also names offset0xbc `CORE_DCVS_CTRL`, whereas the current debug driver names0xbc `EPSS_DEBUG_SRB`. The generations do not have established register compatibility. The old timer meaning and boost-FSM bit positions cannot be transferred to Thor EPSS. No exact Thor +0x88 field name, unit or independent-ceiling behavior was established.

## Common 0x17d98000/04/08 belongs to CPUCP boot plumbing

XBL ARM64 helper `0x1486f1a4` sets bit0 in all three registers and returns the readback of +0x04 bit0. Its wrapper `0x14832c4c` reports errors with filename **boot_cpucp.c**, from string `0x148ad2c8`. Wrappers `0x14832c90/94` invoke the clear-all-bit0 and mixed-bit initialization helpers. These wrappers appear in a boot function table at `0x148bf018/20/28`.

TZ initializer `0x1570f1dc` resolves DAL configuration path **/cpucp/cpucpcfg** and property **tgt_cpucp_config** (strings `0x1578273b/74b`); the following `0x1570f250` routine initializes common +0x08 bit0 and +0x04 bit1. CPUCP itself clears common +0x00 bit0 at `0x17d00460/64`, writes +0x1c=0x2ef2c, and sets +0x14 bit0 during startup. Exact hardware bit names are not established. These are demonstrably involved in CPUCP bring-up; a raw clear of the common block is not a demonstrated isolated SRB control.

The same common block includes vote-routing state at +0x28: CPUCP `0xd82e01a2` reads bit0 at `0xd82e01d8`; for firmware domain0 it chooses an EPSS+0x90 write when set and EPSS+0x320 when clear. Other domains use EPSS+0xb0 bit0 to select their per-client versus shared request path. Helper `0x17d20776` programs common+0x28 as a boolean and clears L3 EPSS+0xb0 bit0. This resolves a register dependency, not a ceiling interface or an SRB algorithm.

## Sources and reproduction

Current pinned related debug offset definition: [qcom-cpufreq-hw-debug.c](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/qcom-cpufreq-hw-debug.c).

Historical register model only: [clk-cpu-osm.c](https://android.googlesource.com/kernel/msm/+/1fb11298840bd9d4225abc93b512f7971a9c620f/drivers/clk/qcom/clk-cpu-osm.c). Its +0x88 timer interpretation is intentionally **not** applied to the newer chip.

[Qualcomm SRB patent application](https://patents.justia.com/patent/20260099460) supplies policy context, without defining these Thor addresses, bit fields or a runtime interface.

Reproduction: `python3 reproduce_evidence.py --cpucp /path/to/cpucp_b.bin --tz /path/to/tz_b.bin --xbl /path/to/xbl-embedded-0x55f94.elf --output /path/to/output`, with Capstone 5.0.6. This validates ELF machine types, reads file-backed mappings, disassembles bounded ranges and emits decoded domain objects/strings/function pointers plus stock image SHA256 hashes. Relative RV32 branches and AUIPC/JALR targets were resolved during inspection; these candidate-reference searches are not firmware execution or complete data-flow analysis. Raw images remain outside the repository.

Remaining actionable-evidence gap: exact field documentation or equivalent owner code for EPSS+0xbc bit0 and +0x88 low6, a recognized runtime control transaction, and its locking/quiescence/restoration requirements. The stock initializer is now a concrete location for further control-flow/property tracing, but not a justified device-write recipe.
