# Agent Timer (Pebble watchapp)

UUID `6ba47813-25c2-4c9e-b93a-f6f24372b4c3`. A fork of the stock Timer
watchapp (github.com/ealang/coredevices-pebble-timer) that starts a timer
on request from the phone — the fork exists because the stock one is a
system app and system apps cannot be sent to at all.

## Start a timer

One `pebblekit_launch_and_send` call, with an empty message as the ping
and the duration in key 20 (int32 seconds):

```json
{
  "app": "6ba47813-25c2-4c9e-b93a-f6f24372b4c3",
  "ping": [],
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
