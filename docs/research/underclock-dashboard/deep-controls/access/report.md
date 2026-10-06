# Access and independent hardware-limit routes

The newly located EPSS initialization stores do not provide a userspace control. This host-only pass checked the stock secure IO service, cached kernel configuration and existing CPUFreq/LMH driver paths. No firmware command or hardware read/write was issued.

## Generic SCM IO is an exact-address allowlist

Stock `qcom-scm.ko` exports `qcom_scm_io_readl` at `.text+0x50a0` and `qcom_scm_io_writel` at `+0x1acc`. Their native wrappers encode service `0x05`, commands `0x01/0x02`, address as the first argument and value as the second write argument. The [pinned related helpers](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/qcom_scm.c#L865) agree with that ABI.

Bounded host simulation of stock TZ's command lookup selects read handler `1571cc30` and write handler `1571cab8` for normalized IDs `02000501/02000502`. These are functional handlers, unlike the older FCAP stub; neither was executed on the device.

Both handlers call `156781b0`, which returns table `157aa918`, and `156781c4`, which returns count 53. Each 16-byte row begins with an exact 32-bit physical address. The handlers first reject nonzero address high bits, then search those exact addresses. A missing address returns `-1` before accessing a register or evaluating the matched row's access policy.

The three CPU EPSS domains' `+0x88`, `+0xbc` and first LUT word `+0x100` are all absent. Eighteen host cases (read/write for nine addresses) execute the native failed-lookup paths and return `-1`. Only code/table and scratch stack/response memory are mapped; PC and writes are guarded and each case is limited to 1,000 instructions. Unicorn does not model this platform's pointer authentication, so only exact-matched PACIA/RETAA prologue/return instructions are substituted; the native lookup, comparison and rejection logic executes unchanged. This is not a simulation of accepted accesses or a real secure command.

`inspect_io_access.py LOCAL_TZ_IMAGE OUTPUT_DIRECTORY`, with Unicorn 2.1.4 and Capstone 5.0.6, regenerates `io-allowlist.json` and `io-handler-excerpts.txt`, after checking the stock SHA-256. The JSON preserves the 53 rows, every tested result and two representative full instruction traces. `trace_io_dispatch.py` separately reproduces command lookup without invoking the selected handlers.

**Conclusion:** the inspected SCM IO route cannot access these exact candidates merely by escalating the caller. This does not establish whether a direct HLOS kernel mapping can write them, or exclude another firmware API. Root cannot itself issue a kernel-exported SCM call from the app either.

## Stock kernel constraints

[Selected cached configuration](kernel-access-config.txt) confirms `CONFIG_DEVMEM` is disabled. Root therefore does not gain `/dev/mem` access by changing file permissions. The stock kernel also has module versioning, strict CFI and module signature protection. `CONFIG_MODULE_SIG_FORCE` is unset, so signature protection must not be overstated as an unconditional prohibition on every unsigned module; compatibility and the rules for the needed symbols still require establishing. Nothing was built for loading or loaded.

The existing CPUFreq debug module supplies a read-only dump including `EPSS_DEBUG_SRB`. It does not provide a store operation. That permits observing the candidate's current value, not changing it or establishing the effect of clearing it.

**Subsequent stock-kernel inspection:** the [next pass](../runtime/report.md) resolved these particular signature checks in the actual kernel Image: `is_module_sig_enforced` returns zero, and both GKI protected-symbol searches pass zero entries. All 23 CRCs from the accepted stock debug module match the kernel exports. Those checks therefore do not preclude a compatible unsigned helper on this build. Genuine build/CFI compatibility, successful loading and direct register-write permissions remain untested.

## Software tables, hardware LUT and thermal limits

Stock CPUFreq target/fast-switch paths write the requested performance index; readback resolves that same request through the software frequency table. The stock initialization reads frequency and voltage LUTs, then builds Linux OPP/table entries. Filtering those Linux entries would not, by itself, remove higher states from the hardware's autonomous selection logic. This is the relevant distinction for an independent physical ceiling.

The stock standalone LMH module reads mitigation state, updates thermal pressure and acknowledges interrupts. The previously inspected cooling module uses frequency QoS. Neither establishes a separate user-owned hardware cap. A read-only attribute named `dcvsh_freq_limit` is feedback from mitigation logic, rather than a setter.

A [primary kernel patch discussion](https://lkml.iu.edu/2101.2/04551.html) implements actual OSM LUT programming on older platforms where firmware has not already initialized/protected that hardware. It illustrates the additional hardware/voltage/sequencer work a kernel implementation can require; it is not a Thor implementation or proof of writable Thor LUTs. The [older OSM driver](https://android.googlesource.com/kernel/msm/+/1fb11298840bd9d4225abc93b512f7971a9c620f/drivers/clk/qcom/clk-cpu-osm.c) also has boost FSM controls, but its `+0xbc` is a different named field and its offsets cannot be transplanted to current EPSS.

The [Arm X3 manual](https://documentation-service.arm.com/static/62bb289431ea212bb6625392), section 5.5, describes MPMM/PDP as instruction/activity power controls. Those may affect power and throughput, but they do not supply the requested absolute CPU-clock ceiling. They were not enabled or changed.

Hashes for the cached configuration and inspected stock modules are in `input-manifest.json`; related older-source provenance is in `source-manifest.json`. Raw firmware/modules and full downloaded source remain outside the repository. No independent physical ceiling is claimed from any of these paths.
