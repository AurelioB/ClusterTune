# CPU permission repair and session timeouts — October 15, 2026

The reported toast, `CPU minimum/profile update failed: CPU minimum protection changed
externally…`, comes from minimum permission ownership. The screenshot also shows actual CPU
ceilings at Stock. Without logs from that device, it does not identify the writer or establish
the cause of every reported reset.

## Repair during an intentional apply

A root-owned host records the minimum node's original permissions before protecting it. The
old implementation rejected a later apply if firmware returned those permissions to their
original writable mode. The next intentional apply can now reacquire an already-owned node
when its mode matches that original mode or the temporary owner-write mode. Its journal retains
the original permissions for release. Unfamiliar permission changes are preserved; applying
Stock releases ownership. Effective minimums raised by independent QoS boosts are not treated
as permission loss.

There is no periodic fixed-profile recovery. The trial `HostProfileRecovery` implementation,
its CPU-file polling, and its dedicated fixtures were removed. Fixed profiles are not silently
reapplied after external clock changes.

## Auto Tune timing

- One app-side loop sends a heartbeat, reads FPS/load telemetry, decides, and applies any clock
  changes. The default interval is one second after the work completes. The backend requires
  Auto Tune to be enabled at startup, heartbeat, and apply; the loop belongs to an active runtime
  generation and exits on stop, disable, cancellation, or supersession.
- Independently, the privileged host schedules one task for the active session's heartbeat
  deadline. That task does not duplicate FPS sampling. It can restore owned limits if the app's
  loop stalls. Renewed sessions can keep an earlier task, which rechecks once and schedules the
  renewed deadline. Successful stop/expiry cancels pending work and releases its timer thread.
- Host construction, capability/status queries, and fixed-profile applies schedule no timer.
  A visible read-only HUD has its own session expiry, which also has no permanent polling loop.
- Failed Auto Tune restoration or lease cleanup can schedule another cleanup attempt after
  one second. These attempts finish previously owned work; they do not sample FPS or continue
  tuning. A failed cleanup retains the authoritative checkpoint instead of abandoning changes.
  Unexpected cleanup exceptions back off instead of repeatedly dispatching an expired deadline.
- Timer generations prevent a cancelled callback from acting on replacement sessions. The
  host monitor serializes scheduling, session mutations, expiry, and lease-loss cleanup.

No wake lock, wake-up alarm, or app-detection poll is added. Normal foreground UI refreshes and
an explicitly opened HUD remain separate from Auto Tune's actuator loop.

## Validation

Unit checks cover idle/cancelled/closed timers, one-shot execution and rearming, heartbeat
renewal, stop/expiry releasing deadlines, and unfinished restoration retaining a retry deadline.
The minimum permission tests cover reacquisition, retained original journal modes, and
preservation of unfamiliar permissions.

The opt-in Thor fixtures inspect the privileged host's actual thread names with PServer and
app-facing root. They verify no timer thread after startup, capability/idle queries, or a fixed
underclock. A controlled permission/Stock reset remains in place until an intentional apply,
which succeeds and reacquires protection. The existing stalled-Auto-Tune fixture still has to
restore real limits without another Auto Tune request. Fixtures restore initial settings.

Per-test outcomes are retained in [validation.json](research/profile-recovery/validation.json).

| Check | Result |
| --- | --- |
| JVM suite | 558 passed, 0 failures |
| Debug app/instrumentation builds and lint | Passed; 0 lint errors, 56 warnings |
| Thor: PServer/root idle timers, explicit repair, Auto Tune timeout/handoff, feature settings | 9 passed, 0 failures |
| Thor: final interactive lifecycle recheck | 4 passed, 0 failures |

The initial combined device run also included two failed interactive checks because the device
started asleep. The test fixture now wakes/unlocks before each interactive check, and all four
lifecycle checks passed on re-run. The idle-timer and Auto Tune timeout checks passed in the
initial run. No permanent profile-recovery loop remains in production.
