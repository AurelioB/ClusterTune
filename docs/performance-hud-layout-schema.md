# ClusterTune performance HUD layout format

## Status

This document is a design proposal. The format, parser, renderer, persistence,
and editor described here are **not implemented** yet.

The goal is to preserve a simple implementation path while defining enough of
the format now that responsive layouts, multiple panels, scrolling profile
selection, conditional content, and additional controls do not require a later
redesign.

## Decision

ClusterTune should use a small, versioned YAML format that is inspired by CSS
terminology but is not a CSS implementation.

- The existing HUD remains a native Jetpack Compose UI.
- YAML is decoded into a strict typed tree before anything is rendered.
- CSS names and behavior are reused where they match: `display`, `position`,
  `width`, `maxHeight`, `padding`, `gap`, `gridTemplateColumns`,
  `gridColumn`, and `overflowY`.
- ClusterTune-specific behavior remains explicit: `when`, `metric`, `graph`,
  `slider`, `toggle`, and `profileList`.
- There are no selectors, cascade, scripts, arbitrary callbacks, or embedded
  commands.
- Interactive widgets are built in and trusted. A layout can arrange them, but
  cannot invent a new privileged action.

This is "MangoHud Next-inspired," not MangoHud-compatible. The useful shared
idea is a set of positioned windows containing ordered layouts and typed
widgets. ClusterTune owns its schema and Android-specific behavior.

## Why this is the simplest path

The format is only a description of the UI. It does not become a second UI
framework:

```text
YAML
  -> decode
  -> validate and normalize
  -> typed HudDocument
  -> native Compose renderer
       |-> read-only value registry
       |-> allowlisted action registry
       `-> WindowManager placement
```

The implementation needs only four main pieces:

1. A small typed model and validator.
2. A renderer for flex, grid, stack, and built-in widgets.
3. A registry that exposes named HUD values and Boolean facts.
4. A window resolver for safe-area size and fixed placement.

It does not need an HTML engine, CSS parser, DOM, selector matcher, JavaScript
runtime, or experimental Compose styling API.

## Authoring conventions

- The file uses YAML 1.2.
- Keys, widget names, enum values, and state references use `lowerCamelCase`.
- Geometry uses `dp` by default. Text sizes use `sp`. Literal `px` is allowed
  for advanced device-specific layouts but should be discouraged in the UI.
- Nonzero lengths always include a unit.
- Multi-value properties use YAML arrays instead of CSS token strings:

  ```yaml
  padding: [8dp, 12dp]
  gridTemplateColumns: [48dp, 1fr, 72dp]
  ```

- All boxes use `border-box` sizing. Authors cannot change this.
- Unknown keys, widget types, state references, and enum values are errors.
- A layout is activated atomically only after the complete document validates.

## Normative defaults

Defaults are part of `schemaVersion: 1`; they cannot change silently between
renderer versions. The closed root theme has these keys:

| Property | Default |
| --- | --- |
| `fontFamily` | `monospace` |
| `fontSize` | `11sp` |
| `lineHeight` | `13sp` |
| `fontWeight` | `bold` |
| `textColor` | `#f0f3f4ff` |
| `mutedColor` | `#a7b0b5ff` |
| `accentColor` | `#6de4c2ff` |
| `panelColor` | `#16181bf5` |
| `borderColor` | `#ffffff40` |
| `frameGraphColor` | `#66d9a6ff` |
| `cpuGraphColor` | `#42a5f5ff` |
| `gpuGraphColor` | `#ef5350ff` |

The initial window and widget defaults are:

| Area | Defaults |
| --- | --- |
| Window | fixed at `top: 12dp`, `left: 12dp`; `width: auto`; flex column; stretched children; `[7dp, 9dp]` padding; `2dp` gap; clipped overflow; panel theme colors; `0.5dp` border; `9dp` radius |
| Metric | `48dp` label column; `72dp` by `16dp` graph; 30 history samples; graph color selected from the registered metric family |
| Slider | `72dp` visual track width; `16dp` visual track height; at least `48dp` actual layout and hit-target height |
| Profile list | single selection; virtualized rows with at least a `48dp` hit target |

Theme values are fixed roles, not arbitrary variables. Nodes may reference
them, for example `color: theme.accentColor`. Layout properties do not inherit.

## Complete example

This example expresses the current compact performance HUD, a collapsible
profile list, and the Auto Tune target control.

