# ClusterTune component library plan

## Goal

Create a small, source-owned Compose component library that makes ClusterTune's
current visual language consistent across the main app, Settings, dialogs, and
system overlays.

The library should make design changes predictable without replacing Material 3,
changing working feature behavior, or turning every screen into a generic
configuration object.

## Decision

- Keep Jetpack Compose Material 3 as the behavior and accessibility foundation.
- Start inside the existing `:app` module under a clear `ui/designsystem` package.
- Own the component source, using a short `Ct` prefix where a name could collide
  with Material components.
- Move the package into a `:core:designsystem` Gradle module only after its APIs
  have stabilized and there is a concrete second consumer or build-time benefit.
- Add no third-party component library during this migration.
- Do not combine the extraction with a Compose BOM upgrade. Dependency upgrades
  should remain independently reviewable.

This is a component extraction and consistency project first. Intentional visual
changes should be made as explicit follow-up changes after parity is established.

## Authoritative baseline

The current source and current installed build are authoritative. Existing
screenshots in `docs/screenshots/` predate the current design and must not be used
as component references or visual goldens.

Before extraction, capture fresh baselines from the current build for:

- the Profiles, Apps, and History views;
- the current Settings sections and dialogs;
- the compact tuner and profile-picker overlays;
- light, dark, system-color, and custom-accent configurations;
- selected, unselected, disabled, loading, empty, and error states.

## Design principles

1. **Material behavior, ClusterTune appearance.** Wrap Material controls instead
   of recreating switches, sliders, radio behavior, focus, or ripple handling.
2. **State is hoisted.** Components receive values and callbacks; repositories,
   services, ViewModels, and polling remain in feature code.
3. **Slots over flag collections.** Shared surfaces expose leading, content, and
   trailing slots. Avoid a universal row with a large set of feature booleans.
4. **Semantic tokens over repeated literals.** Name row, panel, divider, selected,
   disabled, and scrim roles. Do not mechanically replace every `dp` value.
5. **Visual and interactive size are separate.** A control may look compact while
   retaining a 48 dp target and correct accessibility semantics.
6. **Dynamic color is a first-class constraint.** Component colors derive from
   the active Material color scheme and are checked in both light and dark modes.
7. **Overlays have explicit layers.** Scrim, panel, and content are separate
   components. A scrim is never implemented as alpha on the entire overlay tree.
8. **Caller-owned copy.** Shared components do not hardcode product strings or
   content descriptions. Callers supply localized resources.

## Proposed package structure

```text
app/src/main/java/com/aure/clustertune/ui/
  designsystem/
    theme/
      ClusterTuneTheme.kt
      ClusterTuneColors.kt
      ClusterTuneTypography.kt
      ClusterTuneShapes.kt
    token/
      ClusterTuneSpacing.kt
      ClusterTuneSizing.kt
      ClusterTuneElevation.kt
      ClusterTuneMotion.kt
      ClusterTuneBreakpoints.kt
    component/
      CtAppIdentity.kt
      CtDashedCard.kt
      CtDivider.kt
      CtModalScaffold.kt
      CtOverlayFrame.kt
      CtPreferenceRow.kt
      CtRowSurface.kt
      CtSectionCard.kt
      CtSelectableRow.kt
      CtSelectionIndicator.kt
      CtSlider.kt
      CtStatePanel.kt
    preview/
      ClusterTunePreview.kt
      ComponentFixtures.kt
```

Feature-aware components stay outside the design system:

```text
ui/tuner/       profile rows, policy editor, frequency metadata
ui/settings/    settings sections and execution-method presentation
ui/overlay/     foreground-app and overlay-service integration
```

The initial migration does not need to move every screen immediately. Package
splits can happen after shared primitives have replaced the private duplicates.

## Theme and token foundation

### Theme separation

Split the current theme into:

- a pure Compose theme that maps system or seeded colors to Material 3;
- an app-layer mapping from `AppSettings` to a small theme configuration;
- Activity-only status/navigation-bar effects owned by the Activity layer.

This prevents a service-hosted overlay from depending on Activity window behavior.

### Semantic token groups

| Group | Initial roles |
| --- | --- |
| Color | row surface, selected row surface, subtle border, divider, muted content, modal surface, overlay panel, overlay scrim |
| Typography | screen title, section title, row title, supporting text, metadata, value, control label |
| Shape | icon tile, field, row, section, modal, full/pill |
| Spacing | screen padding, section gap, card padding, row content gap, compact modal padding |
| Sizing | 48 dp target, visual control sizes, icon sizes, divider/stroke widths, modal maxima |
| Adaptive layout | compact, medium, and expanded widths; overlay and dialog constraints |
| Elevation | flat, selected, floating, and modal surfaces |
| Motion | short state change and modal visibility durations, with reduced-motion behavior |

