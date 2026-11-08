---
name: android-samsung-clock
description: Samsung Clock, the stock clock on Samsung phones. Set a timer or alarm, one-time or repeating, and silence a ringing timer. Alarms cannot be dismissed, snoozed for a chosen duration or cancelled, a running timer cannot be cancelled, and the stopwatch is unreachable.
verified: 2026-11-08
---

# Samsung Clock

Package `com.sec.android.app.clockpackage`. Timers and alarms are set
through the standard AlarmClock intents. A ringing timer can be
silenced. A ringing alarm cannot be dismissed, and snoozing it ignores
the requested duration; nothing reaches a timer still counting or an
alarm not yet ringing, so neither can be cancelled. The stopwatch cannot be
reached at all.

| Capability | Samsung Clock |
|---|---|
| Screen off | ✅ everything below |
| Set a timer | ✅ seconds and a label, no UI · each call adds a timer |
| Set an alarm | ✅ hour, minutes and a label, no UI · a new time adds a new alarm |
| Repeating alarm | ✅ `DAYS` as `int_arraylist` of `java.util.Calendar` day constants |
| Read timers or alarms | ❌ nothing lists them |
| Silence a ringing timer | ✅ `DISMISS_TIMER`, no extras |
| Silence a ringing alarm | ❌ `DISMISS_ALARM` accepted, it keeps ringing |
| Snooze a ringing alarm | ❌ `SNOOZE_DURATION` is ignored; `SNOOZE_ALARM` snoozes for the alarm's own interval only |
| Cancel a running timer | ❌ `DISMISS_TIMER` accepted, the timer keeps counting |
| Cancel a scheduled or snoozed alarm | ❌ `DISMISS_ALARM` accepted, the alarm stays |
| Stopwatch | ❌ signature-guarded · the Clock app can be opened for the user to tap it |

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
two timers, and nothing can list or read them.

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

## Silence a ringing timer

```
send_intent
{"action": "android.intent.action.DISMISS_TIMER", "package": "com.sec.android.app.clockpackage", "mode": "activity"}
```

Stops the ringing. Sent while the timer is still counting it is accepted
and the timer keeps counting; Android defines `DISMISS_TIMER` for expired
timers only.

## Alarms, once set, are the user's

`DISMISS_ALARM` is accepted in every state and changes nothing: a
ringing alarm keeps ringing, a snoozed or scheduled alarm stays set,
with or without the label search extras. `SNOOZE_ALARM` at a ringing
alarm does snooze it, but only for the interval set on the alarm itself
(the Samsung default is five minutes); the
`android.intent.extra.alarm.SNOOZE_DURATION` extra is ignored, so the
caller cannot choose how long.

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