The smallest useful file is much shorter because windows, typography, colors,
spacing, and metric graph sizes have built-in defaults:

```yaml
format: clusterTuneHud
schemaVersion: 1

windows:
  - id: performance
    children:
      - { type: metric, label: FRAME, value: frame.summary, graph: frame.fps }
      - { type: metric, label: CPU, value: cpu.summary, graph: cpu.loadPercent }
      - { type: metric, label: GPU, value: gpu.summary, graph: gpu.busyPercent }
      - { type: metric, label: PROFILE, value: clusterTune.profile }
```

The expanded form below demonstrates customization rather than required
boilerplate.

```yaml
format: clusterTuneHud
schemaVersion: 1

flags:
  profilesOpen: false

theme:
  fontFamily: monospace
  fontSize: 11sp
  lineHeight: 13sp
  textColor: "#f0f3f4"
  mutedColor: "#a7b0b5"
  accentColor: "#6de4c2"
  panelColor: "#16181bf5"
  borderColor: "#ffffff40"
  frameGraphColor: "#66d9a6"
  cpuGraphColor: "#42a5f5"
  gpuGraphColor: "#ef5350"

windows:
  - id: performance
    position: fixed
    top: 12dp
    left: 12dp
    width: auto
    minWidth: 168dp
    maxWidth: 320dp
    padding: [7dp, 9dp]
    gap: 2dp
    backgroundColor: theme.panelColor
    borderColor: theme.borderColor
    borderWidth: 0.5dp
    borderRadius: 9dp
    display: flex
    flexDirection: column

    children:
      - type: metric
        label: FRAME
        value: frame.summary
        graph: frame.fps
        graphColor: theme.frameGraphColor

      - type: metric
        label: CPU
        value: cpu.summary
        graph: cpu.loadPercent
        graphColor: theme.cpuGraphColor

      - type: metric
        label: GPU
        value: gpu.summary
        graph: gpu.busyPercent
        graphColor: theme.gpuGraphColor

      - type: metric
        label: TEMP
        value: thermal.maxCelsius
        unit: "°C"
        precision: 0
        when: thermal.maxCelsius.available

      - type: metric
        label: OEM
        value: device.oemProfile
        when: device.oemProfile.available

      - type: metric
        label: FAN
        value: device.fanProfile
        when: device.fanProfile.available

      - type: metric
        label: PROFILE
        value: clusterTune.profile
        color: theme.accentColor
        when: clusterTune.profile.available

      - type: slider
        id: autoTuneTarget
        label: AUTO
        value: autoTune.targetFps
        min: 1
        max: display.refreshFps
        step: 1
        width: 72dp
        height: 48dp
        trackHeight: 16dp
        when: autoTune.active

      - type: toggle
        id: profilesToggle
        label: Profiles
        value: flags.profilesOpen

  - id: profilePanel
    position: fixed
    right: 12dp
    bottom: 12dp
    width: 35%
    minWidth: 180dp
    maxWidth: 320dp
    height: 40%
    padding: 8dp
    when: flags.profilesOpen

    children:
      - type: profileList
        id: profileList
        scope: global
        height: 100%
        overflowY: auto
```

The compact `metric` widget is the normal authoring path. It owns the standard
label/value/optional-graph arrangement. Authors only need primitive grid cells
when they want a custom arrangement.

## Document schema

### Root

| Property | Required | Meaning |
| --- | --- | --- |
| `format` | yes | Must be `clusterTuneHud`. |
| `schemaVersion` | yes | Exact schema version. Version 1 is defined here. |
| `flags` | no | Initial values for layout-local Boolean state. |
| `theme` | no | Document-wide visual defaults and named color roles. |
| `windows` | yes | One or more independently positioned HUD panels. |

### Windows

A window is both a native overlay window and the root layout container inside
it. It accepts the common layout, visual, and `when` properties plus:

| Property | Default | Meaning |
| --- | --- | --- |
| `id` | required | Stable unique identity. |
| `position` | `fixed` | Positioned relative to the selected safe viewport. |
| `safeArea` | `safeContent` | `safeContent` or explicit edge-to-edge `display`. |
| `keepInBounds` | `true` | Clamp the resolved window to the selected viewport. |
| `top`, `left` | `12dp` | Used only when neither inset on that axis is declared. |
| `width`, `height` | `auto` | Measure a tight window from its content. |
| `display` | `flex` | Default root layout. |
| `flexDirection` | `column` | Default root flow. |
| `alignItems` | `stretch` | Children fill the available cross axis by default. |
| `children` | required | Ordered child nodes. |