Prefer semantic colors over proliferating alpha constants. User-controlled edge
handle opacity and one-off device geometry are not design tokens.

## Initial component catalog

### Foundation components

| Component | Responsibility |
| --- | --- |
| `CtRowSurface` | Shape, background, border, enabled/selected visual state, and content slots for row-like controls |
| `CtSelectionIndicator` | Shared selected/unselected circular indicator with a stable semantic state |
| `CtDivider` | The modal/section divider recipe currently repeated throughout the tuner UI |
| `CtSectionCard` | Consistent section container, padding, and optional title slot |
| `CtDashedCard` | Shared dashed outline used by empty and add-new profile states |
| `CtStatePanel` | Empty, loading, warning, and error presentation without feature-specific copy |

### Input and preference components

| Component | Responsibility |
| --- | --- |
| `CtPreferenceRow` | Title, optional helpful description, and trailing-control layout with merged semantics |
| `CtSwitchPreference` | Preference-row wrapper with one toggle action and `Role.Switch` |
| `CtSelectableRow` | Radio/single-choice behavior using `selectable`, `selected`, and a trailing slot |
| `CtSlider` | Compact visual slider with a full target, progress semantics, labels, and optional commit callback |
| `CtNumericField` | Digit filtering, numeric keyboard, external-value synchronization, and bounds |

### Modal and overlay components

| Component | Responsibility |
| --- | --- |
| `CtModalScaffold` | Header, optional app identity, scrollable content, dividers, and action footer |
| `CtOverlayFrame` | One background scrim, outside dismissal, opaque panel, size constraints, and content isolation |
| `CtAppIdentity` | App icon, label, and optional package/subtitle with compact and regular variants |
| `CtConfirmationDialog` | Standard and destructive confirmation structure with caller-provided labels |

`OverlayComposeViewFactory` should also be extracted as overlay infrastructure,
not as a visual component. It should install lifecycle, ViewModel-store, and
saved-state owners before `setContent` and keep edge-handle setup separate from
modal-overlay setup.

## Component API contract

Every shared component should:

- be `internal` until there is a reason to publish an API;
- accept required state and callbacks first, then `modifier` and optional values;
- expose `enabled`, `selected`, and error state where relevant;
- provide content slots instead of accepting domain models;
- avoid `TunerState`, `PerformanceProfile`, `CpuPolicyInfo`, `AppSettings`,
  repositories, services, or ViewModels;
- expose one semantic action per user action rather than nested duplicate click
  targets;
- retain at least a 48 dp interaction target even when the visible control is
  smaller;
- include focused previews and semantics tests alongside its implementation.

Feature wrappers may accept domain objects, but they should assemble the shared
primitives rather than expand the primitive APIs to understand the domain.

## Delivery phases

### Phase 0: characterization and guardrails

- Capture fresh visual baselines from the current source and installed build.
- Inventory reachable UI variants before turning them into public component
  parameters. Remove or confirm currently unused branches such as unused compact
  flags rather than preserving accidental flexibility.
- Add the Compose UI-test dependency and an `androidTest` skeleton.
- Add a deterministic preview theme that does not depend on an OEM wallpaper.
- Document the component API and naming rules in the design-system package.

Outcome: current behavior is observable before any extraction begins.

### Phase 1: theme and tokens

- Separate pure theme rendering from Activity system-bar effects.
- Add typography, shapes, spacing, sizing, and semantic color roles.
- Initially alias tokens to the current values to preserve visual parity.
- Centralize the duplicated seeded light/dark color-role mapping.
- Define an opaque overlay-panel color and a separately composited scrim color.

Outcome: screens can consume named design decisions without changing appearance.

### Phase 2: exact leaf extractions

- Extract `CtDivider`, `CtSelectionIndicator`, `CtDashedCard`, `CtAppIdentity`,
  and `CtStatePanel`.
- Replace exact duplicate implementations one at a time.
- Add previews for all states and UI tests for selection, labels, click actions,
  and target sizes.

Outcome: low-risk duplication is removed and the component workflow is proven.

### Phase 3: Settings migration

- Build `CtPreferenceRow`, `CtSwitchPreference`, `CtSelectableRow`, `CtSlider`,
  and `CtNumericField`.
- Migrate Settings sections first because their repeated row/section structure is
  lower-risk than profile editing and overlay hosting.
