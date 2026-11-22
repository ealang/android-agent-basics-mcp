---
name: pebble-watch-control
description: How to drive any Pebble watchapp. Launch, stop, read what the watch has open, send AppMessages one at a time or as a stream, and read what a companion watchapp sends back.
---

# Driving a Pebble watchapp

## Launch, stop, state

```
pebblekit2_launch_app  {"app": "<watchapp uuid>"}
pebblekit2_stop_app    {"app": "<watchapp uuid>"}
pebblekit2_watch_state {}
```

These three work for any watchapp.

A launch waits, up to ten seconds, for the watch to report the app
running, and fails with what was observed otherwise. If the watch never
reports the app running, ensure the app is installed on the watch and
the UUID is right. A stop is passed to the watch and not confirmed.

`pebblekit2_watch_state` is the read-back for both: the connected
watches, each with its name, platform, firmware and `running`, the open
app's UUID, name and whether it is a watchface or a watchapp. No watch
connected is an empty list.

## Tuples

A send takes `tuples`, the AppMessage: an array of
`{key, type, value}`, `type` one of `int`, `uint`, `string`, `bytes`
(base64). Integers go 4 bytes wide; add `"length": 1` or `2` when a
watchapp reads a narrower value. An empty array is a valid message. A
tuple that does not fit the wire format is refused without being sent.

In both families an ACK means only that the message reached the
watchapp, not how the watchapp responded to it.

## PebbleKit 2 (`pebblekit2_*`)

### Compatibility

Sending and receiving work only for a watchapp that names Agent Basics
MCP as its Android companion. Use this family whenever it is available:
it needs no ping, says why a send failed, is able to send multiple messages in one
call, and reads what the watchapp sends back.

### Sending to a watchapp

```
pebblekit2_launch_and_send {"app": "<watchapp uuid>", "tuples": [...]}
pebblekit2_send_message    {"app": "<watchapp uuid>", "tuples": [...]}
```

Use `pebblekit2_launch_and_send` whenever the watchapp may not be open.
It opens the app if needed and waits until it can receive messages
before sending.

### Several messages in one call

Both tools take `messages` in place of `tuples`:

```
pebblekit2_launch_and_send
{
  "app": "<watchapp uuid>",
  "messages": [
    [{"key": 0, "type": "uint", "value": 1}],
    [{"key": 0, "type": "uint", "value": 2}]
  ],
  "timeout_seconds": 15
}
```

- This is much faster than one call per message.
- Messages are sent in order, each after the watch acknowledged the one
  before.
- `timeout_seconds`, default 60, is the time allowed
  for the whole array.
- A failure is returned as an error that says how far the sending got,
  for example: "FAILED: the watch acknowledged 11 of 30 messages.
  Message 12 failed: the time allowed ran out before it was sent.
  Nothing after it was sent."

### Reading what a watchapp sends

```
pebblekit2_receive_messages
{"app": "<watchapp uuid>", "wait_seconds": 30}
```

returns

```
{"events": [
   {"type": "app_opened", "received_at": "2026-01-05T09:30:00.120-05:00"},
   {"type": "message", "received_at": "2026-01-05T09:30:04.870-05:00",
    "tuples": [{"key": 0, "type": "uint", "length": 1, "value": 3}]},
   {"type": "app_closed", "received_at": "2026-01-05T09:31:10.002-05:00"}],
 "dropped": 0}
```

- Events come in arrival order, for that watchapp only. `tuples` is in
  the form the send tools take. Integers arrive with `"length": 4`
  whatever width the watchapp wrote; the value and its sign are right.
- **Reading empties the queue.** Each event is returned once, to
  whichever call reads it first.
- `wait_seconds` holds the call open until the first event arrives.
  Anything over 600 is treated as 600; to keep listening, call again.
  Nothing is lost between calls. Keep the wait shorter than the
  caller's own request timeout: a caller that gives up first can lose
  the event that arrives next.
- `max_age_seconds`, default an hour, discards older events instead of
  returning them.
- The queue is memory only and holds 1024 events per watchapp. `dropped`
  counts those lost to that limit since the last read. Events from
  before the app on the phone last restarted are gone.
- The watch was told each message arrived when it was queued, whether or
  not anything ever reads it.
- `app_opened` and `app_closed` bracket a session. `app_closed` is sent
  once, best-effort: a session can end without one.

## PebbleKit 1 (`pebblekit_*`)

### Compatibility

The legacy API. It reaches any watchapp, and is the only way to send to
one that does not name Agent Basics MCP as its companion. Nothing can be
read back.

### Sending to a watchapp

One call does the whole job when the watchapp may not be running:

```
pebblekit_launch_and_send
{
  "app": "<watchapp uuid>",
  "ping": [],
  "tuples": [{"key": <key>, "type": "int", "value": <value>}]
}
```

`ping` is sent about once a second until the watch replies, which shows
the app is ready; the payload is then sent exactly once. Take the ping
from the app's file. It is usually `[]`, which is inert for any app
whose handler only reacts to keys it knows.

`pebblekit_send_message {"app", "tuples"}` sends to an app already
running. A message to an app that is not running vanishes without an
error.

If no inert ping is known for an app, use `pebblekit2_launch_app`, then
`pebblekit_send_message`, and accept it as best-effort.
