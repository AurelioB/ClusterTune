# Profile reset tests on the Thor — October 15, 2026

These checks used the physical AYN Thor running Android 13, with Auto Tune off. The starting
profile was Large Underclock: CPU limits of 1.4592 / 1.7856 / 1.8432 GHz and a 680 MHz GPU limit.
Both PServer and the app's real-root connection were exercised.

## Fixed profiles, automation, and failed applies

The 13-scenario matrix passed in 398.896 seconds. It recorded 356 clock samples and the profile
switch History for each scenario.

| Scenario | Observed result |
| --- | --- |
| ClusterTune open for 60 seconds, using PServer | Underclock held |
| ClusterTune in the background for 90 seconds, using PServer | Underclock held |
| Three screen off/on cycles with sleep profiles disabled | Underclock held |
| Stock baseline, with an underclock assigned to Settings | Underclock while Settings was visible; Stock on exit, as configured |
| Underclock baseline, with Stock assigned to Settings | Stock while Settings was visible; underclock on exit, as configured |
| Stock baseline, with an underclock sleep profile | Underclock asleep; Stock on wake, as configured |
| Underclock baseline, with a Stock sleep profile | Stock asleep; underclock on wake, as configured |
| Invalid CPU request | Rejected; previous underclock held |
| Deliberately changed CPU minimum permissions | Apply rejected with the permission error; previous underclock held |
| Privileged service stopped normally and reconnected | Underclock held through the stop and reconnect |
| Privileged service killed and reconnected | Underclock held through the kill and reconnect |
| Foreground for 45 seconds, using real root | Underclock held |
| Background for 60 seconds, using real root | Underclock held |

The permission-error case deliberately used unfamiliar permissions. It reproduced the error
path shown in the report; it did not reproduce the original device's unknown cause. The error
alone did not set the CPU limits to Stock.

The four configured app/sleep transitions recorded their expected reasons in History, including
“No assigned app visible,” “Device sleep,” and “Device wake.” A return to Stock can therefore be
intentional when Stock is the saved normal or assigned profile, even with Auto Tune off.

## Firmware performance modes

Three more scenarios changed the Thor's documented performance-mode setting, using real root
to operate that setting while ClusterTune's host continued to use PServer:

- Mode 1 with the underclock protected: 12 seconds, CPU limits and minimums unchanged.
- Mode 2 with the underclock protected: 12 seconds, CPU limits and minimums unchanged.
- Mode 2 after stopping the privileged service: 15 seconds, CPU limits unchanged. Once the
  service released its minimum-file protection, firmware raised CPU minimums to the existing
  underclock limits. It did not raise the limits to Stock.

The original firmware mode, 0, was restored. No vendor service was disabled, and no undocumented
mode value was used. These three scenarios completed in 48.097 seconds including setup/cleanup.

## Stopping the app

A separate fixture applied the underclock, confirmed a live privileged service and protected
minimum files, then waited. An external controller force-stopped ClusterTune while that fixture
was still running. This deliberately terminated the instrumentation; it was not an unexpected
test crash.

The recorded clock limits stayed at Large Underclock. The privileged service exited and released
its minimum-file permissions. Firmware subsequently raised CPU minimums, while the CPU limits
still stayed at the underclock. Samples include their actual elapsed times because collecting
the process list takes additional time on this device.

## Evidence and restoration

- [13-scenario samples and History](research/profile-resets/matrix-2026-10-15.json)
- [Firmware-mode samples](research/profile-resets/oem-modes-2026-10-15.json)
- [App force-stop samples](research/profile-resets/client-death-2026-10-15.json)
- [Restored settings and final device state](research/profile-resets/restoration-2026-10-15.json)

The saved settings, all three app assignments, user profiles, normal profile, and GPU limit were
restored. Auto Tune and sleep profiles were off before the tests and remain off. The screen
timeout was left at its original ten minutes. Large Underclock was reapplied through the normal
app after the process-death tests to restore minimum-file protection.

Only device-test fixtures and this evidence were added for this follow-up. The debug test build
and lint passed, with zero lint errors and 56 existing warnings. Production code was unchanged
during this matrix; the preceding production validation is recorded in
[the session-timeout report](profile-reset-recovery-2026-10-15.md).

These 17 scenarios found no unexplained reset to Stock. They do not rule out a rare event during
a long gaming session, another firmware version, a reboot, or a thermal event. The original
report still needs logs from the moment an unexplained reset occurs to identify its writer.