Each panel is a tight rectangular Android window. Multiple windows are the safe
way to place independent HUD islands far apart. A transparent full-screen
window must not be used as a positioning canvas because it can intercept game
input even where no visible widget is drawn.

`safeContent` means the current window-metrics bounds minus stable insets for
system bars (ignoring visibility), display cutouts/waterfall areas, and
mandatory system gestures. It excludes the IME because the HUD remains
non-focusable. `display` uses the raw target-display bounds and requires the
window controller to opt into the corresponding edge-to-edge/cutout behavior.
Insets are resolved once by the window layer; Compose must not subtract them a
second time.

### Nodes

A node is exactly one of:

- a container with `children`; or
- a built-in widget selected by `type`.

A widget cannot also declare authored children. Dynamic child content such as
profile rows is owned by the widget implementation.

Every node may use the common layout, visual, and `when` properties. An `id` is
optional for static content and required for stateful or scrollable content.
Path-derived IDs may be used for simple static nodes, but explicit IDs keep
state stable when an author reorders the file. Explicit IDs are unique across
the complete document. Bound controls still require IDs because their local
gesture state must survive recomposition safely.

## Container layout

Containers declare `children` and use one of three display modes.

### Flex

```yaml
display: flex
flexDirection: column
gap: 4dp
alignItems: stretch
justifyContent: flexStart
children: []
```

Supported v1 properties:

- `flexDirection`: `row` or `column`;
- `gap`, or separate `rowGap` and `columnGap`;
- `alignItems`: `flexStart`, `center`, `flexEnd`, or `stretch`;
- `justifyContent`: `flexStart`, `center`, `flexEnd`, `spaceBetween`,
  `spaceAround`, or `spaceEvenly`;
- child `flex`: a positive grow factor on the main axis.

`flex` and non-start `justifyContent` require a definite parent size on the
main axis because an auto-sized container has no free space to distribute.
V1 only distributes positive free space; it does not implement the complete
CSS flex shrink/basis algorithm. Flex wrapping is deferred. A layout that needs
wrapping should use a grid.

### Grid

```yaml
display: grid
width: 240dp
gridTemplateColumns: [48dp, 1fr, 72dp]
rowGap: 2dp
columnGap: 5dp
children:
  - { type: text, text: FRAME }
  - { type: value, value: frame.summary }
  - { type: graph, value: frame.fps, height: 16dp }
  - type: profileList
    id: profiles
    scope: global
    gridColumn: span 3
    maxHeight: 160dp
    overflowY: auto
```

Supported track values are fixed `dp`/`px`, percentages, and `fr`. A grid using
percentages or `fr` must have a definite content width; reproducing CSS Grid's
intrinsic `auto` track sizing is deliberately outside v1.

The track algorithm is small and deterministic:

1. Resolve fixed and percentage tracks and subtract column gaps.
2. Divide nonnegative remaining width among `fr` tracks by weight.
3. If fixed tracks and gaps already exceed the content width, `fr` tracks
   resolve to zero, content clips at the grid boundary, and the renderer emits
   a diagnostic.
4. Spanning content never changes track sizes. It wraps or clips inside its
   allocated span.

`gridColumn: span N` is the v1 colspan syntax. Children are otherwise placed in
stable row-major order. Hidden children are removed before placement and do not
reserve a cell.

Row spans, named areas, explicit line ranges, dense flow, and subgrid are
deferred.

### Stack

```yaml
display: stack
width: 240dp
height: 120dp
children:
  - type: graph
    value: cpu.loadPercent
    position: absolute
    left: 10%
    top: 8dp
    width: 80%
    height: 40dp
```

`stack` is ClusterTune terminology for an overlapping container and establishes
the containing block itself. Only its direct children may use
`position: absolute`. A stack must have definite dimensions on an axis used by
percentage positioning. Positioned children do not determine the stack's
automatic size.

## Size and spacing

The common geometry properties are:

- `width`, `height`;
- `minWidth`, `maxWidth`, `minHeight`, `maxHeight`;
- `padding`;
- `gap`, `rowGap`, `columnGap` where the display mode supports them;
- `alignSelf` and `justifySelf` where the parent supports them.

Length forms are property-specific:

