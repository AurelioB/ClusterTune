# Odin 3 Fan Control Notes

These notes summarize observations from an Odin 3 running the stock AYN/Odin settings stack. They are based on device inspection and controlled tests through ADB/root access, not on official AYN documentation.

## Stock Components

The stock fan curve implementation runs inside the persistent system app process:

```text
com.odin.settings
```

The package is installed as a system app at:

```text
/system/app/OdinSettings/OdinSettings.apk
```

Relevant package details observed:

```text
package: com.odin.settings
shared UID: android.uid.system
app process: com.odin.settings
application: com.ro.settings.application.SettingsApplication
persistent: true
```

The runtime fan controller does not appear to be a separately exported Android service. The APK manifest exposes the fan curve editor activity:

```text
action: action_fan_temp_control_curve_config
activity: com.ro.settings.activity.fan.FanTempControlCurveConfigActivity
```

Internal fan-related class/log names found in the APK/runtime logs include:

```text
FanBase
FanProvider
FanTrigger
FanPoint
```

Runtime logs confirm that `FanBase` runs inside `com.odin.settings` and periodically recalculates Smart fan output:

```text
D/FanBase: mSmartAction temp control curve; result ... result.temperature = 35.59 speedPercentage = 20 smartSpeed = 10000
```

In testing, this calculation repeated roughly every 4 seconds.

## Android Settings

The active fan mode and thermal reference are stored in `Settings.System`:

```text
fan_mode=4
fan_thermal_management_area=GPU
is_quick_set_performance_and_fan_enable=1
```

Observed meaning:

```text
fan_mode=4 -> Smart
```

Other fan mode values exist, but the full mapping still needs to be confirmed. Toggling `fan_mode` away from Smart and back to Smart causes the stock controller to re-apply the currently loaded fan mode logic:

```sh
settings put system fan_mode 3
settings put system fan_mode 4
```

Important: bouncing `fan_mode` alone does not reload an externally edited curve file. `com.odin.settings` appears to cache the curve after reading it.

## Custom Curve Storage

The Smart fan curve is stored in the stock settings app private shared preferences:

```text
/data/user_de/0/com.odin.settings/shared_prefs/config.xml
```

The curve key is:

```text
fan_temp_control_curve_point_key
```

Example current stock-style value:

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="fan_temp_control_curve_point_key">[{&quot;a&quot;:0,&quot;b&quot;:20},{&quot;a&quot;:25,&quot;b&quot;:20},{&quot;a&quot;:45,&quot;b&quot;:20},{&quot;a&quot;:65,&quot;b&quot;:30},{&quot;a&quot;:85,&quot;b&quot;:45},{&quot;a&quot;:105,&quot;b&quot;:60},{&quot;a&quot;:2147483647,&quot;b&quot;:60}]</string>
    <boolean name="fan_temperature_control_curve_unit" value="true" />
