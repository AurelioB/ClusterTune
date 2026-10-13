# Auto Tune frame measurement

ClusterTune measures displayed output for an application layer. An emulator producing 30 game
frames and interpolating 30 more can therefore report 60 displayed FPS. Guest simulation rate
requires telemetry supplied by the emulator; SurfaceFlinger cannot identify generated frames.

## Pacing and confidence

The latency backend uses actual presentation timestamps, deduplicates them, excludes future and
pre-session timestamps, and accumulates up to two seconds of history (512 entries maximum).
Overlapping polls preserve history beyond SurfaceFlinger's own ring. If a new dump no longer
overlaps retained history, the source discards the uncertain interval rather than filling the
gap with assumed frames. Newly discovered timestamps older than one second are stale.

A complete latency window contains at least 1.8 seconds of presentation history and at least
`clamp(targetFps / 2, 2, 12)` intervals. It has confidence 1.0; incomplete history has confidence
0.5 and cannot justify a reduction. Frequency changes reset the history at the time the host
verifies the changed ceilings. No-op writes and failed transactions do not reset it.

FPS is `(windowTimestampCount - 1) / (lastTimestamp - firstTimestamp)`. The wire field
`frameCount` counts newly discovered frames in a poll, rather than all timestamps in the rolling
window. P95 and the slow-frame fraction use those rolling-window displayed intervals. The pacing budgets allow the longest refresh interval needed
to deliver the target, with a small clock tolerance. For example, ideal 45 FPS on 60 Hz contains
33.3 ms intervals. An additional missed refresh remains a slow interval.

TimeStats fallback estimates FPS from counter changes over elapsed monotonic time. Where all
layer slices provide `present2present` histograms, their differences provide approximate P95
and slow-frame fractions. A histogram-backed window needs at least 0.9 seconds and the same
minimum interval count, and has confidence 0.75. Histogram buckets have millisecond precision;
counter resets or missing histograms prevent qualification. Scheduler/compositor jank is a
separate `timelineJankRatioPermille`, never a replacement for the target-budget fraction.

The controller requires complete pacing data with confidence at least 0.75 to lower ceilings
or accept a reduction. It waits through mixed before/after windows. Incomplete measurements
can still undo a reduction or recover when FPS falls below the target. A changed layer,
backend, or refresh mode requires requalification and undoes any unproven reduction.

When the target exceeds a known live refresh rate and output is already near that rate, the
controller reports the display limit and holds. Lower throughput still permits recovery.
This does not infer an application cap or thermal limit from low FPS alone.

The additional fields use host protocol version 14. The normal host version check replaces an
older privileged host before these fields are read.

## Device validation

Use the opt-in `ExternalGameAutoTuneTraceTest` with a representative game already open. This
test changes frequency ceilings and restores the session checkpoint; it bypasses foreground
automation. Run it on a dedicated test device with Auto Tune enabled.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aure.clustertune.root.host.ExternalGameAutoTuneTraceTest \
  -Pandroid.testInstrumentationRunnerArguments.externalPackage=YOUR_GAME_PACKAGE \
  -Pandroid.testInstrumentationRunnerArguments.targetFps=45 \
  -Pandroid.testInstrumentationRunnerArguments.sampleCount=120 \
  -Pandroid.testInstrumentationRunnerArguments.requireBoundary=false
```

The app cache file `external-autotune-trace.jsonl` records measurement windows, confidence,
refresh periods, displayed FPS, pacing, Android jank, CPU/GPU loads, reported clocks, live
minimum/maximum values, thermal readings, and controller decisions. Battery current and voltage
provide a whole-device discharge-power estimate when the device exposes them and is discharging.
Unavailable sensors are null. These readings are collected by the opt-in test, not render threads.

Compare the same warmed scene with the same driver, game settings, brightness, cooling and
starting temperature. Warm shader caches before taking results, verify the applied ceilings,
and compare sustained pacing and power rather than a single FPS or frequency reading. Reported
clock values do not prove the hardware always obeyed the requested ceiling.

Cover 30/60 and 45/60 targets, 90/120, refresh changes, stalls, a replaced SurfaceView, delayed
polling, and devices with only TimeStats fallback. Check that histogram-free fallback holds
reductions while permitting recovery, and verify exact restoration after each session.

These changes are independently implemented following the measurement distinctions identified
in [ZeroFG's presenter](https://github.com/hy300leosquizz-ctrl/ZeroFG/tree/8ba7821a2b89f6696d171b6ebf3f275f9d3da918/host/xenia-android)
and [XenDroid-ZeroFG's frame telemetry](https://github.com/hy300leosquizz-ctrl/XenDroid-ZeroFG/blob/1fb985ae7fe587b90be35ab1f18b0d4b6a853f98/emulator-core/src/main/cpp/xenia/src/xenia/base/frame_stats.h).