| Property | Accepted values |
| --- | --- |
| `width`, `height` | `auto`, `dp`, `px`, or `%` |
| min/max dimensions | `dp`, `px`, or `%` |
| `padding`, gaps, borders, radii | `dp` or `px` |
| positioned offsets | signed `dp`, `px`, or `%` |
| grid tracks | `dp`, `px`, `%`, or `fr` |
| `translate` | signed `dp`, `px`, or `%` |

`auto` measures from content and is the default width and height. `fr` is a
share of remaining grid space and is never valid as an ordinary dimension.

Percentage rules are deliberately strict:

- A window percentage uses its selected safe viewport.
- A descendant percentage uses the immediate containing block after padding.
- A percentage needs a definite parent size on the same axis.
- Percentage/automatic-size dependency cycles are validation errors.
- Min/max constraints clamp the resolved size; parent and safe-area limits win.
- If auto-sized window content is larger than the safe viewport, the native
  window is clamped, default `overflowY: clip` applies, and a diagnostic is
  emitted. Authors must add a bounded scrollable region when clipped content
  needs to remain reachable.

A responsive but bounded panel therefore remains straightforward:

```yaml
width: 35%
minWidth: 180dp
maxWidth: 320dp
maxHeight: 70%
```

`padding` uses CSS order but YAML arrays:

| Form | Expansion |
| --- | --- |
| `8dp` | all sides |
| `[8dp, 12dp]` | vertical, horizontal |
| `[8dp, 12dp, 10dp]` | top, horizontal, bottom |
| `[8dp, 12dp, 10dp, 14dp]` | top, right, bottom, left |

Negative dimensions, padding, gaps, borders, and radii are invalid. Signed
values are allowed only for positioned offsets and `translate`.

## Positioning

The position values are:

- `static`: normal flex/grid flow;
- `absolute`: positioned as a direct child of a `stack`;
- `fixed`: positioned inside the selected safe viewport; valid for windows.

Positioned nodes use `top`, `right`, `bottom`, and `left`. Percentages follow the
corresponding containing block: horizontal offsets use its width and vertical
offsets use its height. `right` and `bottom` inset inward from those edges.
Positioned nodes are removed from normal flow.

V1 permits at most one of `left`/`right` and at most one of `top`/`bottom`.
Missing axes use the window defaults or zero for stack children. Authors set
size explicitly or use content-sized `auto`; opposing insets do not stretch a
node. This avoids CSS's over-constraint precedence rules.

Examples:

```yaml
# Fixed distance from the top-right safe edges.
position: fixed
top: 12dp
right: 12dp

# Centered in the safe viewport. Percentage translation is relative to the
# resolved window itself.
position: fixed
left: 50%
top: 50%
translate: [-50%, -50%]
```

Only translation is supported; this is not a general transform language. Its
percentage values use the positioned node's own resolved border box. Window
translation is folded into native `LayoutParams` placement, and child
translation is folded into Compose layout placement; it is never a draw-only
graphics transform. Rotation, scale, skew, and perspective are deferred.

Content-sized right/bottom/translated windows are repositioned whenever their
measured size changes, including changes caused by conditions, telemetry text,
font scale, or profile expansion.

## Scrolling

V1 supports vertical `overflowY` values:

- `clip`: clip overflowing content;
- `auto`: scroll when content exceeds the viewport.

A scrollable node must have a finite `height` or `maxHeight`. Unbounded and
nested same-axis scrolling are validation errors.

`profileList` is always virtualized with stable profile IDs. Its viewport, not
the total height of every profile, participates in window measurement. Scroll
state is stored by the document-wide node ID and survives a temporary
`when: false` removal during the HUD session.

## Conditional display

`when` replaces the more verbose `visibleWhen`. If it is absent, the node is
shown. If it evaluates false, the node and its subtree are not composed,
measured, hit-tested, or assigned a grid cell. This matches CSS `display: none`,
not `visibility: hidden`.

Most conditions are one Boolean path:

```yaml
when: autoTune.active
when: device.fanProfile.available
when: flags.profilesOpen
```

Compound conditions use a small typed grammar:

```yaml
when:
  all:
    - profiles.available
    - not: autoTune.active

when:
  any:
    - device.oemProfile.available
    - device.fanProfile.available

when:
  equals: [device.family, ayn]
```

The complete v1 grammar is:

```text
Condition = true | false
          | booleanReference
          | { all: [Condition, ...] }
          | { any: [Condition, ...] }
          | { not: Condition }
          | { equals: [valueReference, literal] }
```

