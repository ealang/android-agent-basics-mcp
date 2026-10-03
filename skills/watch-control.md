# Driving a Pebble watchapp

The surface is the `pebblekit_*` family — the classic PebbleKit intent
protocol, remoted (a `pebblekit2_*` family would be a different channel).
The tools map onto the Android app tools closely enough that the analogy
is worth keeping: `pebblekit_launch_app` is a launch intent,
`pebblekit_send_message` is `send_intent`, and there is no read side at
all.

## The tools

- `pebblekit_launch_app(app)` / `pebblekit_stop_app(app)` — `app` is a
  UUID or a short name the server knows. Fire-and-forget: the watch never
  acknowledges a launch, so a success here means the broadcast went out,
  nothing more.
- `pebblekit_send_message(app, tuples)` — one AppMessage. `tuples` is an
  array of `{key, type, length, value}` with `type` one of `int`, `uint`,
  `string`, `bytes`. **All four fields are required** — `length` is the
  byte width (1, 2 or 4 for int/uint). Sent verbatim once it fits the wire
  format; a tuple that does not is refused without being sent.
- `pebblekit_launch_and_send(app, ping_key, tuples)` — the launch-ping-send
  loop below, in one call. Use it whenever the watchapp may not be
  running.

## The one rule that catches everyone

**A message to a watchapp that is not running is discarded in silence.**
The phone companion registers its send receiver per *running* watchapp, so
a closed app has no receiver — nothing errors, nothing arrives. Launching
is not enough on its own either, because a launch takes a second or two
and the send will lose the race.

`pebblekit_launch_and_send` is the answer: it launches, sends a single
tuple on `ping_key` about once a second until the watch answers (any
reply — ACK or NACK — proves the app is up; see below), gives up at 10 s,
and only then delivers the payload, exactly once. A payload that gets no
reply is **not** resent by the tool — it may have arrived with only the
reply lost, and whether a duplicate is harmless depends on what the keys
do, which only the caller knows.

The ping key matters: it must be a key **the target app deliberately
ignores**, and it is sent repeatedly, so a wrong one does something
several times. Which key is safe is per-app knowledge from the app's
file in `apps/pebble/`. If the app has no key known to be inert, there is
no safe ping — `pebblekit_launch_app`, wait a couple of seconds, send
with `pebblekit_send_message`, and accept that it is best-effort.

## What an ACK proves

The send tools return the watch's own answer, and it proves less than it
looks:

- Something came back ⇒ **the app is running**. This is the load-bearing
  fact and it holds for any watchapp.
- ACK rather than NACK ⇒ the watch's firmware accepted delivery.
- It does **not** prove the app's handler ran, or that the keys meant
  anything. A key the app has never heard of is ACKed exactly like a key
  it acts on. There is no error for "wrong key" and there never will be.

So a successful send is not evidence the intended thing happened. When
that matters, verify by what the user can see on the watch.

## Keys are per-app knowledge

Nothing in the transport describes an app's keys, and nothing validates
them. The key table for each watchapp lives in `apps/pebble/`, one file
per app. If there is no file, the keys have to come from the watchapp's
own `package.json` (`messageKeys`) and its inbox handler — reading the C
is the only way to learn which keys do nothing, which is what a ping
needs.

## Watchapps that cannot be reached at all

Three ways an app is quietly unreachable, all of them silent:

- It is a **system app**. Stock watchapps cannot be sent to at all; the
  only route is a sideloaded copy or fork.
- It **declares an Android companion app**, which routes sends through a
  newer path that checks the calling package. Ours is not it.
- No watch is connected. This one *is* caught: every watch tool preflights
  the link and fails fast with a message naming it.

PKJS-only apps (JavaScript companion, no Android app) are fine.
