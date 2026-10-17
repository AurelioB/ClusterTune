# Letter apps for physical device tests

`./gradlew :test-apps:assembleDebug` builds four independent, permission-free apps:
`com.aure.clustertune.fixture.a`, `.b`, `.c`, and `.d`. Each shows its white letter on black.
They are separate APKs, not part of the ClusterTune APK.

A and B receive different profiles. C and D start unassigned, so each physical screen can be
switched independently between assigned and unassigned apps without opening personal apps.

By default the apps draw only when needed. The optional test intent extra `renderFrames=true`
redraws the same letter each frame, for real SurfaceFlinger/Auto Tune telemetry checks. There
is no extra visible content, background rendering, or simulated FPS data. This checks frame
plumbing; it does not represent a game's performance or a demanding GPU workload.
