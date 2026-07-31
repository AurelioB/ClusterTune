# Onboarding wizard plan

## Goal

Give a new user a working ClusterTune setup without presenting a wall of
permissions. The wizard should:

- verify a privileged execution method before claiming tuning is ready;
- explain optional features before requesting the access they need;
- request only the access needed for features the user chooses;
- survive Android settings screens, activity recreation, and an interrupted setup;
- remain skippable and available again from Settings.

The wizard is for setup, not a second settings screen. Update-install access stays
in **Settings > Updates** and is requested only when the user installs a downloaded
update.

## Product principles

1. **Explain the benefit, then request access.** Every Android settings screen is
   preceded by a user action tied to a feature.
2. **Capabilities beat device names.** Probe PServer and Root in order. Never
   assume support solely from the manufacturer or model.
3. **One system prompt at a time.** Persist the next wizard step before leaving
   ClusterTune and re-check real state when the app resumes.
4. **Optional means optional.** A denied or skipped feature permission must not
   block manual tuning.
5. **Attempted is not active.** Intent launches, permission requests, and service
   start requests do not count as successful setup until their resulting state is
   verified.

## Proposed flow

### 1. Welcome

Briefly introduce the three jobs ClusterTune performs:

- create and apply CPU profiles;
- switch profiles quickly while using another app;
- automate profile changes by app or screen state.

Also explain that tuning requires a supported privileged execution method and
that aggressive limits can affect stability.

Actions: **Start setup** and **Skip for now**.

### 2. Detect execution method

Do not start a Root probe before the user reaches this screen. Explain that
ClusterTune will check PServer first and Root second, and that a root manager may
display its own authorization prompt.

The UI should show each probe independently:

```text
PServer       Checking… | Ready | Not available | Failed
Root          Waiting…  | Ready | Denied | Timed out | Failed
```

Detection order is:

1. `pserver-stdout`
2. `root-shell`

Only these production methods should appear. Do not expose inactive compatibility
or Shizuku implementations in onboarding.

On success, show the verified active method and refresh repository state so
detected CPU clusters and profiles are immediately available. On failure, offer
**Retry**, **Choose manually**, and **Continue without tuning**. Continuing must
not cause the same probe to run silently on every launch.

### 3. Choose optional features

Use short selectable cards. Requirements are shown on the card before selection,
then deduplicated into the following access steps.

| Feature | What the user gets | Requirements |
| --- | --- | --- |
| Quick Settings tile | Profile controls without opening the main app | No Android permission for the tile itself; profile-picker overlays require Overlay access |
| In-game/left-edge picker | Swipe from the left to choose a profile over the current game | Overlay access and Usage access |
| App profiles | Apply a saved profile when its app becomes active | Usage access and at least one app assignment; Notifications make the service status visible |
| Sleep profile | Apply one profile while the screen is off and restore on wake | Verified execution and a selected profile; Notifications make the service status visible |

The Quick Settings tile can be recommended by default. The other features should
be opt-in.

### 4. Grant relevant access

Build this part of the flow from the selected feature requirements. If two
features need the same access, request it once.

#### Overlay access

Suggested explanation: “Display ClusterTune controls over games.”

Open the app-scoped overlay settings screen. When ClusterTune resumes, check
`Settings.canDrawOverlays()` and show **Allowed** or **Not allowed**. A refusal
leaves overlay-dependent features disabled but does not stop the wizard.

#### Usage access

Suggested explanation: “Identify the foreground game for app profiles and the
edge picker. App activity is processed locally.”

Open Android's Usage Access screen. On resume, re-check AppOps through the existing
usage-access helper. Do not persist the grant as truth.

#### Notifications

Suggested explanation: “Show background status while overlays and automation run.”

On Android 13 and later, use the runtime permission request first. If Android no
longer presents that prompt, take the user to ClusterTune's notification settings.
Use `NotificationManagerCompat.areNotificationsEnabled()` for displayed status.
The permission callback must inspect its Boolean result and display the real
outcome. Android does not require this grant to start a foreground service, so a
denial may hide the notification but should not disable an otherwise valid
feature.

Every access page has **Open settings/Allow**, **Not now**, and **Continue** after
the app has verified the result.

### 5. Activate selected features

Activation happens after access is granted, not when a feature card is selected.

- **Quick Settings tile:** use `QuickSettingsTileAddResult` and allow retry after
  `NOT_ADDED` or `ERROR`. On Android 12/12L, show concise manual tile-editor
  instructions.
- **Left-edge picker:** persist enabled and start the overlay service only after
  both Overlay and Usage access are present.
- **App profiles:** Usage access alone is not an active profile. Offer **Set up
  first app**, routing to the Apps tab, or **Assign later** with a “Ready” state.
- **Sleep profile:** require the user to choose an existing profile before
  persisting the feature as enabled and starting its service.

If a prerequisite disappears while the wizard is in progress, return the feature
to **Needs attention** instead of leaving an enabled-looking but inactive setting.

### 6. Review

Show actual state:

```text
Execution       PServer / Root / Not configured
Quick Settings  Added / Not added
Edge picker     Active / Needs access / Off
App profiles    Active / Ready for an assignment / Off
Sleep profile   Active with <profile> / Off
```

**Finish** atomically records the completed onboarding version and clears
transient progress. Incomplete items link to the relevant wizard step. Add **Run
setup again** to Settings without clearing profiles or unrelated preferences.

