# Read-only helper test through AYN PServer

2026-10-06. **Three load/read/unload cycles passed without rebooting.** A temporary, unsigned helper loaded through AYN's existing PServer, read the SRB-labelled register, and unloaded successfully each time. No CPU frequency control, governor, SELinux setting, firmware vote or hardware register was written. This establishes runtime read access; it does not establish a boost-control fix.

## Result

| Check | Observed result |
| --- | --- |
| Loader execution | Root, `u:r:pservice:s0`, through `PServerBinder`; loading did not invoke Magisk `su` |
| Three domain reads | `0 / 1 / 1` in every cycle; agrees with the existing stock debug reader |
| Requested CPU minimums | Unchanged: 307,200 / 499,200 / 595,200 kHz |
| Requested CPU maximums | Unchanged: 1,459,200 / 1,785,600 / 1,843,200 kHz |
| Governors | `walt`, unchanged for all policies |
| Reboot | None; boot ID unchanged |
| Module unload | Passed three times; helper absent afterward |
| Kernel diagnostic state | Taint remained 12804; no CFI failure, Oops, BUG, version disagreement or unknown-symbol message in the inspected trailing log |
| Cleanup | Temporary device directory removed after collecting evidence |

The kernel is `5.15.123-android13-8-gafd857749d1f`, on firmware `Thor_V1.0.0.377_20260206_165408_user`. Root was used for collecting protected diagnostics and cleanup, but the actual module-load and unload commands ran through PServer. SELinux was already globally permissive and stayed that way throughout. No root module or startup entry was installed.

[Raw runtime output](reader-test.txt), [parsed before/after checks](runtime-result.json), [permission probe](pserver-access.txt), and [binary/import verification](reader-binary-verification.json) retain the evidence. These are brief access/cleanup tests, not sustained reliability, power or overshoot measurements.

## Restricted reader

[ct_srb_reader.c](ct_srb_reader.c) finds the `qcom,cpufreq-epss` device-tree node and validates all three CPU resources against bases `0x17d91000`, `0x17d92000`, `0x17d93000`, size `0x1000`, memory flags, and absence of a fourth resource. Only then does it map/read the four-byte field at each base plus `0xbc`, with `readl_relaxed`, and immediately unmap it. It retains no mapping after initialization.

There are no parameters, arbitrary-address accessor, register writes, sysfs endpoint, notifier or recurring callback. Output is three kernel-log records. Exit only logs unload. The [executed shell script](run_reader_test.sh) verifies the exact binary hash, rejects an already-loaded helper and arranges cleanup on exit. No force-load, version-ignore or force-unload option was used.

## Genuine compatibility derivation

The running kernel exposes its build headers at `/sys/kernel/kheaders.tar.xz`. These were copied read-only to the host, including the exact generated configuration and assembly offsets. Clang **14.0.7/r450784e**, with the same compiler revision as the running kernel, came from Google's official prebuilts. Pinned public AYN source supplies Kbuild and host build tools; its newer kernel was not built, installed or substituted for the device kernel.

After preparing the tools, compilation used the extracted stock headers and configuration. `CONFIG_FUNCTION_ALIGNMENT=0` suppresses a newer Kbuild option absent from the stock configuration; it does not alter the stock generated headers or disable any runtime check. A temporary local whitelist path was used only while preparing build tools; the exact stock configuration and `autoksyms.h` were restored before compiling the reader.

The [ABI derivation units](abi/) generate type/prototype CRCs from those genuine headers using `genksyms`. They are never loaded. Kernel exports depend on the defining translation unit's complete type context: the `module_layout`, OF base and OF address units reproduce their respective include contexts; the stack-check definition retains its actual attributes. An initial reduced include context produced mismatches and was rejected. Resolving that context produced matching CRCs without substituting values from the stock kernel.

[derive_abi.py](derive_abi.py) compares these independently generated CRCs with the raw stock kernel's export/name/CRC tables, and refuses any mismatch. Only generated values are written into the build's small `Module.symvers`. The resulting ELF's **12 imports**, including `module_layout`, all match the kernel. Its module structure is **960 bytes**, matching the existing stock debug module. The init/exit CFI type identifiers also agree with those in the stock debug module. Normal ThinLTO, strict CFI, shadow call stack, BTI/PAC and module-version checks remain enabled. No post-link CRC or vermagic edit occurred.

The helper SHA-256 is `be20860cfbb1b3a82b5653823068625062d4701de109b730232caf6a4e7df55f`. [Input hashes and origins](input-manifest.json) preserve provenance. Large archives, original headers/policy, toolchain and compiled module remain outside the repository.

To repeat the host build, prepare the pinned Kbuild tools with Clang r450784e, overlay the stock headers into source/output include directories, restore the stock `.config` and derived `include/config/auto.conf`, and compile the ABI units. Run `python3 derive_abi.py /path/to/scratch-root` with the cached kernel Image/symbol listing and generated ABI objects in that root. Then build the reader using `make -C SOURCE O=OUTPUT ARCH=arm64 LLVM=1 LLVM_IAS=1 CONFIG_FUNCTION_ALIGNMENT=0 M=READER modules`. The device script deliberately accepts only the recorded binary hash; a new build needs independent verification before updating that hash.

## Factory-device limitation

The read-only runtime test proves that **PServer can load this compatible helper on the current permissive Thor**. It does not prove factory-device eligibility under enforcing SELinux.

Offline queries of the OEM precompiled policy at `/odm/etc/selinux/precompiled_sepolicy` found **zero permissive domains**, and no allow rule giving `pservice` `sys_module`, `module_load`, or `setenforce`. [Exact policy queries](stock-policy-queries.json) are recorded against the policy hash in the manifest. Thus that policy would block this direct PServer loading path if SELinux were enforcing. Its presence does not establish the factory device's global enforcement state at runtime. We did not change enforcement, inject policy, or attempt a domain transition to get around it.

A stock Thor's actual enforcement state still needs establishing before claiming that this path works without root modifications. The current result does show that neither flashing nor a replacement kernel is intrinsically required for live read access.

## Remaining proof

No SRB bit was changed and no overshoot experiment was run. We still need the field's exact meaning, runtime write permission, sequencing, concurrent firmware ownership and reliable restoration before testing a write. Successful loading and matching readback do not establish any of those properties.