- Associate labels with switches, radio buttons, sliders, and numeric inputs.
- Move user-facing strings and accessibility descriptions to resources as each
  section is touched.
- Split `SettingsScreen.kt` by section after the shared APIs settle.

Outcome: Settings becomes the first complete consumer of the library.

### Phase 4: tuner rows and dialogs

- Extract `CtRowSurface`, `CtSectionCard`, `CtModalScaffold`, and confirmation
  patterns.
- Rebuild profile-choice, profile-list, history, app, and policy rows as thin
  feature wrappers.
- Keep profile precedence, drag state, tuning commands, and frequency formatting
  in tuner feature code.
- Add accessible move-up/move-down actions to reorder controls and progress
  semantics to the frequency slider.
- Split `TunerScreen.kt` into screen, dialogs, profile, app-profile, history, and
  policy-editor files.

Outcome: feature behavior remains local while common visuals become consistent.

### Phase 5: overlays

- Add `CtOverlayFrame` with explicit scrim and opaque panel layers.
- Migrate compact tuner and compact profile picker to the same overlay frame and
  modal scaffold.
- Extract `OverlayComposeViewFactory` while preserving view-tree owner ordering.
- Preserve Back dismissal, outside-tap dismissal, foreground-app refresh, gesture
  exclusion, and edge-handle behavior.
- Verify that only content outside the panel is dimmed; do not apply window or root
  alpha to the panel.

Outcome: app and overlay share components without sharing lifecycle assumptions.

### Phase 6: stabilization and optional module extraction

- Remove replaced private components and unused visual constants.
- Resolve the dual icon approach and establish one library-owned icon API.
- Add a component catalog screen available only in debug builds if previews are
  insufficient for on-device review.
- Add lint, unit, UI, and visual checks to CI.
- Decide whether the stable `ui/designsystem` package merits extraction into
  `:core:designsystem`. Do not create a module solely for architectural symmetry.
- Replace documentation screenshots with current captures as a separate,
  reviewable documentation change.

Outcome: the library has stable boundaries and automated protection.

## Validation strategy

### Preview matrix

Every component should cover:

- light and dark deterministic themes;
- representative custom accent hues;
- normal, selected, disabled, loading, empty, and error states as applicable;
- compact landscape, phone portrait, and expanded widths;
- font scales 1.0, 1.3, and 2.0;
- LTR and RTL where layout direction matters.

Dynamic system colors remain an on-device smoke-test concern because wallpaper and
OEM palettes are intentionally nondeterministic.

### Automated tests

- JVM tests for seeded palette determinism, token invariants, and content/container
  contrast.
- Compose UI tests for roles, merged labels, selected/toggle state, progress
  actions, dismiss actions, headings, live regions, and minimum targets.
- A small set of component visual goldens plus screen-level goldens for main,
  Settings, and both overlays.
- Existing unit tests continue to protect tuning and execution behavior.

### Device matrix

- Emulator at minimum supported API 31.
- Emulator at the target API used by the project.
- The connected AYN handheld for landscape, dynamic color, system overlay,
  navigation gesture, Back, and TalkBack validation.

For overlay components, emulator-only testing is insufficient. Validate the real
`TYPE_APPLICATION_OVERLAY` window, focus order, outside touches, Back, foreground
app changes, and return-to-home behavior on the handheld.

### Build gates

At the end of each phase run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Visual-baseline verification should be added to CI once the chosen screenshot
mechanism is deterministic in the project environment.

## Acceptance criteria

- The main app, Settings, dialogs, and overlays continue to behave as before
  except for explicitly approved accessibility fixes.
- Shared primitives have no dependency on feature state, repositories, services,
  or ViewModels.
- Dynamic and custom accent colors remain supported in light and dark modes.
- Compact visuals retain accessible interaction targets and semantic state.
- The profile-picker panel is opaque and unaffected by the outside scrim.
- Back, outside dismissal, overlay refresh, and profile-selection behavior remain
  covered by tests and real-device checks.
- Every library component has previews and at least one behavior or semantics
  test.
- No stale documentation screenshot is used as a visual baseline.
- No third-party component library or unrelated Compose dependency upgrade is
  introduced as part of the migration.

## First implementation slice

The first implementation change should contain only:

1. the package skeleton and component conventions;
2. the deterministic preview wrapper and Compose UI-test setup;
3. theme/system-bar separation with parity-preserving tokens;
4. `CtDivider`, `CtSelectionIndicator`, and `CtDashedCard`;
5. migration of their exact duplicate call sites;
6. previews, semantics tests, unit tests, and a fresh device comparison.

This slice proves the architecture without touching profile execution, overlay
lifecycle, or complex screen state.
