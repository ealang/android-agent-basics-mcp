# Samsung Clock (com.sec.android.app.clockpackage)

Verified on device 2026-09-06.

## What works

The public alarm-clock actions, all on activities guarded by
`com.android.alarm.permission.SET_ALARM` (normal-level; phone-mcp holds it):

- `android.intent.action.SET_TIMER` — verified end to end (2026-10-01):
  the timer appears and counts down. `SHOW_TIMERS` also resolves.
- `android.intent.action.DISMISS_TIMER` resolves and is accepted, but
  **does nothing**: the timer keeps running (verified 2026-10-01,
  repeatedly, by label search). Do not rely on it.
- `android.intent.action.SET_ALARM`, `SHOW_ALARMS`, `DISMISS_ALARM`,
  `SNOOZE_ALARM` resolve to `AlarmCTSHandleActivity`.
- Exported receivers include `com.samsung.sec.android.clockpackage.alarm.ALARM_DISMISS`
  and `ALARM_SNOOZE` (see the intent sweep) — untested.

## Stopwatch: unreachable, do not re-probe

There is no programmatic stopwatch on this device — and no standard one
anywhere in Android.

- The platform defines **no stopwatch intent at all**: `AlarmClock`
  stops at alarms and timers (`javap` on the SDK confirms; so does the
  common-intents documentation). AOSP/Lineage DeskClock export nothing
  for the stopwatch either — their `START_STOPWATCH`-style actions are
  internal, on an unexported service. A tool built on a stopwatch
  action would rest on a contract that does not exist, which is why no
  first-class `start_stopwatch` was added (decided with the owner,
  2026-09-06).
- Samsung's private `com.sec.android.app.clockpackage.INTENT_STOPWATCH`
  is handled only by `ClockPackageExecutable`, an internal entry activity
  (Bixby/Routines surface) guarded by
  `com.sec.android.app.clockpackage.permission.ACCESS_WCCITY`. That
  permission is **signature-level**: declaring it in our manifest was
  tried and the grant never arrives — explicit-component launch still
  throws `SecurityException` naming it. Only Samsung-signed apps can call
  it. The guard is a blanket one on the internal entry point, not a
  stopwatch-specific policy.
- Two traps met on the way, both now handled: the filter lacks
  `CATEGORY_DEFAULT`, so an implicit send fails with
  `ActivityNotFoundException` even though `intent_resolve` lists a
  handler (resolve does not require DEFAULT; `startActivity` does); and
  before fcb7429 the resulting exception dropped the HTTP connection
  instead of returning `FAILED:`.

Best available: open the Clock app (launcher intent or
`clockpackage://ClockMain` VIEW deep link, unguarded) and the user taps
Stopwatch → Start.
