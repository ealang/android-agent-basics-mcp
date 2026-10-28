---
name: android-media-control
description: How to drive any media app through the media browser route, the live-session route, and the targeted media-button intent that revives an app neither route can reach. Read before using the media tools on an app without its own skill file.
---

# Media control — driving apps through their media sessions

Two mechanisms, one grammar — plus a third, built from `send_intent`,
for the case both mechanisms lose.

**The browser route** — behind `media_apps`, `media_browse`,
`media_search`, `media_play`, and `media_command`. This is Android Auto's
route: bind the app's MediaBrowserService, get its browse tree and media
session, and send transport commands through the session. Connecting wakes
the app's process with **no activity, no launch, and no keyguard
involvement** — playback starts on a locked phone from a cold app. Its
limit is the door: each app decides in `onGetRoot` who may connect, and a
signature-allowlisted app refuses this route forever.

**The session route** — behind `media_sessions`, `media_key`, and
`media_command` with `route: session`. Requires the notification-access
grant. `getActiveSessions` hands over a controller for every **live**
session on the phone regardless of the app's browser policy — this is how
refused-tier apps (YouTube Music) get steered. Its limit is liveness: a
session exists while the app plays or sits freshly paused, and this route
can never cold-start anything.

**The targeted media button** — not a `media_*` tool but a `send_intent`
recipe, for the app both routes lose: browser refused, session dead.
`media_key` exists but is untargetable, so build the press by hand and
address it:

```
send_intent
  mode: foreground_service        # plain service: Android refuses cross-app
  component: <pkg>/<its media service>   # from inspect (MEDIA_BUTTON filter)
  action: android.intent.action.MEDIA_BUTTON
  extras: [{key: android.intent.extra.KEY_EVENT, type: key_event, value: play}]
```

One intent: starting the service is the delivery, and the `key_event`
extra is the press — without it the intent is an empty envelope, since
MEDIA_BUTTON only says *a* button was pressed and the KeyEvent says
which. This is the system's own headset-revival mechanism, aimed instead
of routed: it wakes the app's process and resumes last content with no
live session, no unlock, no audio-focus permission. Proven on YouTube
Music from a stopped session with the screen locked (2026-09-26); a
broadcast at the same app's exported MediaButtonReceiver was delivered
and ignored, so prefer the service delivery. Whether an app honors it is
per-app data; force-stopped apps stay unreachable even here.

MEDIA_BUTTON is one instance of the general move: **`inspect` the
app for whatever service-level control surface it exports, and start
the service with it via `foreground_service`.** Some apps declare a
private action vocabulary instead of (or richer than) the media-button
contract — Smart AudioBook Player has thirteen named actions including
bookmark and sleep timer, all reachable from a dead process with no
extras at all (see its per-app file).

## The tools

- `media_apps` — enumerate every installed app declaring a
  MediaBrowserService. An app absent from this list cannot be driven by
  this mechanism at all.
- `media_browse {app, node?, offset?}` — list a tree node's children.
  Omit `node` for the root. Results mark items `[dir]` or `[playable]`
  and include the id needed to play them.
- `media_search {app, query}` — the session's own search
  (`onSearch`). Most apps don't implement it; per-app files say which do.
- `media_play {app, media_id?, query?}` — `media_id` calls
  `playFromMediaId`; `query` calls `playFromSearch`. The result includes a
  3-second read-back of the session state.
- `media_command {app, action, route?}` — everything else. Actions:
  `play`, `pause`, `stop`, `next`, `previous`, `queue`, `queue:<id>`
  (skipToQueueItem), `seek:<seconds|+N|-N>`, `speed:<x>`, `rate:<spec>`,
  `uri:<uri>` (playFromUri), `search:<query>` (playFromSearch),
  `media:<id>` (playFromMediaId), `queue_add:<id>`,
  `queue_add:<pos>:<id>`, `queue_remove:<id>`; anything else is sent
  verbatim as a **custom action** (app-defined, case-sensitive ids — see
  per-app files). `route: session` targets a live session instead of
  connecting the browser service; queue edits are browser-only.
