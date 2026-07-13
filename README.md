<h1>
  <img src="docs/app-icon.svg" alt="" width="48" align="left">
  ClusterTune
</h1>

![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/AurelioB/cluster-tune/total)
![GitHub Release](https://img.shields.io/github/v/release/AurelioB/cluster-tune)

<a href='https://ko-fi.com/J3J518XVKR' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>

ClusterTune is an Android utility for tuning CPU frequency limits on supported handheld devices. It lets you adjust CPU clusters, save performance profiles, and access quick controls from an Android Quick Settings tile.

The app has been tested with the AYN Odin 3, but should be compatible with other AYN and Retroid devices.

ClusterTune uses a device's built-in PServer service where available, so supported handhelds do not require Magisk or user-granted root access. A standard `su` root shell is also supported as a fallback on rooted devices.

> [!WARNING]
> ClusterTune changes CPU frequency limits. This may affect device stability, thermals, battery life, and performance, and I cannot guarantee that it is safe for your hardware or beneficial for your use case. Use it only if you understand what CPU frequency limits do and are comfortable accepting the risk.

## Why use this?

ClusterTune underclocks by setting lower maximum CPU frequencies. It does not undervolt the CPU, and it is not an adaptive governor; it simply caps how fast each CPU cluster is allowed to run.

Lower CPU frequency caps can reduce power draw, which may help lower temperatures, quiet the fan, and extend battery life. In some games, reducing CPU power and heat may also leave more thermal or power headroom for the GPU, especially when the game is GPU-bound.

## Screenshots

| Main app | Profile editor |
| --- | --- |
| <img src="docs/screenshots/main-app.png" alt="ClusterTune main app view" width="420"> | <img src="docs/screenshots/profile-editor.png" alt="ClusterTune profile editor dialog" width="420"> |

| Quick Settings tile dialog | Settings |
| --- | --- |
| <img src="docs/screenshots/quick-settings-dialog.png" alt="ClusterTune Quick Settings tile dialog" width="420"> | <img src="docs/screenshots/settings.png" alt="ClusterTune settings view" width="420"> |

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
- A compatible handheld with a supported PServer service, or a rooted Android device with a working `su` shell.

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
  root/       privileged execution and sysfs access
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
- Automatic execution detection prefers direct PServer access, then PServer compatibility mode, and finally a standard root shell.
- Shizuku support is experimental and manual-only because a granted Shizuku permission does not necessarily provide access to protected CPU controls.

## License and Attribution

ClusterTune is distributed under the terms of the GNU General Public License v2.0.

The RootExec / PServer command execution code in this project is based on code from
[O2P Tweaks](https://github.com/FeralAI/o2ptweaks.app) by FeralAI, which is also
licensed under the GNU General Public License v2.0.

ClusterTune was inspired by the Odin 3 underclocking scripts published in
[TheOldTaylor/Odin3-CPU-Underclock](https://github.com/TheOldTaylor/Odin3-CPU-Underclock).
The original underclocking idea was shared by Reddit users u/twoohfive205 and
u/JoaozaoS in
[this r/OdinHandheld comment](https://www.reddit.com/r/OdinHandheld/comments/1snp9xd/comment/ogphgmb/).

## AI Assistance Disclosure

I used AI assistance while building this project. Software development is my day job, and I reviewed the code throughout the process. I understand how the app works and what it does.
