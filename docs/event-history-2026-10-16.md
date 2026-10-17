# Event history and text sharing — October 16, 2026

History now includes Profile, App switch, Sleep, Wake, Boot, Auto Tune, Service and Error entries.
Successful profile changes retain their reason. App entries cover assigned-app changes, restoration
when no assigned app remains, and an Auto Tune assignment that is inactive because Auto Tune is
disabled. Switching between unassigned apps without a tuning consequence produces no new entry.
Screen broadcasts, boot/update callbacks, connection callbacks and existing profile operations
supply the other events. FPS samples, heartbeats and individual Auto Tune clock steps are not logged.

## Resource limits

- There is no logging worker, timer, polling loop or additional hardware read.
- History keeps its existing configurable entry limit: 100 by default, at most 1,000.
- New titles are bounded to 160 characters and details to 800 characters.
- Identical failures are suppressed until recovery or a changed cause. The in-memory suppression
  table holds at most 32 keys, with no expiry timer.
- History joins the presentation flow after hardware state is read. Appending an event updates the
  view without restarting hardware reads. A unit check exercises repeated History-only updates.
- Export creates one cache file only when Share is pressed. It runs off the UI thread and replaces
  that same file rather than accumulating exports.

Diagnostic entries do not change effective-profile state and cannot supply a restored profile's
identity. Old saved History entries remain compatible. The text export contains retained entries,
timestamps, event types, reasons, app version, device and Android version.

## Share and screenshots

History's Share button creates `clustertune-history.txt` and opens Android's native share sheet.
It uses the existing FileProvider with a dedicated `history/` cache path and a temporary read grant;
no new Android permission is requested. The button is disabled when History is empty.

These are real device screenshots with temporary, clearly marked example entries. The examples
were removed afterward; they are not claims that all of those events happened during the capture.

- [History, first entries](research/history-2026-10-16/history-all-event-types.png)
- [History, remaining entries](research/history-2026-10-16/history-all-event-types-bottom.png)
- [Android share sheet showing the text file](research/history-2026-10-16/history-android-share-sheet.png)
- [Example exported text](research/history-2026-10-16/history-example-export.txt)
- [Device UI and share test](research/history-2026-10-16/ui-and-share-runner.log)

## Event ordering found during validation

The initial device regressions retained failures for rapid screen swaps, a wake with sleep profiles
disabled, and one CPU ceiling verification after reconnect. Both PServer and Root initial results
are retained below. Focused repeats passed without a tuning-engine change; a passing repeat does
not establish the cause of every initial failure.

Inspection found that wake publishes an interactive empty snapshot before its fresh asynchronous
task query returns. That unknown state must not restore the normal profile. Snapshots now explicitly
mark whether app information is available, and the coordinator waits for an actual observation.
A direct device test checks that unknown information preserves an assigned app's profile, while a
confirmed empty observation restores the normal profile. Pending profile decisions also conflate
to the latest app/configuration state while a previous apply is finishing, avoiding stale queued
switches. Neither change adds a timer or hardware check.

The CPU verification failure showed the requested ceiling and the system's Stock ceiling in the
new Error entry. The logging change does not repair that underlying intermittent hardware mismatch.

- [Initial PServer results](research/history-2026-10-16/pserver-first.json)
- [Initial Root results](research/history-2026-10-16/root-first.json)
- [Focused PServer repeat](research/history-2026-10-16/pserver-retry.json)
- [Direct unknown-wake check](research/history-2026-10-16/unknown-wake-runner.log)

## Validation

The JVM suite passed 570 tests. New coverage checks old/new History storage compatibility, all event
types, error suppression and its memory bound, diagnostic/profile identity separation, presentation
updates without hardware refresh, and text export contents/order. The device fixture verifies
one retained entry after repeated identical failures, unchanged effective-profile identity,
empty-History Share state, the readable content URI/MIME/read grant, and the actual chooser with
`clustertune-history.txt` visible. Debug app/instrumentation builds and lint passed.

After the event-ordering changes, all 16 PServer and 11 Root scenarios passed. These cover forward/
reverse app switches, both screens and crossed CPU/GPU requests, independently replaced screens,
screen swaps, sleep disabled/enabled, repeated sleep/wake, and reconnect with simultaneous clock
reads. The PServer run additionally covers inactive Auto Tune assignments, transitions between
Auto Tune/fixed/unassigned apps, different FPS targets, manual override, and disabling active tuning.
The direct unknown-wake check and the real share-sheet fixture also passed. The original CPU
verification failure remains retained as an unresolved intermittent observation, rather than
being erased by the successful final runs.

- [Final PServer results](research/history-2026-10-16/pserver-final.json)
- [Final Root results](research/history-2026-10-16/root-final.json)
- [Validation summary](research/history-2026-10-16/validation.json)

## Restoration

The app was stopped before restoring its pre-test saved data. All app settings matched exactly;
profiles, all three assignments, normal CPU/GPU values and selected Large Underclock were preserved.
Only the effective-profile record and History changed through deliberate cleanup and actual startup
connection events. Temporary example entries and screenshot/export files were removed from the device.

Final CPU maxima were 1,459,200 / 1,785,600 / 1,843,200 kHz, GPU was 680,000,000 Hz, and minimum
permissions were `0440`. Both physical displays were verified at 120 Hz. Auto Tune and sleep profiles
were off, PServer was selected, and all four letter apps were stopped. Screen timeout, OEM mode,
display preferences and debugging settings matched the pre-test state. See the
[restoration check](research/history-2026-10-16/restoration.json).