Rules:

- A bare reference must be registered as Boolean. There is no truthiness.
- `all` and `any` must contain at least one condition.
- `equals` uses strict types. Its left operand is a condition-eligible reference
  and its right operand is a literal.
- A statically unknown reference is a validation error.
- A known value that is temporarily unavailable evaluates as unknown; final
  unknown is treated as false. `not unknown` remains unknown, so a missing value
  cannot accidentally reveal a control.
- `all` returns false if any child is false, true if every child is true, and
  unknown otherwise. `any` returns true if any child is true, false if every
  child is false, and unknown otherwise.
- Conditions use one immutable runtime snapshot per render.
- V1 condition-eligible references are `flags.*`, every `.available` reference,
  `autoTune.active`, `foregroundApp.available`, `profiles.available`,
  `device.family`, `layout.compact`, and `layout.landscape`. Live numeric
  telemetry is not condition-eligible, even through `equals`, because it could
  reflow the HUD every sample. The registry should expose a stable derived
  Boolean fact if a later use case needs one.

There is no expression-string syntax, interpolation, regex, or executable code.

## Built-in widgets

### `metric`

Displays a label, formatted value, and optional history graph using the standard
compact HUD row geometry.

```yaml
type: metric
label: CPU
value: cpu.summary
graph: cpu.loadPercent
graphWidth: 72dp
graphHeight: 16dp
```

### `text`

Displays literal text. It never interprets templates or markup.

```yaml
type: text
text: Performance
```

### `value`

Displays one registered value.

```yaml
type: value
value: frame.fps
precision: 1
unit: fps
```

Formatting is intentionally finite: `precision`, `unit`, `prefix`, and
`suffix`. Composite presentation such as the current frame summary is provided
through a registered read-only value such as `frame.summary`; v1 does not add a
template or expression language.

### `graph`

Displays history for one registered numeric series.

```yaml
type: graph
value: gpu.busyPercent
width: 72dp
height: 16dp
min: 0
max: 100
samples: 30
color: "#ef5350"
```

`min` and `max` accept a number or a registered numeric reference such as
`display.refreshFps`. Missing samples create gaps. Collection cadence remains
owned by telemetry, not by the layout.

### `slider`

Displays an allowlisted numeric control.

```yaml
type: slider
id: autoTuneTarget
label: AUTO
value: autoTune.targetFps
min: 1
max: display.refreshFps
step: 1
width: 72dp
height: 48dp
trackHeight: 16dp
```

`height` is the real layout and hit box inside the native window;
`trackHeight` is only the compact visible track. The renderer requires at least
a 48 dp interaction target and standard progress semantics. A layout cannot
bind a slider to an arbitrary setting; v1 initially allows only
`autoTune.targetFps`.

### `toggle`

Writes an allowlisted Boolean binding. The first implementation only needs
layout-local flags:

```yaml
type: toggle
id: profilesToggle
label: Profiles
value: flags.profilesOpen
```

Layout-local flags are session state initialized by the root `flags` map. Their
persistence can be added later without changing the syntax.

### `profileList`

Displays compatible profiles as a virtualized, single-selection list.

```yaml
type: profileList
id: profiles
scope: global
maxHeight: 180dp
overflowY: auto
```

Supported scopes are:

- `global`: applies the selected normal profile;
- `foregroundApp`: changes the assignment for the currently verified app.

The widget obtains profiles, compatibility, active state, and actions from
ClusterTune. The layout cannot provide item actions or arbitrary row templates.
Every selection is revalidated at activation time. If a foreground target is
missing or changed, an app-scoped action fails closed.

Selecting the current row is a no-op. Removing an assignment or selecting a
neutral profile requires a separate product decision and is not implicit in v1.

### `divider` and `spacer`

These provide simple visual separation without empty container tricks:

```yaml
- { type: divider }
- { type: spacer, height: 6dp }
```

## Runtime value registry

Layout references are stable public names, not Kotlin property paths. The
registry definition records the type, optionality, condition eligibility, and
write capability for each reference; none of these are inferred from a runtime
sample. The initial registry should include:

