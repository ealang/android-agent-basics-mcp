# Driving a Pebble watchapp

The generic surface is three tools, and they map onto the Android app
tools closely enough that the analogy is worth keeping: `launch_watch_app`
is a launch intent, `send_watch_message` is `send_intent`, and there is no
read side at all.

## The tools

- `launch_watch_app(app)` / `stop_watch_app(app)` — `app` is a UUID or a
  short name the server knows. Fire-and-forget: the watch never
  acknowledges a launch, so a success here means the broadcast went out,
  nothing more.
- `send_watch_message(app, tuples, ensure_running_ping_key?)` — one
  AppMessage. `tuples` is an array of `{key, type, length?, value}` with
  `type` one of `int`, `uint`, `string`, `bytes`. Sent verbatim.

## The one rule that catches everyone

**A message to a watchapp that is not running is discarded in silence.**
The phone companion registers its send receiver per *running* watchapp, so
a closed app has no receiver — nothing errors, nothing arrives. Launching
is not enough on its own either, because a launch takes a second or two
and the send will lose the race.

Two ways to handle it:

- Pass `ensure_running_ping_key`. The server then launches the app and
  pings it on that key roughly once a second until the watch acknowledges
  (giving up at 10 s), and only then sends the real message. **The key must
  be one the target app is guaranteed to ignore** — it is sent repeatedly,
  so a key that does anything does it up to ten times.
- Or `launch_watch_app`, wait, and send. Cheaper to write, and a coin flip.

If you do not know an inert key for the app, you do not have a safe ping,
and the honest answer is that the send is best-effort.

## What an ACK proves

`send_watch_message` returns the watch's own answer, and it proves less
than it looks:

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
them. The key table for each watchapp lives in `apps/`, one file per app.
If there is no file, the keys have to come from the watchapp's own
`package.json` (`messageKeys`) and its inbox handler — reading the C is
the only way to learn which keys do nothing, which is what a ping needs.

## Watchapps that cannot be reached at all

Three ways an app is quietly unreachable, all of them silent:

- It is a **system app** — the stock Timer, for instance. This is why the
  repo carries a fork of it.
- It **declares an Android companion app**, which routes sends through a
  newer path that checks the calling package. Ours is not it.
- No watch is connected. This one *is* caught: every watch tool preflights
  the link and fails fast with a message naming it.

PKJS-only apps (JavaScript companion, no Android app) are fine.