## State and migration

Persist wizard progress in `SettingsStorage`, not profile storage:

```kotlin
data class OnboardingProgress(
    val completedVersion: Int = 0,
    val currentStepId: String? = null,
    val selectedFeatureIds: Set<String> = emptySet(),
    val skippedFeatureIds: Set<String> = emptySet(),
    val executionDetectionAttempted: Boolean = false,
)
```

Use durable string IDs with safe fallback parsing. Persist the destination step
before launching an external settings screen. On completion, update
`completedVersion` and clear progress in one DataStore edit.

Do not persist permission state. Always derive Overlay, Usage, Notifications, tile
presence, available profiles, and the active execution method again on resume.
Android backup can restore preferences onto a device whose grants and capabilities
are different.

Existing installations should not be interrupted by onboarding v1. Before any
legacy first-run side effect runs, initialize onboarding state and use the
pre-existing Quick Settings prompt key as the legacy-install sentinel. Mark those
installs complete and let users opt into the wizard from Settings. Remove or gate
the old eager tile prompt first so it cannot make a fresh install look like a
legacy one.

Routing must also distinguish **settings not loaded** from `AppSettings()` defaults.
Otherwise returning users can briefly see the wizard before DataStore emits.

## Execution detection changes

The resolver currently keeps useful probe details private and can silently use a
different method from the configured one. Before using it in onboarding:

1. Run probes on an IO dispatcher.
2. Return a structured report, for example:

   ```kotlin
   data class ExecutionDetectionReport(
       val selectedMethodId: String?,
       val probes: List<MethodProbeResult>,
   )
   ```

3. Represent `Checking`, `Available`, `NotPresent`, `Denied`, `TimedOut`, and
   `Failed` distinctly enough for useful UI.
4. Persist only a verified method.
5. Track “detection completed with no compatible method” separately from “never
   checked.”
6. Make configured, probing, and active method explicit so a failed PServer
   selection cannot be presented while commands are actually using Root.

## Implementation shape

The existing single-activity UI does not need Navigation Compose. Add a third
root branch alongside the main screen and Settings:

```text
Loading persisted settings
          |
          v
Onboarding required? -- no --> Main UI <--> Settings
          |
         yes
          v
   Onboarding wizard
```

Likely change surface:

- `MainActivity.kt`: loaded-state routing, wizard branch, permission result
  handling, and removal of eager first-run prompts.
- `AppSettings.kt` and `SettingsStorage.kt`: versioned progress, legacy
  initialization, and atomic completion.
- `PrivilegedExecutionMethod.kt`: structured, off-main detection results.
- `TunerViewModel.kt`: expose loaded settings and wizard actions/results.
- new `ui/OnboardingState.kt`: pure step and dependency reducer.
- new `ui/OnboardingScreen.kt`: adaptive Compose flow.
- new shared `permissions/AppAccess.kt`: permission status and intent construction
  used by both onboarding and Settings.
- `SettingsScreen.kt`: **Run setup again** entry.
- `TunerScreen.kt`: optional completion route to the Apps tab.

## Delivery phases

1. **Foundation**
   - Add a shared access-status provider.
   - Add loaded-state routing.
   - Expose structured execution probe results and run probes off-main.
   - Make notification denial explicit without blocking otherwise valid services.

2. **Wizard shell**
   - Add versioned/resumable state and legacy-install initialization.
   - Add Welcome, Back, Skip, and Review behavior.
   - Gate the existing first-run tile prompt and execution auto-detection.

3. **Execution setup**
   - Implement the explicit detection screen, progress, failures, retry, and
     manual verified selection.

4. **Feature and permission setup**
   - Add feature selection, deduplicated access steps, resume-time verification,
     and prerequisite-aware activation.

5. **Handoff and hardening**
   - Add tile/app-profile/sleep configuration handoffs.
   - Add **Run setup again**.
   - Verify accessibility, rotation/process recreation, denial, revocation, and
     device backup/restore behavior.

## Test plan

Keep progression logic pure and cover it with the existing JUnit 4 style:

- every feature combination produces the correct deduplicated access steps;
- Back, Skip, activity recreation, and process recreation resume correctly;
- PServer success prevents a Root probe;
- PServer failure falls through to Root;
- Root denial, timeout, and no-compatible-method remain retryable;
- successful detection is persisted, while an unavailable result does not repeat
  silently on every launch;
- a launched settings intent does not count as a grant;
- permission denial never activates a dependent feature;
- legacy installs skip onboarding v1 and fresh installs do not;
- completion is atomic and **Run setup again** preserves user data;
- tile `NOT_ADDED`/`ERROR` results remain retryable.

Add device/instrumentation coverage for Android settings round-trips, the Android
13 notification request, Quick Settings tile addition, rotation, and service
activation. There is no current `androidTest` suite, so this should be introduced
as a separate test-infrastructure slice.

## Acceptance criteria

- A fresh install never sees a root authorization prompt before choosing to
  detect.
- Detection uses PServer then Root and shows the verified active method.
- A user can finish without optional permissions or without a compatible executor.
- Selecting a feature requests only its required access and never repeats an
  already granted step.
- Returning from Android settings updates the wizard without requiring a restart.
- No feature is shown as active when a prerequisite is missing.
- Existing users are not forced through onboarding after upgrading.
- The wizard can be rerun from Settings without resetting profiles.
