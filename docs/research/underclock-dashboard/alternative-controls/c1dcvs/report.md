# Remaining C1DCVS controls: stock Thor static result

The remaining knobs are not a promising direct fix for the demonstrated prime-core overshoot. Stock firmware actually gates the C1 algorithm with `enable_c1dcvs`; therefore the existing controlled disable experiment bypassed the algorithm that consumes these thresholds, hysteresis and optimization flag. Overshoot persisted in that phase. That strongly rules out this C1 algorithm as necessary for that particular excursion, although it does not prove every interaction is irrelevant or identify SRB's implementation.

Scope: host inspection and bounded host simulation of existing extracted stock modules and CPUCP image only. No device SET/GET request, sysfs content read, ADB action, tracing activation, module load, MMIO write or reboot was performed. All simulation state changes below occurred in host scratch memory.

## Stock ABI and actual controls

Stock `c1dcvs_vendor.ko` `.text` wrapper offsets `0x1dc..0x394` use protocol `0x87`, IDs 11–22. `.text+0x4f0` sends scalar setters as 4 bytes; `+0x624` sends thresholds as two 32-bit words `(cluster, threshold)` (8 bytes). Getter helper `+0x3bc` allocates 8-byte transmit and 128-byte receive buffers and copies returned payload to the caller. Stock `c1dcvs_scmi.ko` has the six ordinary attributes, unsigned-decimal scalar parsing and threshold pair parsing; there is no Linux-side documented enum or physical-cap API hidden behind these names.

The related pinned source agrees with this narrow wire ABI and exposed knob set; it does not define mode meanings, threshold units or SRB. Sources:
- https://raw.githubusercontent.com/LineageOS/android_kernel_ayn_qcs8550/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/arm_scmi/c1dcvs_vendor.c
- https://raw.githubusercontent.com/LineageOS/android_kernel_ayn_qcs8550/0d9a32622533fa6dbeeab60207977171c286c458/drivers/soc/qcom/dcvs/c1dcvs_scmi.c
- https://raw.githubusercontent.com/LineageOS/android_kernel_ayn_qcs8550/0d9a32622533fa6dbeeab60207977171c286c458/include/linux/scmi_c1dcvs.h

CPUCP registration at `0x17d206ec..0x17d206fc` registers handler `0x17d201a8` with protocol `0x87`. Its 12-entry jump table is `0x17d28090`:

| Knob | IDs SET/GET | Firmware branches | Actual behavior |
|---|---:|---|---|
| enable_c1dcvs | 11/12 | `17d201d2` / `17d201fc` | Normalize scalar to boolean; write/read pointed configuration +8. Disable also invokes C1 cleanup. |
| enable_trace | 13/14 | `17d20210` / `17d20238` | Normalize to boolean at `17d28160`; set logger category 9 level to 15 or 0. Getter returns boolean. |
| ipc_thresh | 15/16 | both `17d2030c` | Return status `-1` and 4 response bytes. Neither parses thresholds nor updates state. Exposed attributes do not mean these commands work. |
| efreq_thresh | 17/18 | `17d2024a` / `17d2027a` | Accept domain IDs **1..3**, subject to platform existence; store unrestricted 32-bit threshold at `17d28148 + 4*domain`. Invalid/absent domain silently returns success with no update. GET returns status plus all three slots (zero for absent). |
| hysteresis | 19/20 | `17d202ba` / `17d202cc` | Store `argument >> 3` at `17d28158`; getter returns stored value `<<3`. Thus 49 reads back 48. No further range validation. |
| c1dcvs_opt_mode | 21/22 | `17d202e0` / `17d202fa` | Store full scalar at `17d28164`, and its low bit as byte at `17d28168`; getter returns the full scalar. There is no checked two-mode enum in this handler. |

Boot initialization (`17d20692..17d20770`) puts internal hysteresis=6 (GET equivalent 48), timestamp threshold=3 at `17d2815c`, tracing=0, optimization scalar/flag=0, and domain threshold values 1,400,000 / 1,500,000 / 1,500,000 for present domains 1/2/3. These are **image initialization values**, not measured live state or established threshold units.

## Consumers and why this is not a direct frequency ceiling

The C1 algorithm begins at `17d2031c`. Per selected CPU, it calls `17d245ac(cpu,1)`, which reads the 32-bit field at `17d2a07c + 24*cpu + 0x5a0`; the associated updater `17d2451c` sums four stored samples and shifts by 2 to form an average. C1 compares that statistic against the domain EFREQ threshold at `17d2055e..17d20562`. This supports a sampled effective-frequency decision threshold, **not** a hard upper-frequency limit. The sample producer at `17d24460..17d244ea` takes per-CPU counter differences and shifts by 1; its exact units/reference period are not independently established here. Do not translate these threshold values into a safety ceiling.

