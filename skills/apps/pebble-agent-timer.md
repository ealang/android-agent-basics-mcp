# Agent Timer (Pebble watchapp)

UUID `6ba47813-25c2-4c9e-b93a-f6f24372b4c3`, known to the server by the
short name `timer`. A fork of the stock Timer watchapp
(github.com/ealang/coredevices-pebble-timer) — the fork exists because the
stock one is a system app and system apps cannot be sent to at all.

There is a first-class tool for the only thing it currently does:
`set_watch_timer(seconds, label?)`, which launches, pings, sends, and waits
for the ACK on its own. **Prefer it.** The key table below is for driving
the app through `send_watch_message` — worth doing only when adding a new
key to the watchapp, or when testing the generic path.

## Keys

| Key | Name | Type | Effect |
|---|---|---|---|
| 1 | `KEY_PING` | any | **Nothing, by design.** The safe ping key for the wait-until-running loop. |
| 20 | `KEY_START_TIMER` | `int` (int32, seconds) | Starts a timer and shows its detail screen. |

Declared in `package.json` but unread by the inbox handler: 5
(`KEY_DURATION`), 10 (`KEY_UNIQUEID`), 15 (`KEY_TOTAL_TIME`). Do not use
them as a ping — they are inert only by accident, and one edit from
meaning something.

The handler returns early on key 1, then acts only if key 20 is present;
anything else is delivered, ACKed, and ignored.

## Driving it generically

1. `launch_watch_app` with `{"app": "timer"}`.
2. Ping until running: `send_watch_message` with
   `{"app": "timer", "tuples": [{"key": 1, "type": "int", "value": 0}]}`,
   repeated on "no reply" (about once a second, give up at 10 s). Key 1 is
   discarded by the app on purpose, so repeats are harmless.
3. Once — never repeated:
   `{"app": "timer", "tuples": [{"key": 20, "type": "int", "value": 300}]}`.

That sequence is what `set_watch_timer(300)` does internally, in one call.

Showing the detail screen after starting is deliberate: the phone has just
launched the app, so whatever was on screen is not something the user chose
to look at, and a timer created behind the edit screen looks the same as a
request that did nothing. A timer already ringing keeps the screen, since
it owns the vibration.

## Reading what the app actually did

`pebble logs --phone <ip>` over CoreApp's developer connection is the only
channel that shows the C side. The ACK cannot tell you whether the handler
ran; the log can.
