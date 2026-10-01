# Timers and alarms — the AlarmClock intents

Android's clock interface is a family of public intent actions
(`android.provider.AlarmClock`) handled by whichever clock app the
phone ships. Everything here goes through `send_intent` with
`mode: "activity"` — these are activity intents, so the overlay grant
("Display over other apps") must be held for a background send; the
tool preflights that and names it on failure.

Delegating to the clock app is the point, not a workaround: the timer
lands where the user already looks for timers, rings with their own
alarm sound, can be edited or snoozed by hand, and keeps running if
phone-mcp dies.

## Set a timer

```json
{
  "action": "android.intent.action.SET_TIMER",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.LENGTH", "type": "int", "value": 300},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "tea"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

- `LENGTH` is seconds, and **must be typed `int`** — a string arrives as
  nothing on the receiving side. Clock apps accept 1–86400 (24 h) and
  reject outside that; validate before sending.
- Always set a `MESSAGE` label: it is the only handle for cancelling a
  specific timer later, because there is no read API.
- `SKIP_UI: true` is a request, not a guarantee: the stock clock starts
  the timer without coming to the front; a third-party clock may show
  its UI anyway.

## Cancel a timer

`android.intent.action.DISMISS_TIMER`, same shape. By label:

```json
"extras": [
  {"key": "android.intent.extra.alarm.SEARCH_MODE", "type": "string", "value": "android.label"},
  {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "tea"}
]
```

Or the next one to fire, with `SEARCH_MODE` = `"android.next"` and no
`MESSAGE`. Some clock apps show a picker when the search matches more
than one timer.

## What cannot be done

- **No read API.** Nothing can enumerate the clock app's timers, so
  there is no list and no way to confirm what is running. "Started
  activity" from `send_intent` means the clock app took the intent;
  that is all the platform offers.
- **No duplicate protection follows from that.** Sending SET_TIMER
  twice makes two timers. Never resend on doubt.

## Alarms

The same family: `android.intent.action.SET_ALARM` (`HOUR`, `MINUTES`
as ints, `MESSAGE`, `DAYS` as an int_array of `java.util.Calendar` day
constants for repeats, `SKIP_UI`), `android.intent.action.DISMISS_ALARM`
and `android.intent.action.SNOOZE_ALARM` (same `SEARCH_MODE` grammar,
plus `"android.time"` with `HOUR`/`MINUTES`, and `"android.all"`), and
`android.intent.action.SHOW_ALARMS` which only opens the list on
screen. Like timers: create and dismiss are reliable, reading the alarm
list generically is impossible.

A `package` can be added to any of these to target a specific clock app
instead of the system default — see per-app files in `apps/` for apps
with their own alarm surfaces.
