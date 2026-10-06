# FCAP ownership and physical-ceiling investigation

Scope: the available AYN Thor, firmware `Thor_V1.0.0.377_20260206_165408_user`,
active slot `_b`. The user authorized planning and proceeding with the staged
investigation. Existing frequency settings, thermal protections and device
stability remain constraints. This is not authorization to flash or patch
secure firmware, bypass kernel protections, or issue undocumented cap writes.

## Stages and evidence required

| Stage | Work and exit condition | Current result |
| --- | --- | --- |
| 1. Identify the implementation | Trace the stock secure-call lookup to the DCVSH handler, confirm the request ABI, and inspect its request handling. Calibrate the lookup with unrelated known commands. | Completed: command `0x02001310` resolves to a constant-error stub. This route fails the prerequisite for subsequent cap tests. |
| 2. Establish ownership and release | For a working implementation, trace storage, caller identity, arbitration and the disable branch. Determine whether release removes one request or a shared value. Establish crash/restart behavior and observability of other actors' requests. | Not applicable to the identified stub: it neither creates nor releases a cap. No working alternate implementation has been identified. |
| 3. Prepare observation and recovery | A bridge, only if needed, must match the stock kernel, symbol versions, CFI and module protection. Initially expose only supported non-setting queries. Establish a release mechanism from the verified implementation and account for concurrent request changes. | No module built or loaded. A bridge cannot supply the missing firmware implementation. A generic availability response alone would be misleading here. |
| 4. Controlled physical-cap test | Only after stages 1–3 pass: use a supported conservative cap, retain existing thermal protections, measure independent clock counters/status under bounded load, verify another known request remains active, and release only the test request. Never create a second owner by impersonating a VM or secure client. | Not run: prerequisite fails at stage 1. |
| 5. Repeat and restore | If supported: test other-owner request changes while our request is active; verify that its latest request survives our release. Test caller interruption only after a verified cleanup strategy exists. Compare the full final configuration and actual clock behavior with baseline. | No cap existed to restore. No device setting was changed. |

## Stage 1 execution and result

The earlier literal search missed a generated decision tree. Stock TrustZone
has a command lookup at virtual address `0x156c4934`. It recognizes the
`0x02001301..0x02001312` range using arithmetic and a halfword jump table at
`0x1577cb24`, rather than a table of complete SMC IDs. The generic dispatcher
uses this lookup to construct command metadata and select a handler.

For `0x02001310`, lookup index 15 has jump offset 1450. The branch target is
`0x156c49b8 + 1450 * 4 = 0x156c6060`. That branch selects argument descriptor
`0x15` and handler `0x156edf1c`. Its entire executable behavior is:

```text
156edf1c bti c
156edf20 mov w0, #-4
156edf24 ret
```

There is no request read, state update, child-firmware request, owner selection,
cap setting or release branch in this handler. The argument descriptor matches
the [Qualcomm DCVSH declaration](https://github.com/edk2-porting/edk2-msm/blob/e1952621f419f8db60ed28271264e1b5184c571d/Silicon/Qualcomm/QcomPkg/Include/Protocol/scm_sip_interface.h).
The [related Qualcomm SCM error definitions](https://github.com/LineageOS/android_kernel_ayn_qcs8550/blob/0d9a32622533fa6dbeeab60207977171c286c458/drivers/firmware/qcom_scm.h)
identify SCM `-4` as not supported. This is a different protocol/status namespace
from SCMI's `-3` permission-denied finding in CPUCP.

The actual generic invocation path copies the selected function pointer into
the request at `0x15694774..0x15694780`, invokes it through the argument-count
dispatch at `0x15696868`, and uses the register trampoline at `0x15652d08`.
These static excerpts support interpreting the lookup output as the selected
function rather than an unrelated metadata pointer.

The computer-side simulation ran the **unmodified** recorded lookup bytes,
with only its code, read-only table and a scratch response mapped. Instruction
execution was bounded to that routine; writes were restricted to the scratch
response; SMC instructions and execution outside the routine were rejected.
The identified stubs were separately simulated only after matching their exact
bytes, with an unmapped request argument so any attempted request read would
fail. All four tested constant-return stubs returned `-4` with no writes.
No real firmware command was sent and no full-system emulator was used.

| Command | Selected handler | Argument descriptor | Result inspected |
| --- | --- | --- | --- |
| INFO availability `0x02000601` | `0x15696094` | `0x01` | Real lookup-based implementation; used as calibration, not simulated as a complete query. |
| Peripheral authentication `0x02000205` | `0x156769bc` | `0x01` | Lookup calibration only; handler not executed. |
| I/O read `0x02000501` | `0x1571cc30` | `0x01` | Lookup calibration only; handler not executed. |
| LMH debug set `0x02001308` | `0x156edf2c` | `0x15` | Constant `-4` stub. |
| LMH debug types `0x0200130b` | `0x156edf5c` | `0x24` | Constant `-4` stub; not useful for enumeration on this image. |
| LMH/DCVSH config `0x02001310` | `0x156edf1c` | `0x15` | Constant `-4` stub; the older FCAP transport has no working setter here. |
| LMH sensor init `0x02001311` | `0x156ee000` | `0x06` | Constant `-4` stub. |
| LMH command `0x02001312` | `0x156ee010` | `0x22` | Nontrivial handler; not simulated. Its existence does not make command `0x10` functional. |

The INFO implementation checks whether lookup recognizes a command; it does
not call the selected handler. Thus recognition of `0x02001310` is compatible
with a setter that always rejects the operation. A positive generic availability
result would not establish FCAP support. This pass did not issue that query on
the device or establish how the hypervisor would route it at runtime.

## Scope of the proof

This proves the recorded stock TZ image's selected DCVSH command handler is a
stub, and explains why cap ownership/restoration cannot be demonstrated through
that implementation. It does not prove the absence of every hardware limit,
vendor SCMI command, alternate firmware service or runtime modification. Actual
live firmware execution was not inspected. Other firmware builds require their
own extraction and dispatch analysis; these addresses and results must not be
applied to them by assumption.

The remaining cap/release experiments in this plan are conditional; the fixed
stub is a concrete reason to stop this route before loading modules or writing
limits. Further investigation of a different control would first need to identify
a real supported implementation and repeat the ownership/recovery stages.

## Evidence and reproduction

- [Recorded stock artifact hash](stock-artifact-manifest.json): `tz_b.bin`,
  SHA-256 `917e26b15114884cd54bc47fdb40ae43b63ee6281ce74f217d3990dcdfa1d206`.
- [Bounded host simulator](emulate_lmh_dispatch.py): requires Unicorn 2.1.4 and
  Capstone 5.0.6, takes a local TZ image and output directory, verifies the hash,
  and accesses no device. It performs command lookup and exact-matched stub
  execution only; other returned handlers are never executed.
- [Simulation results and execution addresses](fcap-proof-dispatch.json).
- [Bounded disassembly and raw LMH jump table](fcap-proof-tz-excerpts.txt).
- [Final read-only device snapshot](fcap-proof-final-device-state.txt), compared
  with the preceding ownership snapshot. No clocks, modes, permissions, governors,
  GPU ceiling, boost/C1DCVS, performance mode, firmware or APK were changed.
