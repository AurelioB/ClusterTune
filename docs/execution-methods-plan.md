# Execution methods plan

Goal: make ClusterTune usable on more Android handhelds by separating “how privileged commands run” from the tuning code that needs privileged reads/writes.

## Problem

Current code assumes one privileged path:

```text
ClusterTune -> RootCommandRunner / PServerSysfsReader -> PServerBinder -> stdout
```

That is too narrow. Some devices expose `PServerBinder`, but commands requested with output capture do not execute reliably. Others may only work with `su`. Shizuku remains available for explicit testing, but is not part of automatic selection because a normal shell-level Shizuku session may lack permission to change CPU controls.

## Target architecture

Introduce a small common API:

```kotlin
interface PrivilegedExecutionMethod {
    val id: String
    fun probe(): ExecutionProbeResult
    fun executeScript(scriptName: String, scriptContents: String): Result<String?>
    fun readText(path: String): String?
}
```

Everything above this layer should treat privileged execution as a capability, not as “PServer”. Existing call sites should continue to ask for:

- `isAvailable`
- `executeScript(...)`
- protected sysfs `readText(path)`

Ordinary Android filesystem access is always attempted first for sysfs reads and directory listings, regardless of the selected privileged method. Privileged reads are only a fallback when direct access fails. Writes and permission changes always use the selected privileged method and are verified through the direct-first read path.

## Method order

1. **PServer direct output**
   - Probe: `PServerBinder` exists and `echo <marker>` returns the marker.
   - Best path. Existing behavior, but explicitly capability-checked.
   - Request command output only for protected reads and listings. Dispatch writes and permission changes without output capture.

2. **PServer storage bridge**
   - Probe: `PServerBinder` exists and a command dispatched without output capture can write a unique marker to app-owned storage readable by ClusterTune.
   - Use for devices where asking PServer to capture output prevents reliable command execution.
   - `executeScript` runs the script with stdout/stderr redirected to an intermediary file.
   - `readText(path)` runs `cat path > intermediary-file`, then reads the intermediary file from app storage.

3. **Root shell (`su`)**
   - Probe: `su -c 'echo <marker>'` returns the marker.
   - Useful on rooted devices without PServer.
   - First implementation can be conservative and not lock files aggressively until tested.

**Manual only: Shizuku (experimental)**
   - Implemented behind the same API, but excluded from automatic detection.
   - Probe: Shizuku binder alive, app permission granted, and `echo <marker>` returns stdout.
   - Keep it unavailable for tuning unless an exact CPU-control capability probe succeeds; binder access and permission grant alone are insufficient.

## Implementation milestones

### Milestone 1: abstraction only

- Add `PrivilegedExecutionMethod` and `ExecutionProbeResult`.
- Add resolver/selector with ordered methods.
- Implement PServer stdout method.
- Implement PServer file-output fallback method.
- Wire `RootCommandRunner` and `PServerSysfsReader` through the selected method while keeping their public APIs stable.
- Unit-test method selection with fakes.

### Milestone 2: root shell fallback

- [x] Add `RootShellExecutionMethod`.
- [x] Use `ProcessBuilder("su", "-c", command)` with bounded output capture.
- [ ] On-device verify before treating it as supported in UI.

### Milestone 3: Shizuku experimental implementation

- [x] Add Shizuku API/provider dependencies and manifest provider.
- [x] Add `ShizukuExecutionMethod` behind the same API.
- [x] Probe binder availability, permission, and actual shell stdout using `echo <marker>`.
- [ ] On-device verify whether Shizuku's shell/root identity can read/write the sysfs nodes ClusterTune needs.
- [ ] Keep Shizuku manual-only unless it can access and update the required nodes.

### Milestone 4: capability reporting

Expose diagnostics in state/UI/logs:

```text
selected automatic method: pserver-stdout | pserver-file-output | root-shell | unavailable
pserver present: yes/no
stdout supported: yes/no
file-output fallback: yes/no
root shell: yes/no
last probe failure: ...
```

This matters because device support bugs will otherwise be opaque.

### Milestone 5: Shizuku qualification

- Check whether Shizuku can run the exact sysfs reads/writes ClusterTune needs on target devices.
- If yes, retain `ShizukuExecutionMethod` as an explicitly selected option.
- If no, report the capability failure clearly and do not allow tuning through it.

## Safety constraints

- Keep command escaping centralized.
- Intermediary files must live under an app-owned internal directory, use unique names per invocation, and be removed after completion.
- Do not trust stdout as proof of success for writes; keep readback verification.
- Resolver should cache the selected method for normal use, but there should be a way to force reprobe later.
- Never include Shizuku in automatic selection without evidence that it provides the needed privilege level.

## First code slice

Implement Milestone 1, then run:

```bash
./gradlew testDebugUnitTest
```

Expected outcome: existing behavior unchanged on devices with stdout-capable PServer, plus a fallback path for PServer implementations that execute commands but return no stdout.
