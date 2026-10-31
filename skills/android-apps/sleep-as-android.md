---
name: android-sleep-as-android
description: Sleep as Android alarm clock. Create an alarm, and nothing else. The alarm table is closed, so an existing alarm cannot be read, retimed, enabled or deleted, and every create adds a new one.
verified: 2026-10-04
---

# Sleep as Android

Package `com.urbandroid.sleep`. When this app is the user's alarm clock,
"move my wake time" means this app and not the stock clock, and the only
thing that works is creating an alarm through the standard intent. The
alarm table cannot be read or written, so an existing alarm cannot be
retimed, enabled, disabled or deleted, and each create adds a new alarm
the user has to remove by hand.

## Create an alarm

```
send_intent
{
  "action": "android.intent.action.SET_ALARM",
  "package": "com.urbandroid.sleep",
  "mode": "activity",
  "extras": [
    {"key": "android.intent.extra.alarm.HOUR", "type": "int", "value": <hour>},
    {"key": "android.intent.extra.alarm.MINUTES", "type": "int", "value": <minutes>},
    {"key": "android.intent.extra.alarm.MESSAGE", "type": "string", "value": "<label>"},
    {"key": "android.intent.extra.alarm.SKIP_UI", "type": "boolean", "value": true}
  ]
}
```

Sleep reuses an existing alarm only when every parameter matches, so a
new time makes a new alarm; there is no dismiss and no delete to undo
it. For a repeating alarm add `android.intent.extra.alarm.DAYS` as an
`int_arraylist` of `java.util.Calendar` day constants (Sunday = 1); with
any other type the alarm comes out one-time.

## Why nothing else works

| Surface | Create | Read | Change | Guard |
|---|---|---|---|---|
| `SET_ALARM` intent | yes, and duplicates | no | no | normal permission, held |
| `ALARM_STATE_CHANGE` broadcast | no | no | enable/disable only, addressed by label | none |
| `content://com.urbandroid.sleep.alarmclock/alarm` provider | yes | yes | yes | `com.urbandroid.sleep.READ` / `.WRITE` |

The provider is the only surface that could retime an alarm, and it is
closed: `provider_query` against the alarm authority fails with a
permission denial naming `READ` or `WRITE`, `READ` is a build-time
manifest grant Puck MCP does not declare, and `WRITE` is
signature-level, so only Urbandroid-signed apps can ever hold it. No
Settings toggle and no manifest line changes that.

The broadcast toggles an alarm by its label, and alarms created by the
intent carry no label the app addresses, so it reaches nothing created
here. Labels are a manual edit in the app's own UI.

There is no nap action: Urbandroid documents none, and the
"ideal alarm" tracking action derives its alarm from the user's sleep
goal, not an interval.

Docs: [intent API](https://docs.sleep.urbandroid.org/devs/intent_api.html),
[content provider API](https://docs.sleep.urbandroid.org/devs/content_provider_api.html).