| Reference | Type | Meaning |
| --- | --- | --- |
| `frame.fps` | number | Current trusted frame rate. |
| `frame.p95Ms` | number | Current trusted P95 frame time. |
| `frame.summary` | string | Standard combined frame summary. |
| `cpu.loadPercent` | number | Current displayed CPU load. |
| `cpu.clockMHz` | number | Current displayed CPU clock. |
| `cpu.summary` | string | Standard CPU summary. |
| `gpu.busyPercent` | number | Current GPU utilization. |
| `gpu.clockMHz` | number | Current GPU clock. |
| `gpu.summary` | string | Standard GPU summary. |
| `thermal.maxCelsius` | number | Maximum relevant temperature. |
| `device.oemProfile` | string | Current supported OEM performance mode. |
| `device.fanProfile` | string | Current supported fan mode. |
| `device.family` | string | Normalized supported device family. |
| `clusterTune.profile` | string | Current effective ClusterTune profile. |
| `autoTune.active` | Boolean | Whether Auto Tune is the current mode. |
| `autoTune.targetFps` | number, writable | Current Auto Tune target. |
| `display.refreshFps` | number | Current target-display refresh rate. |
| `foregroundApp.available` | Boolean | Whether an app target is currently verified. |
| `profiles.available` | Boolean | Whether compatible profiles exist. |
| `profiles.count` | number | Compatible profile count. |
| `layout.compact` | Boolean | Stable root safe-width breakpoint. |
| `layout.landscape` | Boolean | Current target display orientation. |

Every base value reference exposes one derived `<reference>.available`, for
example `thermal.maxCelsius.available`. Availability references do not
recursively expose another `.available`. The derived value is always true for
mandatory state and reflects current capability/data availability for optional
state. Missing read-only values render the widget's documented unavailable
state unless `when` removes it.

History is accessed by graphing the numeric value reference; authors do not
address internal history buffers directly.

## Visual properties

V1 should keep formatting finite and flat:

- `color`, `backgroundColor`, and `opacity`;
- `fontFamily`, `fontSize`, `fontWeight`, `lineHeight`, and `textAlign`;
- `borderColor`, `borderWidth`, and `borderRadius`;
- widget-specific graph colors and line widths.

Colors accept `#rrggbb`, `#rrggbbaa`, or a root theme reference such as
`theme.accentColor`.

Theme typography and colors are defaults. `color`, `fontFamily`, `fontSize`,
`fontWeight`, `lineHeight`, and `textAlign` inherit down the authored tree;
other visual and all layout properties do not. A child may override an inherited
value. This is direct parent inheritance only—there are no classes, selectors,
specificity rules, or arbitrary custom properties in v1. `opacity` is a content
effect and is not accepted on a top-level window; native window alpha remains
controlled by the renderer so an authored style cannot alter Android's
touch-security behavior.

## Validation and failure behavior

Validation happens before a document can replace the active layout:

1. Parse with a safe YAML 1.2 core schema. Reject duplicate keys, custom tags,
   merge keys, aliases, non-finite numbers, and excessive input size or depth.
2. Validate structure, known properties, enum values, and unique IDs.
3. Validate state references and required value types.
4. Validate parent-specific properties such as grid spans and flex factors.
5. Reject percentage/auto cycles, unbounded scroll regions, and invalid absolute
   containing blocks.
6. Validate platform limits, node limits, and interaction constraints.

Diagnostics should contain a stable code, line and column, and YAML path:

```text
windows[0].children[3].maxHeigth:
unknown property; did you mean maxHeight?
```

Errors keep the last known-good layout active. If none exists, ClusterTune uses
the bundled default. Invalid widgets are never silently dropped because doing
so could hide controls or change the native window's touch region.

Runtime data becoming unavailable is not a schema error. Conditions fail
closed, read-only widgets show their unavailable state, and actions revalidate
current compatibility immediately before mutation.

## Android window and input invariants

The schema must not hide Android's window-level input behavior:

- Each top-level HUD panel maps to one tight native overlay window.
- For a touchable panel, touches pass through only outside that window's
  rectangular bounds.
- Transparent gaps and rounded corners inside the rectangle are still part of
  the native input region.
- Touchability is recalculated from currently composed interactive descendants.
  Hiding the final slider, toggle, or profile list must update the native window
  flags rather than leave an invisible interactive rectangle.
- Read-only panels should request a non-touchable window, but Android 12+
  security rules can still drop cross-application touches through an untrusted
  overlay depending on window alpha and overlapping overlays. Pass-through is
  therefore a device-tested behavior, not guaranteed by this schema; the
  implementation must enforce an accepted opacity/overlap policy.
- Panels with visible interactive descendants remain touchable but non-focusable
  unless a future interaction explicitly requires focus.
