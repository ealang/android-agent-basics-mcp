---
name: pebble-agent-timer
description: Agent Timer, a Pebble watchapp. Start a countdown timer on the watch in one call. Timers cannot be read, cancelled or changed from the phone.
verified: 2026-10-20
---

# Agent Timer (Pebble watchapp)

UUID `6ba47813-25c2-4c9e-b93a-f6f24372b4c3`. A fork of the stock Timer
watchapp (github.com/ealang/coredevices-pebble-timer) that starts a timer
on request from the phone; the stock app is a system app and cannot be
sent to. One call starts a timer. Nothing can read, cancel or adjust one
from the phone.

## Start a timer

```
pebblekit_launch_and_send
{
  "app": "6ba47813-25c2-4c9e-b93a-f6f24372b4c3",
  "ping": [],
  "tuples": [{"key": 20, "type": "int", "length": 4, "value": <seconds>}]
}
```

The value is seconds, 5 to 86399. The watch buzzes when the countdown
finishes and keeps counting with the phone away. It shows the new
timer's detail screen so the user sees what was set.

The watch holds several timers at once, so repeating the call adds
another timer rather than replacing one. A result of no ACK and no NACK
does not mean the timer was not set; the reply alone may have been lost.

## Clock-time requests

"A timer until 3pm" is the caller's arithmetic: resolve the next 3pm in
the phone's timezone, send the difference in seconds, and echo the
resolved clock time back to the user, so an am/pm guess that went the
wrong way is visible before the timer rings.
