# Sleep as Android (com.urbandroid.sleep)

The alarm clock in use, so "move my wake time" means this app and not the
stock clock. Verified on device 2026-10-04.

## The three surfaces, and why only one of them can retime an alarm

| Surface | Create | Read | Change an existing alarm's time | Guard |
|---|---|---|---|---|
| `SET_ALARM` intent | yes — and **duplicates** | no | **no** | `SET_ALARM` (normal, held) |
| `ALARM_STATE_CHANGE` broadcast | no | no | no — `alarm_enabled` only, addressed by label | none |
| `content://com.urbandroid.sleep.alarmclock/alarm` | yes | yes | **yes**, full CRUD | `com.urbandroid.sleep.READ` / `.WRITE` |

Docs: [intent API](https://docs.sleep.urbandroid.org/devs/intent_api.html),
[content provider API](https://docs.sleep.urbandroid.org/devs/content_provider_api.html).

## The provider is closed to us, and `WRITE` is closed to everyone

phone-mcp holds neither grant, so `provider_query` against the alarm
authority fails outright:

```
SecurityException: Permission Denial: opening provider
com.urbandroid.sleep.alarmclock.AlarmProvider … requires
com.urbandroid.sleep.READ or com.urbandroid.sleep.WRITE
```

`<uses-permission>` is build-time, so no caller can fix that at
runtime — and the two permissions are not equal:

```
com.urbandroid.sleep.READ    dangerous   grantable to an app that declares it
com.urbandroid.sleep.WRITE   signature   ungrantable
```

`signature` means only Urbandroid-signed apps can hold `WRITE` — same
wall as Samsung's stopwatch. There is no toggle in Settings, no
`pm grant`, no manifest line that helps. **Writes to Sleep's alarm
table are permanently unavailable: no retime, no enable, no delete.**
Urbandroid's documentation says the alarm URI "allows all provider
operations", which is true only for Urbandroid. Do not re-probe this.

`ContentProvider.call()` is *not* write-gated in AOSP — opening the
provider needs READ *or* WRITE and `call()` adds no check — so an app
holding `READ` could reach Sleep's own `call()`. But Urbandroid
documents no method names and they cannot be enumerated, so it is a
lottery, not a route.

## SET_ALARM creates a second alarm; it does not edit the first

Sleep reuses an existing alarm only when *every* parameter matches, so
a new time makes a new alarm. With no working dismiss and no delete,
**each attempt leaves a live alarm the user has to remove by hand** —
ask before sending one.

`DAYS` must be an **`int_arraylist`** (`ArrayList<Integer>` of
`java.util.Calendar` constants, Sunday = 1); with any other type the
alarm comes out one-time (`daysofweek = 0`) — see
`../alarms-and-timers.md`.

## The alarm table

Columns, per Urbandroid's provider docs: `_id`, `hour`, `minutes`,
`daysofweek`, `alarmtime`, `enabled`, `vibrate`, `message` (this is the
label), `alert` (ringtone URI). Query with a **null projection** — a
fork may not have every documented column, and asking for a missing one
throws rather than returning null.

`daysofweek` is a bitmask; **127 (`0x7F`) is every day** and `0` is
one-time. Labels default to null, and that matters more than it looks:
`ALARM_STATE_CHANGE` — the one documented, unguarded way to toggle an
alarm — addresses alarms *by label*, so a null-labelled alarm cannot be
addressed at all. Labels are a manual edit in Sleep's own UI.

## Reachable with no permissions at all

Sleep tracking start/stop/pause, lullabies, snooze, dismiss and sleep
history are plain intents, so `send_intent` drives them generically.
Not yet tested here.

A nap ("wake me in 30 minutes") is **not** known to be available:
Urbandroid documents no nap action, and
`START_SLEEP_TRACK_WITH_IDEAL_ALARM_ACTION` derives its alarm from the
sleep-duration *goal*, not an arbitrary interval.