The low optimization bit is consumed at `17d20438..17d20470`. With flag zero, an equality between domain 1's register-low-byte state and its saved boot value clears both rail candidates together. With flag one, that shared special-case rejection is skipped. The precise user-facing names/intent of modes 0 and 1 are unknown; values 2 and 3 merely demonstrate the handler's full-value storage and low-bit behavior. No additional mode semantics are justified by those integer values.

Hysteresis is a count used in the rail transition state machine (`17d20598..17d20628`). Candidate rail states are delayed by counters at `17d28170`/`17d28174`; state bytes are `17d2816c`/`17d2816d`. Rail 0 applies to domain 1; rail 1 applies to domains 2 and 3 together. This algorithm can coordinate rails, but the fact of rail coordination alone does not identify it as the observed SRB mechanism.

Actuation uses `17d24656(domain, boolean)`: obtain the domain object, use its EPSS base pointer at object+0x14, and read-modify-write base+`0x88`. For boolean 1, replace only low six bits with the saved six-bit table entry at `17d0c37c + 4*domain`; otherwise set low six bits to `0x3f`. The hardware semantics of that field and saved table are left to the EPSS investigation. This path does not write the ordinary software desired-Pstate register `+0x320`, nor does the threshold setter itself write any EPSS register.

## Enable gate and existing negative experiment

Initialization `d82e72d8..d82e72e8` stores `17d2a710` into pointer `17d28050`. The SET_ENABLE handler writes boolean at pointer+8 = `17d2a718`.

The periodic callback at `17d20000` checks `17d2a718` at `17d20010..17d20014`; it calls `17d2031c` only when nonzero (`17d20028..17d2002a`). A separate configuration word at `17d2a71c` gates another algorithm at `17d24c36`; it does not cause the disabled C1 algorithm to run. Disable also calls `17d2015a`: for present domains 1..3 it invokes `17d24656(domain,0)`, then clears the two saved C1 rail-enable state bytes. This makes the documented on/off/on test more informative than assuming the sysfs label has a particular meaning.

Consequently, changing C1 EFREQ/hysteresis/opt settings should not be promoted as an SRB disable or independent strict cap. They may alter behavior while C1 is active, but the reproduced overshoot with C1 disabled sharply reduces the practical value of experimenting with them. IPC experiments have no value for this stock image because both handlers are unsupported.

## Observation versus tracing

Existing sysfs getters provide configuration only; reading them invokes vendor firmware GET commands and was excluded in this investigation. The EFREQ getter is a threshold/configuration readback, not a live effective-frequency telemetry stream. Its Linux show loop stops at the first zero, so an absent first domain or zero threshold can also truncate visible later slots.

The trace flag controls category 9 logging, via helper `17d210d6` storing its level at `17d2868c`. Calls to logger `17d2130c` provide three C1 formats (image strings at `17d280c0`, `17d280f4`, `17d2811c`): domain/state/timestamp, CPU/effective-frequency statistic, and rail/enable/hysteresis state. The C1 trace flag only enters logging branches in the inspected algorithm; no evidence makes it an SRB setting. Logger output transport/device readout was not established, and enabling it would be a setting change with performance/volume consequences. No existing passive host evidence exposes those per-tick C1 statistics. Previously captured hardware status and clock/cycle measurements remain stronger observation paths for the overshoot itself.

## Reproduction and limits

`control-excerpts.txt` contains address-bounded native disassembly for the claims above; `dispatch-data.txt` records the decoded table. Candidate-reference scans were simple local constant tracking, not complete data-flow proof. Stock kernel module hashes are recorded in `manifest.json`.

`emulate_controls.py` uses Unicorn 2.1.4 to execute the unmodified RV32 compressed handler in host memory, bounds each case to 2,000 instructions, verifies the recorded stock SHA-256, guards every instruction PC to the handler/trace configurator/explicit substitute/return sentinel, and guards writes to host response/stack/control-state locations. Platform-existence callee `d82e5e82` is explicitly substituted with return 1, so simulation cannot establish which domains are present at runtime. Trace configurator executes only its native host-memory store; no log output is generated and no physical tracing is enabled. The 16 isolated scratch cases confirm IPC fixed error, EFREQ invalid-domain no-op / valid-domain storage / GET layout, hysteresis quantization, low-bit optimization behavior and trace configuration. Full step/write records and the firmware SHA-256 are in `emulated-controls.json`; the compact result is `emulated-controls.summary.json`. Run: `python emulate_controls.py --image /path/to/cpucp_b.bin --output /path/to/results.json`. Scratch initial thresholds are arbitrary test values, not claimed live or boot values. No transport, arbitrary firmware services or MMIO path was emulated.
