# Timers and alarms — the AlarmClock intents

A generic Android skill, not tied to any app: the clock interface is a
family of public intent actions (`android.provider.AlarmClock`), and
each one is handled by whatever app the system resolves it to — the
default clock app, or whichever app the user has chosen for that role.
Timers and alarms can even land in different apps (observed on the test
device: timers in Samsung Clock, alarms in Sleep as Android). Whether
an action is honoured is therefore up to the receiving app, and the
caveats below record what was actually observed.

Everything here goes through `send_intent` with `mode: "activity"` —
these are activity intents, so the overlay grant ("Display over other
apps") must be held for a background send; the tool preflights that and
names it on failure.

Delegating to the clock app is the point, not a workaround: the timer
lands where the user already looks for timers, rings with their own
alarm sound, can be edited or snoozed by hand, and keeps running if
phone-mcp dies.

## Set a timer — works

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

Verified on the device (2026-10-01): the timer appears and counts down.

- `LENGTH` is seconds, and **must be typed `int`** — a string arrives as
  nothing on the receiving side. Clock apps accept 1–86400 (24 h) and
  reject outside that; validate before sending.
- Always set a `MESSAGE` label so the user can tell agent-set timers
  apart; there is no read API to check later.
- `SKIP_UI: true` is a request, not a guarantee: the stock clock starts
  the timer without coming to the front; a third-party clock may show
  its UI anyway.

## Set an alarm — works

```json
{
  "action": "android.intent.action.SET_ALARM",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.HOUR", "type": "int", "value": 7},
    {"key": "android.intent.extra.alarm.MINUTES", "type": "int", "value": 30},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "flight"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

Verified on the device (2026-10-01): the alarm appears, armed.
`HOUR`/`MINUTES` must be typed `int` like `LENGTH` above. For repeating
alarms add `DAYS` as an `int_array` of `java.util.Calendar` day
constants (Sunday = 1). `android.intent.action.SHOW_ALARMS` opens the
alarm list on screen.

## Dismissing — does not work; do not rely on it

`android.intent.action.DISMISS_TIMER` and
`android.intent.action.DISMISS_ALARM` (with
`android.intent.extra.alarm.SEARCH_MODE` = `"android.label"` +
`MESSAGE`, `"android.next"`, or no extras at all against a ringing
alarm) all resolve and are accepted — "Started activity" — and then
nothing happens: the timer keeps counting, the alarm stays armed, a
ringing alarm keeps ringing. Verified on the device (2026-10-01),
repeatedly, against both receiving apps. `SNOOZE_ALARM` is the same
grammar and there is no reason to expect better.

This is the receiving app's choice, so another clock app may honour
these — but since the handler is whatever the system default is, treat
dismiss as unavailable: a timer or alarm, once set, is the user's to
stop.

## No read-back of any kind

- **No read API.** Nothing can enumerate timers or alarms, so there is
  no list and no way to confirm state. "Started activity" from
  `send_intent` means the handling app took the intent; that is all
  the platform offers.
- **No duplicate protection follows from that.** Sending SET_TIMER or
  SET_ALARM twice makes two. Never resend on doubt.

A `package` can be added to any of these to target a specific clock app
instead of the system default — see per-app files in `apps/` for apps
with their own alarm surfaces.
