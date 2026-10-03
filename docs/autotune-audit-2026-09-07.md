# Auto Tune audit, September 7, 2026

Compared the current implementation with GameNative master `627bf6f8d90fc4cca507e89664ed4df26f06a38e`. Our latest committed tuning work is dated August 25; no exact earlier upstream review revision was recorded, so August 25 is the comparison boundary.

## Upstream changes since the previous work

[Power-control hotfix #1861](https://github.com/utkarshdalal/GameNative/commit/b69458827f4c924242bf7706e1b7f3d0760eac68), August 29, is the only subsequent commit touching the power-control package.

* Adaptive FPS capping gets a new serialized preference, with defaults restricted to the tested Retroid models. Odin 3 leaves that default-enabled list. This is an FPS limiter feature, separate from adjusting frequency ceilings. ClusterTune does not control game frame limiters and already requires an explicit per-app Auto Tune assignment.
* The cap controller tolerates an unavailable profile and reconciles its enabled state whenever a profile is applied. The final source retains unconditional driver startup on resume because pause shuts it down. That resume call was already present at the comparison boundary; it is a useful lifecycle invariant, not a newly introduced change to bring over.
* There are no new changes to the per-cluster decision engine after August 25. The useful controller differences below are existing upstream behavior that our implementation had diverged from.

## Independently implemented corrections

* Start frame telemetry before capturing the initial frequency checkpoint. On the Thor, the OEM changed an unlocked ceiling during telemetry initialization and our cross-initialization equality check aborted with `frequency ceilings changed while Auto Tune telemetry was starting`. No frequency writes had occurred yet. Capturing the live starting point after telemetry is ready removes this unnecessary race; transaction-time ownership checks remain.
* Do not stop a maximum-only session when the OEM changes a minimum-frequency vote. A longer live test reproduced `live minimum changed (policy0 minimum changed)`, even though the efficiency policy was excluded from adaptive writes. Minimums remain OEM-managed and are never written or restored by Auto Tune. Ceiling ownership checks remain independent.
* Let baseline application use the normal maximum-only executor instead of rejecting it beforehand because an OEM minimum is higher or temporarily unreadable. Actual write verification and rollback still govern acceptance. Initial lower tuning bounds remain conservative; this does not permit new steps below the captured envelope.
* Raise frequency ceilings directly on an FPS miss or excessive slow frames. Remove raise trials, no-gain deprioritization, and recovery freeze maps. First recovery raises three supported frequency steps; repeated misses reopen the selected domain to its baseline. Keep regression watches and temporary holds for reductions.
* Use high p95 frame time to prevent further reductions, not as an independent reason to raise frequencies when FPS and the slow-frame ratio remain healthy.
* Wait through missing or stale frame telemetry. Previously four seconds without frames permanently stopped the session and paused the same foreground assignment. Foreground changes, manual selection, host failures, and screen state still control session lifetime.
* Observe effective profiles and active Auto Tune target changes in the Quick Settings tile. Ignore telemetry-only updates so the tile does not refresh every sampling cycle.
* Do not display a persisted Auto Tune identity as active when there is no active runtime, including after process restart or failed cleanup.
* Treat a positively identified transient system panel as covering the current app, not as a new foreground app. On the dual-screen Thor, expanding Quick Settings removes the game's application window from accessibility enumeration; the old logic handed Auto Tune ownership to the secondary-screen launcher and restored Stock. Re-evaluate underlying applications when the panel disappears; an unexplained missing application still uses the bounded disappearance check.
* Report an Auto Tune failure as such instead of showing the Stock baseline toast during cleanup.
* Preserve runtime telemetry through a data-class copy when invalidating a session instead of duplicating every field.

No upstream source code was copied into the project.

## Behavior retained after review

Maximum-only writes, saved normal-profile ceilings, a single privileged host, and restoration of owned ceilings remain appropriate. Unlike an integrated game launcher, ClusterTune can be preempted by a manual profile, sleep automation, another foreground app, or another privileged tool. Session IDs, host epochs, and serialized writes protect those observed ownership transitions.

The host heartbeat remains necessary: a coroutine timeout cannot interrupt a synchronous Binder call. Lifecycle cancellation and the host watchdog cover different failures.

Foreground automation uses current accessibility windows with bounded disappearance confirmation. The picker can retain a display's last app identity for presentation without extending automation ownership. An OEM exposing only its assistant window can still end automation after the confirmation period; broad indefinite ownership caching would reintroduce stale app profiles.

## Worth evaluating separately

[GameNative's decision engine](https://github.com/utkarshdalal/GameNative/blob/627bf6f8d90fc4cca507e89664ed4df26f06a38e/app/src/main/java/app/gamenative/powercontrol/autotuning/TunerDecisionEngine.kt) can reduce an idle domain even when target FPS is unreachable and the limiting domain is already at maximum. It uses GPU-load hysteresis, an averaged baseline, and a regression watch. This can save power in GPU-bound games, but needs workload traces before adding another tuning path.

[Its cluster wrapper](https://github.com/utkarshdalal/GameNative/blob/627bf6f8d90fc4cca507e89664ed4df26f06a38e/app/src/main/java/app/gamenative/powercontrol/autotuning/ClusterTuner.kt) also remembers learned steps per game container. ClusterTune sees Android packages, which can host many different emulated games, so persisting one learned starting point per package would conflate different workloads.

Samsung SDK controls, Wine process affinity, fan control, and adaptive game FPS limiting are distinct capabilities outside this correction.

## Verification

* All 542 unit tests pass, including OEM ceiling changes during telemetry startup, OEM CPU/GPU minimum changes, maximum-only restoration, stale-frame resumption, transient-panel foreground continuity, and tile state after restart.
* Debug app and instrumentation APK builds and Android lint pass. A clean serial build also passed before the final baseline-gate cleanup; the final incremental full verification passed afterward.
* The Thor passed the PServer privileged-host integration test with live frame sampling, adaptive writes, and restoration. Manual device checks verified active tile labels for Auto Tune at 30 and 45 FPS and Small Underclock.
* On the final build, Auto Tune stayed active throughout a 30-second expanded Quick Settings check and after dismissal, without adding a stop/restart to profile history. Accessibility remained bound and the screens were put to sleep after testing.
* The final debug build is installed without temporary trace logging. Existing app data and unrelated HUD work were preserved. The temporary FPS target was returned to 30 and the normal profile to Stock.
* Removed the redundant downloaded JDK archive, an obsolete unsigned release APK, the temporary upstream checkout, and intermediate audit screenshots, reclaiming roughly 550 MiB. The installed JDK and current build outputs remain available.
