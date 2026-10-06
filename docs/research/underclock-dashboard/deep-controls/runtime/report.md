# SRB candidate ownership and stock-kernel access

2026-10-06. **The stock kernel's signature/protected-symbol checks are no longer an unexplained obstacle to a helper. The candidate register's runtime semantics remain unproved.** This pass combined deeper host analysis with read-only observations and kernel extraction from the authorized Thor. No device setting, register, firmware vote, module, boot configuration or ClusterTune behavior was changed.

## Stock loader restrictions resolved

The active `_b` boot header is Android boot format 4. Its 56,048,128-byte ARM64 kernel payload was read to the host without rebooting or writing the partition. SHA-256: `b53c6063332f1d7057416cb708e443d008634752ec578c56d68040b2af9bcdf3`. Its banner matches the running `5.15.123-android13-8-gafd857749d1f` kernel and identifies Android Clang 14.0.7/r450784e with LLD 14.0.7.

Recovering the kernel's symbol table permits checking the actual implementation, rather than inferring loading policy solely from `CONFIG_MODULE_SIG_PROTECT=y`:

- `is_module_sig_enforced` at `ffffffc008239fb8` returns constant zero.
- `gki_is_module_protected_symbol` at `ffffffc008242c8c` calls `bsearch` with **zero entries**. The protected-import check embedded in `simplify_symbols` also passes zero entries.
- `gki_is_module_exported_symbol` at `ffffffc008242ce8` likewise calls `bsearch` with zero entries.
- `bsearch` at `ffffffc0087733f4` exits immediately on a zero count, returning null before reading the table or invoking its comparison callback. The two compiled protected arrays alias the same empty object.

Thus these compiled stock checks do not reject an otherwise compatible module for an untrusted signature or protected symbol name. This does not bypass ordinary symbol resolution, GPL-only export rules, version checks, CFI, structural validation or other loader errors. It is specific to this kernel image, not a universal rule for rooted devices.

The stock CPU debug module is already loaded with taint `E` (no trusted module signature). `/proc/sys/kernel/modules_disabled` is `0`; the root shell has `CAP_SYS_MODULE`, no seccomp filter, and the observed SELinux state is permissive. These were read, never changed. Kernel taint stayed `12804` across the observations. An absent `sig_enforce` sysfs parameter was **not** used as evidence that enforcement is disabled; the native kernel implementation supplies that evidence.

All **23** version CRCs in the extracted stock debug module match the independently recovered kernel export/CRC tables, including `module_layout`, `devm_ioremap`, the sysfs helpers and MMIO read logging. Export names are independently checked through the tables' signed relative name offsets. This corroborates the recovered kernel layout and establishes a real accepted baseline for a small reader. It does not establish a new helper's compatibility. CRCs must be used to compare genuine builds, not patched into incompatible binaries to force loading.

The pinned public LineageOS AYN source has kernel version **5.15.211**, whereas stock is **5.15.123**. The stock revision was not resolved by the bounded public commit lookups. A newer source tree is not automatically incompatible when a stable KMI is preserved; it is also not an established matching build environment. A candidate helper must be genuinely built with compatible headers/configuration/toolchain and its own generated CRCs checked against the stock exports. A full matching `Module.symvers`/build environment has not been established, and no helper was built or loaded in this pass.

Evidence: [native kernel checks and CRC comparison](kernel-access-proof.json), [instruction excerpts](kernel-access-excerpts.txt), [kernel hash/extraction method](stock-kernel-manifest.json), [read-only observations](readonly-observations.json).

## Domain ownership narrowed

The [new pointer scan](trace_domain_ownership.py) seeds a valid domain object after each of 24 identified direct calls to CPUCP's domain getters. It follows local control flow, tracks object and EPSS pointers, preserves ordinary callee-saved facts and merges conflicting facts to unknown. It explores 2,488 instruction locations across those runs, with 5,083 worklist steps; no run exhausts its 10,000-step budget.

The traced object `+0xc` stores are only `d82e0272`, `d82e02fc` and `d82e0324`, each writing `1` after domain initialization. No object-flag clear is found through these tracked paths. The only identified EPSS `+0xbc` accesses remain the four startup read-modify-write pairs already located. The adjacent helper at `d82e0188` disables EPSS offset **0**, rather than clearing this flag or SRB-labelled field; it is not a justified isolated SRB control.

