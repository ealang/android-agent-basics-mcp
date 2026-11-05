---
name: pebble-watch-control
description: How to drive any Pebble watchapp. Launch, send an AppMessage, the launch-and-send handshake that beats the silent discard, what an ACK proves, and which watchapps cannot be reached.
---

# Driving a Pebble watchapp

The `pebblekit_*` tools launch a watchapp and send it AppMessages; there
is no read side. One call does the whole job when the watchapp may not
be running: `pebblekit_launch_and_send`. Use it by default. The keys a
watchapp understands are per-app knowledge, in `pebble-apps/`, one file
per app.

## The call

```
pebblekit_launch_and_send
{
  "app": "<watchapp uuid>",
  "ping": [],
  "tuples": [{"key": <key>, "type": "int", "value": <value>}]
}
```

- `tuples` is the AppMessage: an array of `{key, type, value}`, `type`
  one of `int`, `uint`, `string`, `bytes` (base64). Integers go 4 bytes
  wide; add `"length": 1` or `2` when a watchapp reads a narrower value.
  An empty array is a valid message. A tuple that does not fit the wire
  format is refused without being sent.
- `ping` is a message sent about once a second until the watch replies,
  then the payload is delivered exactly once. Take the ping from the
  app's file. It is usually `[]`, which is inert for any app whose
  handler only reacts to keys it knows.

`pebblekit_send_message {"app", "tuples"}` sends to an app already
running, and `pebblekit_launch_app` / `pebblekit_stop_app {"app"}` start
and stop one. A launch is fire-and-forget: the watch never acknowledges
it.

## Why the handshake

A message to a watchapp that is not running is discarded in silence: the
phone companion registers a receiver per running watchapp, so a closed
app has none, nothing errors and nothing arrives. A launch alone is not
enough either, because it takes a second or two and a send straight after
loses the race. The launch-and-send loop waits for the first reply, which
proves the app is up, and only then sends the payload. It gives up after
ten seconds.

If no inert ping is known for an app, there is no safe handshake:
`pebblekit_launch_app`, wait a couple of seconds, `pebblekit_send_message`,
and accept it as best-effort.

## What the reply proves

The send tools return the watch's answer. A reply of any kind proves the
app is running; ACK rather than NACK proves the firmware accepted
delivery. Neither proves the handler acted: a key the app has never heard
of is ACKed exactly like one it acts on, and there is no error for a
wrong key. A payload that gets no reply is not resent by the tool, since
it may have arrived with only the reply lost, and a resend does whatever
the keys do a second time.

## Keys are per-app knowledge

Nothing in the transport describes or validates an app's keys. With no
file in `pebble-apps/`, the keys come from the watchapp's own
`package.json` (`messageKeys`) and its inbox handler in C, which also
decides whether an empty ping is safe.

## Watchapps that cannot be reached

- **System apps.** Stock watchapps cannot be sent to at all.
- **Apps that declare an Android companion app.** Their sends go through
  a path that checks the calling package, and Agent Basics MCP is not it.
- **No watch connected.** This one is caught: every watch tool preflights
  the link and fails fast naming it.

Apps with a JavaScript companion only (PKJS) are fine.
