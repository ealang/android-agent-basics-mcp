---
name: android-alarms-and-timers
description: Set a timer or alarm on whatever clock app the phone uses, through the standard AlarmClock intents. The calls, what every Android phone guarantees, and how to find the handling app's own skill file.
---

# Timers and alarms — the AlarmClock intents

Timers and alarms are set through the public `android.provider.AlarmClock`
intent actions, which the system hands to whichever app is registered for
them: usually the stock clock, sometimes a third-party alarm app, and
timers and alarms can land in different apps. The timer then lives where
the user already looks for timers, rings with their own sound, and keeps
running if Puck MCP dies.

This file is the starting point for a clock app with no file of its own
in `android-apps/`. Where the handling app has one, use that file: its
calls are complete and it says what the app actually does with each
action.

## What every phone guarantees, and what it does not

Android's compatibility definition requires every certified device to
ship a handler for the AlarmClock actions: SET_ALARM, SET_TIMER,
SHOW_ALARMS, SHOW_TIMERS, DISMISS_ALARM, DISMISS_TIMER and SNOOZE_ALARM
all **resolve** on any Play-certified phone. A send that fails to
resolve means an uncertified device.

Resolving is not acting. The requirement is that a handler exists, not
that it does anything, and clock apps differ:

- **SET_TIMER and SET_ALARM are honoured.** Creating is the purpose of
  the contract and what voice assistants drive; a clock that ignored it
  would be visibly broken.
- **SHOW_ALARMS and SHOW_TIMERS** open the list on screen. Harmless.
- **DISMISS_ALARM, DISMISS_TIMER and SNOOZE_ALARM are per app.** Some
  clocks accept them and do nothing; the handling app's file says what
  it does with them.

## The calls

All through `send_intent` with `mode: "activity"`. These are activity
intents, so a send from the background needs the overlay grant ("Display
over other apps"); the tool preflights that and names it on failure.

Set a timer:

```json
{
  "action": "android.intent.action.SET_TIMER",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.LENGTH", "type": "int", "value": 300},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "<label>"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

Set an alarm:

```json
{
  "action": "android.intent.action.SET_ALARM",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.HOUR", "type": "int", "value": 7},
    {"key": "android.intent.extra.alarm.MINUTES", "type": "int", "value": 30},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "<label>"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

- `LENGTH` is seconds, typed `int`, 1 to 86400.
- `HOUR` and `MINUTES` are typed `int`. For a repeating alarm add
  `android.intent.extra.alarm.DAYS` as an `int_arraylist` of
  `java.util.Calendar` day constants (Sunday = 1, Saturday = 7); with any
  other type the alarm comes out one-time.
- `MESSAGE` labels the timer, so the user can tell agent-set timers apart.
- `SKIP_UI: true` is a request, not a guarantee: most clocks set the
  timer without coming to the front, some show their UI anyway.
- **SET_ALARM only ever creates.** The contract has no way to address an
  existing alarm, so sending a new time makes a second alarm rather than
  moving the first.
- Add `"package": "<clock app>"` to target a specific app instead of the
  system default, when the user has more than one.

## Which app will handle it

`intent_resolve` with the same action returns the handling package. Look
for its file in `android-apps/`; if there is none, the facts above are
all that can be assumed.

## No read-back

Nothing can enumerate timers or alarms, so there is no list and no way to
confirm state. "Started activity" means the handling app took the
intent; that is all the platform offers. Sending SET_TIMER or SET_ALARM
twice makes two.
