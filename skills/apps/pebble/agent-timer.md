# Agent Timer (Pebble watchapp)

UUID `6ba47813-25c2-4c9e-b93a-f6f24372b4c3`. A fork of the stock Timer
watchapp (github.com/ealang/coredevices-pebble-timer) that starts a timer
on request from the phone — the fork exists because the stock one is a
system app and system apps cannot be sent to at all.

## Keys

| Key | Name | Type | Effect |
|---|---|---|---|
| 1 | `KEY_PING` | any | **Nothing, by design.** |
| 20 | `KEY_START_TIMER` | `int` (int32, seconds) | Starts a timer and shows its detail screen. |

Declared in `package.json` but unread by the inbox handler: 5
(`KEY_DURATION`), 10 (`KEY_UNIQUEID`), 15 (`KEY_TOTAL_TIME`). They are
inert only by accident, and one edit from meaning something.

The handler returns early on key 1, then acts only if key 20 is present;
anything else — including an empty message, so the default ping of
`pebblekit_launch_and_send` is safe here — is delivered, ACKed, and
ignored.

## Start a timer

One call does the whole dance — launch, ping until the app is up, deliver
once — via `pebblekit_launch_and_send`:

```json
{
  "app": "6ba47813-25c2-4c9e-b93a-f6f24372b4c3",
  "tuples": [{"key": 20, "type": "int", "length": 4, "value": 300}]
}
```

The value is seconds; the watchapp's own settings screen can express 5 to
86399, so stay inside that. The watch buzzes when the countdown finishes
and keeps counting with the phone away. The watch holds several timers at
once, so repeating the call adds another timer rather than replacing one —
which is also why a "no ACK or NACK" result should not be blindly resent.

A success means the watch acknowledged delivery to the running app. It
does not prove the handler acted; `pebble logs --phone <ip>` over
CoreApp's developer connection is the only channel that shows the C side.

There is no way to cancel, read, or adjust a timer from the phone — the
watchapp exposes no such keys.

Showing the detail screen after starting is deliberate: the phone has just
launched the app, so whatever was on screen is not something the user chose
to look at, and a timer created behind the edit screen looks the same as a
request that did nothing. A timer already ringing keeps the screen, since
it owns the vibration.

## Clock-time requests

"A timer until 3pm" is the caller's arithmetic: resolve the next 3pm in
the phone's timezone, send the difference in seconds, and echo the
resolved clock time back to the user — that echo is the only way an am/pm
guess that went the wrong way becomes visible before the timer rings.
