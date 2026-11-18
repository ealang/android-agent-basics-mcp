---
name: pebble-watch-control
description: How to drive any Pebble watchapp. Launch, stop, read what the watch has open, send an AppMessage, read what a companion watchapp sends back, what an ACK proves, and which watchapps cannot be reached.
---

# Driving a Pebble watchapp

Two tool families, named for the phone companion's API each one uses.

- `pebblekit2_*` launches and stops any watchapp and reports what the
  watch has open. For a watchapp that names Agent Basics MCP as its
  Android companion it also sends AppMessages and reads the ones the
  watchapp sends to the phone.
- `pebblekit_*` is two send tools for every other watchapp. They cannot
  read anything back.

Which family sends to a given watchapp is a fact about that watchapp, and
its file in `pebble-apps/` says so along with its keys. With no file,
assume `pebblekit_*`: few watchapps name a companion, and one that does
not answers a `pebblekit2_` send with "not open" however open it is.

## Launch, stop, state

```
pebblekit2_launch_app  {"app": "<watchapp uuid>"}
pebblekit2_stop_app    {"app": "<watchapp uuid>"}
pebblekit2_watch_state {}
```

A launch waits, up to ten seconds, for the watch to report the app
running, and fails with what was observed otherwise. If the watch never
reports the app running, ensure the app is installed on the watch and
the UUID is right. A stop is passed to the watch and not confirmed.

`pebblekit2_watch_state` is the read-back for both: the connected
watches, each with its name, platform, firmware and `running`, the open
app's UUID, name and whether it is a watchface or a watchapp. No watch
connected is an empty list.

## Tuples

Every send takes `tuples`, the AppMessage: an array of
`{key, type, value}`, `type` one of `int`, `uint`, `string`, `bytes`
(base64). Integers go 4 bytes wide; add `"length": 1` or `2` when a
watchapp reads a narrower value. An empty array is a valid message. A
tuple that does not fit the wire format is refused without being sent.

## Sending to a watchapp that names no companion

One call does the whole job when the watchapp may not be running:

```
pebblekit_launch_and_send
{
  "app": "<watchapp uuid>",
  "ping": [],
  "tuples": [{"key": <key>, "type": "int", "value": <value>}]
}
```

`ping` is a message sent about once a second until the watch replies,
then the payload is delivered exactly once. Take the ping from the app's
file. It is usually `[]`, which is inert for any app whose handler only
reacts to keys it knows.

`pebblekit_send_message {"app", "tuples"}` sends to an app already
running.

### Why the handshake

A message to a watchapp that is not running is discarded in silence: the
phone companion registers a receiver per running watchapp, so a closed
app has none, nothing errors and nothing arrives. A launch alone is not
quite enough either: the companion wires up that receiver a beat after
the watch reports the app running, and a send straight after the launch
can lose the race. The launch-and-send loop waits for the first reply,
which proves the app can be reached, and only then sends the payload. It
gives up after ten seconds of unanswered pings.

If no inert ping is known for an app, there is no safe handshake:
`pebblekit2_launch_app`, which returns once the watch reports the app
running, then `pebblekit_send_message`, and accept it as best-effort.

## Sending to a watchapp that names Agent Basics MCP as companion

```
pebblekit2_launch_and_send {"app": "<watchapp uuid>", "tuples": [...]}
pebblekit2_send_message    {"app": "<watchapp uuid>", "tuples": [...]}
```

No ping. Here a send that cannot be delivered is refused with a reason
instead of vanishing, so launch-and-send simply offers the message until
the app accepts it, and it is delivered at most once.

The failures are worth reading:

- **Not open, or not a companion watchapp.** The phone companion gives
  one answer for a closed app, an app that is not in front, and an open
  app that does not name Agent Basics MCP. `pebblekit2_watch_state`
  tells the first two from the third.
- **Refused by the Pebble app.** The user has not allowed Agent Basics
  MCP to talk to this watchapp. They are asked in the Pebble app.
- **NACK.** The watch received the message and refused it.
- **No answer within 10s.** It may or may not have arrived.

## Reading what a watchapp sends

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
  whichever call reads it first. One reader per watchapp.
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

Only a watchapp that names Agent Basics MCP as its Android companion is
heard from. An empty result for any other watchapp means nothing.

## What the reply proves

The send tools return the watch's answer. ACK proves the firmware
accepted delivery to the running app. It does not prove the handler
acted: a key the app has never heard of is ACKed exactly like one it
acts on, and there is no error for a wrong key. A payload that gets no
reply is not resent by the tool, since it may have arrived with only the
reply lost, and a resend does whatever the keys do a second time.

## Keys are per-app knowledge

Nothing in the transport describes or validates an app's keys. With no
file in `pebble-apps/`, the keys come from the watchapp's own
`package.json` (`messageKeys`) and its inbox handler in C, which also
decides whether an empty ping is safe.

## Watchapps that cannot be reached

- **System apps.** Stock watchapps cannot be sent to at all.
- **Apps that name a different Android companion app.** Their sends go
  through a path that checks the calling package, and Agent Basics MCP
  is not it. They can still be launched and stopped.
- **No watch connected.** This one is caught: every tool that acts on
  the watch preflights the link and fails fast naming it.

Apps with a JavaScript companion only (PKJS) are fine, over
`pebblekit_*`.