- `media_sessions` — list every live session on the phone with state,
  title, position, and advertised actions. Sees apps the browser route
  cannot connect to. Needs notification access.
- `media_key {key}` — press a system media button (`play`, `pause`,
  `play_pause`, `next`, `previous`, `stop`, `fast_forward`, `rewind`),
  the car/headset mechanism. **Untargetable by design**: the system
  routes the press to whatever it considers the most recent media app,
  and can revive that app from swiped-away just as a headset button
  does (verified: YouTube Music, 2026-10-03). A reasonable default for
  a bare "play / pause / skip" request — but **not all apps respect
  it** (Pocket Casts ignores the revival, and a press at its paused
  player tears the player down), so use the per-app skill file when
  one exists, and prefer `media_command route: session` whenever a
  target can be named. An app actively holding the audio session (a
  voice assistant mid-conversation) can capture the press instead.
  Needs no grant to send; the read-back needs notification access.

## Connection tiers

Three separate per-app policies exist: accept the connection at all, show
a stranger the tree, honor a play command. Observed tiers:

1. **Open** — connection accepted, tree browsable, play honored. Most
   well-behaved media apps.
2. **Degraded** — connection accepted but the root is deliberately empty
   ("browse withheld" convention), and/or playback is refused by policy
   (YouTube: Premium paywall).
3. **Refused** — `onGetRoot` returns null. These apps keep a signature
   allowlist (Android Auto, Wear) and there is **no way to get in**; don't
   retry, don't look for a workaround on this route.

## The proven play pattern

1. **Fresh browse** the tree and lift the target's id **verbatim**. Never
   reconstruct, edit, or hand-build an id unless the app's file says ids
   are durable (Libro.fm's ISBNs yes; Substack's JSON blobs no — forgeries
   are refused at play time and fall back to the last-loaded item).
2. `media_play` with `media_id`.
3. **Verify by position, not by "sent"**: the read-back should show state
   PLAYING and a position that advances (e.g. "Position: 2s of 1356s").
   A stale title with position 0 means the app refused the id.

`playFromSearch` (free text) is even simpler where honored — some apps
resolve text server-side and start playing with no browse at all.

## Transport, queue, and state

- The session **advertises** its supported actions in every read-back.
  An unadvertised command is silently ignored, not errored — trust the
  advertisement. Some apps update it live (paused ⇒ `pause` drops out).
- **Queue read** (`media_command action=queue`) returns the current queue
  with ids; `queue:<id>` jumps to an entry. This is the reliable way to do
  "next episode" in apps that don't advertise skip_to_next.
- **Queue write** (`queue_add`) works where advertised but check the
  per-app file: queue durability varies (Substack's queue is per-session
  and evaporates when the process dies).
- Custom actions are app-specific verbs (skip-forward, change speed,
  favorite…) listed in each read-back; ids are **case-sensitive**.

## Universal caveats

- **Lifecycle**: most apps promote themselves to an independent foreground
  service and keep playing after we disconnect. At least one (AIMP) does
  not — its playback lives only while a browser client stays bound. Check
  the per-app file before promising background playback.
- **A "PLAYING" state is not audio.** Position advancing over consecutive
  read-backs is the strongest remote signal; only the user confirms sound.
- Media ids may be stable public identifiers, opaque database keys, or
  signed/minted blobs. The per-app file states which; it decides whether
  ids can be stored long-term in a skill or must be re-browsed each time.
- **`paused` is drivable; `stopped` is a dead handle.** A stopped session
  still shows metadata but ignores every command — fall back to launching
  the app. And a paused reading during an app's teardown is not proof of
  resumability; only a command's read-back tells. Sessions also decay
  fast: losing an audio-focus fight can erase one within a minute.
- **Force-stopped apps are unreachable on every route** (platform
  stopped-state: no broadcasts, no media-button restarts) until the user
  opens them again.
