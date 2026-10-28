---
name: android-samsung-clock
description: Samsung Clock, the stock clock on Samsung phones. Set a timer or alarm and open the timer or alarm list. Dismiss and snooze do nothing; the stopwatch is unreachable.
verified: 2026-10-01
---

# Samsung Clock

Package `com.sec.android.app.clockpackage`. Timers and alarms are set
through the standard AlarmClock intents and the lists open on screen.
Dismiss and snooze are accepted and do nothing. The stopwatch cannot be
reached at all; do not re-probe it.

## Set a timer

```
send_intent
{
  "action": "android.intent.action.SET_TIMER",
  "package": "com.sec.android.app.clockpackage",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.LENGTH", "type": "int", "value": <seconds>},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "<label>"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

The timer appears in the Clock app and counts down. Sending twice makes
two timers; nothing can list or read them, so never resend on doubt.

## Set an alarm

```
send_intent
{
  "action": "android.intent.action.SET_ALARM",
  "package": "com.sec.android.app.clockpackage",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.HOUR", "type": "int", "value": <hour>},
    {"key": "android.intent.extra.alarm.MINUTES", "type": "int", "value": <minutes>},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "<label>"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

Only ever creates: a new time makes a second alarm. For a repeating
alarm add `android.intent.extra.alarm.DAYS` as an `int_arraylist` of
`java.util.Calendar` day constants (Sunday = 1).

## Show the lists

`android.intent.action.SHOW_TIMERS` and `SHOW_ALARMS`, same shape with
no extras, open the Clock app at the list.

## Does nothing

`DISMISS_TIMER`, `DISMISS_ALARM` and `SNOOZE_ALARM` resolve to the app's
handler activity and are accepted; the timer keeps running. A timer or
alarm, once set, is the user's to stop.

## Stopwatch: unreachable

Android defines no stopwatch intent, and Samsung's private
`com.sec.android.app.clockpackage.INTENT_STOPWATCH` is handled by an
internal entry activity guarded by a signature-level permission, so an
explicit launch throws `SecurityException` and no manifest line changes
that. The best available is opening the Clock app for the user to tap
Stopwatch:

```
send_intent
{"action": "android.intent.action.VIEW", "data": "clockpackage://ClockMain", "mode": "activity"}
```