This is a bounded pointer inventory. Callees are not entered, unsupported operations discard facts, and indirect pointer construction, other processors, generic memory helpers and external firmware inputs remain outside the result. Zero unresolved control-flow exits does not make it a whole-program ownership proof. In particular, there is no established guarantee about resume, controller reset or concurrent firmware access.

The [native host simulation](emulate_guard_and_field.py) establishes two narrower properties:

1. For each of four domains, with synthetic getter state initialized and the object's `+0xc` already `1`, the actual initializer returns `0` using only stack writes. No EPSS pages are mapped, and no MMIO access is attempted in these cases.
2. Twelve isolated startup-fragment cases (four domains, three input words each) confirm the exact `+0xbc` writes: clear bit 0 for L3/Silver, set it for Gold/Gold_Plus, preserve every other bit. This runs only those instructions against host scratch memory, not full domain initialization or real hardware.

Results: [pointer inventory](domain-ownership.json), [guard and field-update simulation](guard-and-field.json).

## Meaning and live observations

The Thor's existing read-only debug frontend reports SRB-labelled values **0 / 1 / 1** for Linux domains efficiency / performance / prime. They agree with the stock initialization. The requested and hardware-status words changed naturally between two snapshots while those SRB-labelled words stayed unchanged. A persistent `1` therefore must not be presented as an instantaneous boost indicator.

The current related driver defines the offset and name, but not its bit fields or a write operation. Scoped public-source searches found only the debug reader in OnePlus's SM8550 CPUFreq directory, no SRB definitions in that repository's Qualcomm clock/SoC directories, and no `EPSS_SRB` boot-image match. These are bounded search results, not proof that documentation or an implementation exists nowhere. Historical OSM per-core-DCVS definitions remain a different generation and do not resolve this Thor field.

**Bit 0 could still be the required control, but its exact effect remains unknown.** Neither the native startup RMW nor its name establishes that clearing it after initialization disables only SRB, that HLOS writes are accepted, or that no domain quiescence is required. The stock debug mapping establishes read access. It does not establish write permission.

## Concrete route to a test

The next kernel experiment should begin with a small **read-only helper**, using the same device-tree resources and mapping/read functions as the existing debug driver, restricted to the three CPU domains and the exact `+0xbc` field. It should provide no arbitrary-address accessor or setting endpoint. Its legitimate import CRCs and Clang/CFI compatibility must be checked before loading. Agreement with the existing reader, successful unload and unchanged device state would establish the access/build baseline; none of these new-helper tests has occurred yet.

A writable experiment still needs an established field meaning and valid runtime sequencing. A Linux-side mutex cannot synchronize an unknown CPUCP writer. Restoring an entire saved register word could overwrite unrelated firmware updates; even restoring only bit 0 requires knowing who can change that bit. The fresh-domain initializer is not an appropriate runtime transaction: it changes dozens of unrelated EPSS fields and clock-controller state.

After those prerequisites, the actual test remains: unchanged requested cluster caps, reproduce overshoot with the bit in its original state, change only the justified field, measure hardware status and independent cycles, restore the original field and demonstrate the original behavior returns. Readback alone cannot prove enforcement. No coordinated-cap workaround was added.

## Reproduction and sources

The three included scripts take explicit local input paths, validate image hashes and write only host outputs. `trace_domain_ownership.py` requires Capstone 5.0.6; `emulate_guard_and_field.py` requires Unicorn 2.1.4. `inspect_kernel_access.py` requires Capstone 5.0.6 and a symbol listing generated by `kallsyms-finder` from [vmlinux-to-elf 1.3.6](https://github.com/marin-m/vmlinux-to-elf). The included selected-symbol listing is also sufficient for reproducing the bounded kernel checks; CRC/name checks against the raw image provide independent layout validation.

Example host invocation: `python3 inspect_kernel_access.py /path/to/stock-kernel-image.bin kernel-selected-symbols.txt /path/to/qcom-cpufreq-hw-debug.ko /path/to/output`. Full kernel/firmware images, complete symbol listings, downloaded sources and tooling remain outside the repository.

Primary sources: [Android module signing/versioning documentation](https://source.android.com/docs/core/architecture/kernel/loadable-kernel-modules), pinned AYN [module loader](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/kernel/module.c), [GKI symbol checks](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/kernel/gki_module.c), and [CPU debug reader](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/cpufreq/qcom-cpufreq-hw-debug.c). Related source explains organization; the native stock checks establish the stock result. [Source hashes and scoped lookups](source-manifest.json) preserve provenance.