</map>
```

Curve point format:

```json
{"a": 45, "b": 20}
```

Observed interpretation:

```text
a -> temperature threshold, apparently Celsius for runtime behavior
b -> fan speed/duty percentage
```

The final point commonly uses:

```text
2147483647
```

This appears to act as a catch-all max temperature endpoint.

## Temperature Unit Flag

The setting:

```xml
<boolean name="fan_temperature_control_curve_unit" value="true" />
```

appears to be a UI-only preference for the stock fan curve editor's Celsius/Fahrenheit toggle.

Evidence:

- The stock fan curve layout contains a `unitSwitch`.
- The UI shows `F` and `C` unit labels.
- The APK contains strings/method names such as `fan_temperature_control_curve_unit`, `unitSwitch`, and `setTemperatureUnit`.
- Temporarily flipping only this boolean from `true` to `false` did not change runtime fan output after reloading Smart mode.

Older `config.xml` snapshots did not include this boolean. It likely gets written lazily after opening or interacting with the stock fan curve editor.

## Runtime Sysfs Nodes

The live fan output is exposed through:

```text
/sys/class/gpio5_pwm2/
```

Observed files:

```text
/sys/class/gpio5_pwm2/state
/sys/class/gpio5_pwm2/duty
/sys/class/gpio5_pwm2/speed
/sys/class/gpio5_pwm2/period
/sys/class/gpio5_pwm2/thermal
/sys/class/gpio5_pwm2/en_backlight
```

Typical values:

```text
state=1
period=50000
duty=10000
speed=4800
thermal=0
```

Observed relationship:

```text
20% fan -> duty=10000 when period=50000
0% fan  -> duty=0, speed=0
```

These nodes were read during testing. Direct writes to GPIO were intentionally avoided during the later fan curve tests.

The stock APK also references other possible fan paths:

```text
/sys/devices/virtual/fan_info/fan_info_data/duty
/sys/devices/virtual/fan_info/fan_info_data/state
/sys/devices/platform/odm/odm:mid_custom/fan
/sys/devices/platform/odm/odm:mid_custom/fan_pwm
```

## Thermal Sources

The active thermal reference setting was:

```text
fan_thermal_management_area=GPU
```

GPU-related thermal zones observed:

```text
/sys/class/thermal/thermal_zone32 type=gpuss-0
/sys/class/thermal/thermal_zone33 type=gpuss-1
/sys/class/thermal/thermal_zone34 type=gpuss-2
/sys/class/thermal/thermal_zone35 type=gpuss-3
/sys/class/thermal/thermal_zone36 type=gpuss-4
/sys/class/thermal/thermal_zone37 type=gpuss-5
/sys/class/thermal/thermal_zone38 type=gpuss-6
/sys/class/thermal/thermal_zone39 type=gpuss-7
```

The exact sensor aggregation used by `FanBase` still needs deeper decompilation. Runtime logs mention:

```text
/sys/devices/virtual/thermal/
```

## Applying External Curve Changes

When editing `config.xml` externally, this sequence worked:

```sh
# Copy updated config.xml into:
# /data/user_de/0/com.odin.settings/shared_prefs/config.xml

kill -9 "$(pidof com.odin.settings)"
settings put system fan_mode 3
settings put system fan_mode 4
```

Why both steps matter:

- `com.odin.settings` appears to cache the curve file.
- Restarting `com.odin.settings` reloads the XML.
- Bouncing `fan_mode` makes the runtime fan controller apply/recalculate Smart mode.

The broadcast below was observed, but sending it manually did not apply externally edited curve data:

```text
settings.notify.update.fan.ui.action
```

## Curve Behavior Findings

The stock UI limits the lower fan speed to 20%, but the backend accepts 0%.

Tested curve:

```text
0C   -> 0%
25C  -> 0%
45C  -> 0%
65C  -> 30%
85C  -> 45%
105C -> 60%
max  -> 60%
```

Observed live result below 45C:

```text
duty=0
speed=0
```

This suggests the 20% stock UI lower bound is probably a conservative UI/product limit rather than a hard runtime limitation.

Duplicate temperature points are not reliable as a true vertical step. This test did not trigger the expected jump:

```text
35C -> 0%
35C -> 20%
```

But this non-duplicate test did:

```text
35C -> 20%
```

Observed live result:

```text
duty=10000
speed=4800
```

This suggests the stock curve implementation may collapse duplicate temperature points or resolve them using the first matching point. A near-vertical step should be approximated with adjacent thresholds instead:

```text
49C -> 0%
50C -> 20%
```

## Safety Notes

Fan-off curves are technically possible, but they should be treated as safety-sensitive:

- Small fans may not start reliably below some PWM threshold.
- `0%` means fan off, not quiet fan.
- Low or fan-off curves can allow heat to build quickly under load.
- The stock UI likely enforces 20% minimum to avoid stall, startup, and support issues.

Any app support for custom fan curves should make fan-off behavior explicit and should avoid silently lowering user cooling.

## Open Questions

- Full mapping of all `fan_mode` values.
- Exact thermal source selection logic for `fan_thermal_management_area`.
- Whether CPU/GPU thermal reference changes require only `Settings.System` updates or also process reloads.
- Whether Retroid devices use the same package, preference key, and sysfs paths.
- Whether `FanBase`, `FanProvider`, and `FanTrigger` can be decompiled enough to model interpolation precisely.
