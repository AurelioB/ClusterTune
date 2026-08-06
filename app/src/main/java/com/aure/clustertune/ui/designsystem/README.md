# ClusterTune design system

This package is ClusterTune's source-owned Compose component library. Material 3
continues to provide control behavior, focus, ripple, and accessibility; these
components provide the app's compact visual recipes and semantic roles.

## Boundaries

- Components are `internal`, state-hoisted, and feature-agnostic.
- Callers own all product copy, content descriptions, domain state, and side effects.
- Prefer slots over domain models or growing collections of feature flags.
- Keep visual size separate from the 48 dp interaction target.
- Use `ClusterTuneColors`, typography, shapes, and tokens instead of introducing
  another unexplained literal when a semantic role already exists.
- Activity code owns system-bar effects. The pure design-system theme is also safe
  for service-hosted overlays.
- Overlay scrims and opaque panels must remain separate layers.

## Components

- Surfaces and state: `CtRowSurface`, `CtSectionCard`, `CtDashedCard`,
  `CtStatePanel`, and `CtDivider`.
- Icons: `CtIcon`, with font-symbol and vector overloads behind one caller API.
- Preferences and input: `CtPreferenceRow`, `CtSwitch`, `CtSwitchPreference`,
  `CtSelectableRow`, `CtSelectionIndicator`, `CtSlider`, and `CtNumericField`.
- Dialogs and overlays: `CtModalScaffold`, `CtConfirmationDialog`,
  `CtOverlayFrame`, and `CtAppIdentity`.

Feature-aware wrappers stay with their feature. A profile row, CPU-frequency
editor, or execution-method description should assemble these primitives rather
than teach the library about repositories, services, or feature models.

## Review and verification

`preview/ComponentCatalogPreviews.kt` provides deterministic light, dark, modal,
and state previews without relying on OEM wallpaper colors. JVM tests protect
tokens and palette invariants; Compose tests protect merged actions, selected and
disabled state, progress semantics, minimum targets, and overlay input isolation.
