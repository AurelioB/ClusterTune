# ClusterTune

ClusterTune is an Android utility for tuning CPU frequency limits on supported handheld devices. It lets you adjust CPU clusters, save performance profiles, and access quick controls from an Android Quick Settings tile.

The app has been tested with the AYN Odin 3, but should be compatible with other AYN and Retroid devices.

ClusterTune does not require Magisk or user-granted root access. It relies on the device's built-in PServer service, where available.

## Features

- Tune CPU clusters with per-cluster frequency sliders.
- View the currently applied frequency cap for each cluster.
- Save, edit, delete, reorder, import, and export profiles.
- Use bundled profiles for supported devices.
- Reapply the last profile on boot.
- Quick Settings tile for fast access.
- Customizable quick tile behavior

## Requirements

- Android 12+ (`minSdk 31`).
- A compatible handheld with the PServer service, such as supported AYN and Retroid devices.

This app changes CPU frequency limits. Use it only if you understand the risk of changing performance and thermal behavior on your device.

## Build

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run both:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

## Project Structure

```text
app/src/main/java/com/aure/clustertune/
  data/       detection, storage, bundled profiles, repository
  model/      app state and profile models
  root/       PServer access and command execution
  tile/       Quick Settings tile and add-tile prompt
  ui/         Compose screens, dialogs, settings, theme
  boot/       boot completed receiver

app/src/main/assets/bundled_profiles/
  <SoC model>.json
```

## Bundled Profiles

Bundled profiles are stored as SoC-specific JSON files:

```text
app/src/main/assets/bundled_profiles/CQ8725S.json
```

The filename should match the detected SoC model, such as `ro.soc.model`.

Example:

```json
{
  "schemaVersion": 1,
  "socModel": "CQ8725S",
  "profiles": [
    {
      "id": "bundled_cq8725s_small",
      "name": "Small Underclock",
      "maxFrequencies": {
        "0": 2745600,
        "6": 3072000
      }
    }
  ]
}
```

Exported profiles follow the same schema.

## Notes For Contributors

- UI refers to cpufreq policies as CPU clusters, but internal code keeps `policy` naming because it matches Linux/sysfs terminology.
- ClusterTune uses the device's PServer service to read and write protected CPU frequency controls. It does not ask the user for root access.

## License and Attribution

ClusterTune is distributed under the terms of the GNU General Public License v2.0.

The RootExec / PServer command execution code in this project is based on code from
[O2P Tweaks](https://github.com/FeralAI/o2ptweaks.app) by FeralAI, which is also
licensed under the GNU General Public License v2.0.