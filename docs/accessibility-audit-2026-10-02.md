# Accessibility service audit — October 2, 2026

Audited the service callbacks, window enumeration, screen/display listeners, profile coordinator, settings observer, and connection reporting. Existing workspace changes were preserved. The result addresses concrete failure paths; it does not establish the cause of every OEM accessibility disconnection.

## Findings and fixes

| Priority | Failure path | Resulting change |
| --- | --- | --- |
| P1 | Window/event reads and connection initialization ran directly on the main thread without an exception boundary. A runtime exception from a disappearing or inaccessible window, settings read, or registration could escape the callback and terminate the process. | Initialization and sampling now have separate exception boundaries and three bounded delayed retries (250 / 500 / 1,000 ms). Failed samples clear stale foreground ownership, pausing automation until a fresh sample succeeds. Fatal VM errors are not treated as recoverable window errors. |
| P1 | The coordinator's `SupervisorJob` did not handle uncaught child failures. Its existing catch was inside `collect`, so an upstream storage-flow exception bypassed it. Other launched tasks could also escape to the process exception handler. The AppContainer settings observer had the same missing boundary. | Bounded retries for transient IO reads; coordinator retries pause Auto Tune before resubscribing. Coroutine exception handlers prevent ordinary unhandled worker exceptions from reaching the process crash handler. A terminal coordinator failure stops that coordinator and marks automation unhealthy rather than claiming a working connection. |
| P2 | `onUnbind` suspended work but left screen/display listeners registered. A subsequent screen-on callback could clear the suspended flag and publish a fresh snapshot while disconnected. | Unbind now cancels scheduled work, unregisters both listeners, and stops the coordinator. Late events/wake callbacks are ignored unless bound and initialized. Reconnect re-registers listeners and restarts sampling. |
| P2 | Every new window event cancelled and postponed the next 50 ms sample. A continuous stream of events could indefinitely delay sampling. Settled samples could also be postponed by window-state event bursts. | Regular sampling is coalesced without moving its existing deadline, so events cannot starve it. |
| P2 | Rebinding could create additional AppContainer settings observers, and service destruction did not cancel its observer. Android 12 window/node objects were read without returning them to their pools. | Reuse the service's container across reconnects and close its observer on destruction. Release owned window/root objects in `finally` on Android versions that pool them. |
| P2 | A connected Binder was treated as sufficient evidence that automation worked, even if its worker had failed. | Track connection and operational health separately. The warning now says app detection is unavailable instead of asserting Android necessarily disconnected it. A successful sample clears transient window-read failure only while a coordinator still exists. |

The coroutine failure behavior is documented in Kotlin's [SupervisorJob reference](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-supervisor-job.html) and [CoroutineExceptionHandler reference](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-exception-handler/). Android documents the API 33 removal of node pooling in [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).

Other reviewed behavior: `onInterrupt()` remains a no-op because interrupting accessibility feedback is not a service disconnection. Cached foreground identities remain tied to observed window identities. The previous coordinator cannot mark a replacement coordinator unhealthy through a delayed failure callback. Its shutdown releases its supervisor even if cleanup fails.

## Cost and scope

Normal operation remains event-driven. There is no new periodic health poll or wake lock. Exceptions schedule only bounded retries; new real events can attempt a fresh sample after retries are exhausted. The input-method setting is read once per relevant event/sample rather than repeatedly for every package in the same sample. No frequency, governor, or accessibility-grant policy was changed by this audit.

This is internal recovery while the accessibility binding remains available. It does **not** add privileged toggling of accessibility when Android leaves the service enabled but unbound. An explicit user disable remains disabled. Persistent programming/storage faults are reported rather than retried indefinitely. Window/root IPC still runs synchronously; an OEM call that hangs instead of throwing can still cause an ANR. No evidence of such a hang was captured here, and moving sampling off the main thread requires a separate concurrency change.

## Evidence

The Thor's retained ClusterTune exit history contained instrumentation/package-update force stops and one empty-process eviction, with no recorded ClusterTune crash or ANR. Its crash buffer contained other applications' failures; those were not attributed to ClusterTune. This limited history does not rule out earlier crashes or reproduce the RP5 report.

New unit tests inject stale-window/security errors, check bounded retries and cancellation on disconnect, verify a successful later event restores the retry budget, and confirm VM errors are not swallowed. Storage tests cover successful resubscription, exhausted IO retries, non-IO failures, and cancellation.

Four new Android tests exercise the service and its actual callback/coordinator code with injected window reads:

1. A window read throws, stale ownership is cleared, and a delayed retry recovers without another accessibility event.
2. Unbind unregisters listeners, rejects late wake/window callbacks, and reconnect resumes sampling.
3. A stream of events faster than the coalescing delay still produces samples.
4. An injected child-coroutine exception is handled without crashing; connection remains reported separately from failed worker health.

These injected tests use attached service instances with a controlled window source; they are not proof that Android automatically rebinds a crashed production service. They passed on the Thor in 3.585 seconds. Physical binding/sleep checks and final regression totals follow.


### Final verification

- **576 JVM tests passed**; debug app/test builds succeeded. Lint: **0 errors, 59 warnings** (one additional `UseKtx` style suggestion on the pooled-window cleanup loop).
- Complete Thor suite: **73 passed / 5 conditional skips / 0 failures**, 117.066 seconds. This includes the four new fault-injection tests. The conditional protected-frequency fixtures were not rerun in this audit; their earlier results remain separate.
- Three real accessibility disable/re-enable cycles, with sleep/wake both while disabled and after reconnection, passed. The binding returned every time; the app PID remained 32533 and the exact enabled-service list was preserved. Settings/main-window transitions were also exercised. These deliberate toggles verify lifecycle handling, not automatic repair of an OS-stuck binding.
- Normal app UI showed no permission/automation warning after reconnection. The installed notification reads **ClusterTune is tuning your clusters**.
- The updated build remains installed. Large Underclock was reapplied with original ceiling values and all three CPU minimum modes at 0440. The prior accessibility grants and 60-second screen timeout were restored.

Evidence: [fault injection](research/accessibility/fault-injection.txt), [complete device suite](research/accessibility/device-regression.txt), [binding cycles](research/accessibility/binding-cycles.json), [retained exit history](research/accessibility/exit-history-before.txt).