- A percentage-sized window intentionally creates a larger input region.
- Windows target one display in v1. They are attached in document order, so a
  later window is visually and interactively above an earlier overlapping
  window. Overlap diagnostics should encourage authors to avoid ambiguous
  interactive regions.
- The resolver uses current display density, safe insets, rotation, and layout
  font scale, then clamps the final rectangle. `left` and `right` are physical
  in v1; logical `start`/`end` placement is deferred.
- The Compose view and WindowManager must use the same display-bound window
  context. Windows are recreated or closed when their target display changes or
  disappears.
- Logical schema values are persisted; resolved pixels are never persisted.

The author cannot configure raw Android window flags, focus policy,
`pointerEvents`, or input passthrough.

## Versioning

- `schemaVersion` is a required exact format integer.
- Defaults, unit meanings, percentage bases, condition behavior, and action
  behavior are part of the versioned contract.
- Any new widget, property, condition operator, or runtime reference that an
  older renderer cannot understand increments the version, even when the
  change is additive.
- Future versions are rejected rather than guessed.
- Renaming a registered runtime reference is a breaking change.
- Migrations are explicit and rewrite the document; aliases do not accumulate
  permanently.
- Version 1 is enabled for user-authored files only after all features described
  as v1 in this document are implemented. Internal development milestones may
  render a subset of the bundled document, but must not claim general v1
  compatibility.

## Implementation sequence

No implementation is part of this document change. When work begins, use these
small vertical stages:

### 1. Typed default, no user files

- Add the document model, normalizer, value registry, and validation tests.
- Express the existing HUD as the bundled default document.
- Render that typed default with pixel, semantics, and behavior parity.
- Keep the existing persisted settings and window behavior unchanged.

### 2. Complete read-only layout engine

- Add flex, definite-width grid, stack, sizing, conditions, and scroll behavior
  behind internal fixtures.
- Add diagnostics and resolver tests for every valid v1 layout primitive.
- Keep the bundled typed document as the only active source during this stage;
  do not expose a partial user-authored v1 format.

### 3. Trusted interaction

- Add the compact Auto Tune slider and layout-local toggles.
- Add the bounded, virtualized profile list.
- Route actions through current stale-target, compatibility, and atomic update
  checks; never through layout-provided callbacks.

### 4. Responsive and multi-window layout

- Add safe-area percentage resolution, absolute stack children, and multiple
  tight windows.
- Re-resolve on rotation, density, inset, and target-display changes.
- Verify outside-window touches continue reaching the underlying game.
- Add the YAML 1.2 decoder, last-known-good storage, full v1 conformance tests,
  and only then enable imported/user-authored v1 layouts.

### 5. Editing and interchange

- Add diagnostics plus an in-app preview/reset/editor workflow only after the
  schema and renderer stabilize.
- Export/import this canonical format.
- If useful, add a separate best-effort MangoHud Next importer for the safe
  overlapping subset. Never make MangoHud YAML the persisted source of truth.

Each stage should preserve the bundled default and may land independently as
internal implementation work. User-authored v1 activation remains gated on the
stage 4 conformance boundary.

## Explicitly outside v1

- CSS selectors, cascade, specificity, and general custom properties;
- arbitrary expressions, interpolation, templates, loops, or generic repeats;
- scripts, shell commands, intents, network resources, or generic event handlers;
- arbitrary writable settings or user-defined actions;
- `calc()`, `min()`, `max()`, `clamp()`, viewport units, and font-relative
  geometry units;
- margins, aspect-ratio sizing, intrinsic grid tracks, and general
  `position: relative` containing blocks;
- sticky positioning, unrestricted overflow, arbitrary transforms, filters,
  or animations;
- flex wrapping, row spans, named grid areas, subgrid, and nested scrolling;
- remotely loaded fonts, images, or widgets;
- raw Android window flags, focus control, or pointer-pass-through settings;
- arbitrary telemetry sampling cadence or history-buffer ownership.

These exclusions keep the implementation testable and safe. New requirements
should first be expressed as a typed widget, value, condition, or layout
capability rather than as a general scripting feature.

## References

- [Android Compose modifiers](https://developer.android.com/develop/ui/compose/modifiers)
- [Android adaptive layout guidance](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes)
- [Android `WindowManager.LayoutParams`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams.html)
- [MangoHud Next server configuration](https://github.com/flightlessmango/MangoHud/blob/master/mangohud-next/server/README.md)
