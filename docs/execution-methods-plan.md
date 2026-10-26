# Privileged execution architecture

ClusterTune performs privileged work through one long-lived Binder host. The app-side resolver
selects how that host is started, while all sysfs discovery, mutation, permission changes,
verification, and rollback stay inside the host process.

## Method order

1. **PServer** (`pserver-stdout`, historical id)
   - Probe only checks that `PServerBinder` exists and is alive.
   - Starts the host with one output-disabled Binder transaction.
   - Does not execute per-action commands or provide stdout to the app.

2. **Root shell** (`root-shell`)
   - Probes `su` and starts the same host through a bounded shell process.

The resolver keeps the selected method stable for a host launch and rejects launches when the
selection changes concurrently. Existing persisted `pserver-stdout` settings remain valid even
though stdout is no longer part of the PServer capability.

## Host responsibilities

The host owns capability discovery and an atomic CPU/GPU snapshot. Every apply and minimum repair
is a single transactional Binder request with preflight validation, journaling, rollback, and
post-write readback. A transport loss after mutation is reported as indeterminate and never
falls back to another writer.

The app may use ordinary Android file access for best-effort display values when a host snapshot
is unavailable. That path never performs privileged writes or repairs. Explicit apply and repair
operations fail clearly until a host is available.

## Safety rules

- PServer is lifecycle transport only.
- No privileged per-command stdout or file-output bridge exists.
- Stock writes try the discovered stock ceiling, verify readback, then use the selectable fallback.
- CPU and GPU changes are verified together, with reverse-order rollback on failure.
- Host identity and topology are checked before and after each transaction.

## PServer protocol and implementation provenance

The MIT transition removes the historical `RootExec.kt` implementation adapted from
O2P Tweaks/jdsp4rp5. `PServerHostLauncher` implements the launch-only behavior below;
the old command-output decoder, overloads, and cached Binder lifecycle are absent.
It resolves a current connection through Android's nonblocking `checkService` on
each probe or launch. A probe checks liveness without executing a command. A launch
is sent once; failure never triggers an automatic retry of a possibly executed command.

The protocol was independently inspected in the AYN Thor's `/system/bin/pservice`,
with firmware fingerprint
`qti/kalama/kalama:13/TKQ1.231222.001/eng.Thor.20260206.163241:user/release-keys`.
The executable's SHA-256 is
`8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4`.
Its embedded debug symbols identify `android::BinderServiceP::onTransact` at Thumb
address `0x350d`. The handler calls `Parcel::readInt32` at `0x3560`, validates a
count of 1–8, and loops over `Parcel::readString16` at `0x3586`. It reads no Binder
interface token before the count. This is protocol evidence, not vendor source
code incorporated into ClusterTune; the firmware executable is not redistributed.

ClusterTune's launch request uses the following wire format:

| Field | Value |
| --- | --- |
| Service name | `PServerBinder` |
| Binder transaction code / flags | `0` / `0` (synchronous) |
| First Parcel field | int32 string count, `2` |
| Second field | UTF-16 host-launch shell command |
| Third field | UTF-16 `0`, disabling command-output capture |
| Interface token | None |

The command is nonblank, contains no NUL, and fits in 255 UTF-8 bytes. Keep the
short shell envelope and detachment script described above. Transaction acceptance
does not establish host readiness or command success: the production client still
waits for its nonce-protected Binder handoff and verifies host identity/version.

Unit tests exercise discovery, service replacement, input limits, rejection and
uncertain transport failures. Android instrumentation tests inspect the actual
Parcel layout, and the existing PServer host-handoff test exercises the real
firmware and production client. The firmware behavior is verified for the tested
Thor; another vendor implementation may expose different capabilities.

### MIT transition review

The review started from ClusterTune `dd019bc02442bc95c465525b6f9f8a0d5d6d5196`.
The old launcher was traced to jdsp4rp5 `11e482fe579b191805128ed136f3c95174487886`
through O2P Tweaks. The replacement was written against the firmware observations
above and the launch-only interface. The old code was available during the audit;
this is not a claim of a formal clean-room development process.

Comparison of the remaining application source with O2P Tweaks's main-source files
at `8bdeef39df0a7790255eaf960d273db6b81d3f04` and GameNative's power-control package
at `375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9` found no further substantive matching
source blocks. Short Android lifecycle and Compose layout boilerplate was shared.
The existing [Auto Tune audit](autotune-audit-2026-09-07.md) and the initial branch's
[frame-measurement notes](https://github.com/AurelioB/ClusterTune/blob/dd019bc02442bc95c465525b6f9f8a0d5d6d5196/docs/autotune-frame-measurement.md) record independently
implemented behavior inspired by other projects. These comparisons support the
source review; matching-text checks alone do not determine copyright ownership.

Git history contains one external contribution, `06ad2b3`, adding the three
functional QCS8550 Small Underclock frequency values to the existing JSON schema
and reformatting that file. It adds no executable code. Those settings and the
contributor's credit are retained; no assignment or relicensing consent is claimed.
Historical revisions retain their original license. Current dependency and bundled
font notices are preserved separately from ClusterTune's MIT grant.

Initial branch validation: all 610 JVM tests passed, Android lint passed, and
debug, release, and instrumentation APK builds succeeded. On the Thor, the three
Parcel/probe instrumentation tests and the production PServer host-handoff test
passed (four tests, no skips). The handoff test exercised live telemetry, a
controller-driven ceiling reduction, and restoration. Both app APKs were inspected
for the exact MIT license, Apache 2.0 text, and third-party notices under
`assets/licenses/`.

Integration onto `main` was limited to the two licensing commits, preserving the
1.4.0 release changes and leaving the initial branch's frame-measurement work
separate. All 579 JVM tests, Android lint, debug/release/instrumentation builds,
and the same four Thor device tests passed on that integration. Both app APKs
contained the license bundle and no historical `RootExec` class.
