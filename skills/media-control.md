# Media control — driving apps through their MediaBrowserService

The mechanism behind `media_apps`, `media_browse`, `media_search`,
`media_play`, and `media_command`. This is Android Auto's route: bind the
app's MediaBrowserService, get its browse tree and media session, and send
transport commands through the session. Connecting wakes the app's process
with **no activity, no launch, and no keyguard involvement** — playback
starts on a locked phone from a cold app.

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
- `media_command {app, action}` — everything else. Actions:
  `play`, `pause`, `stop`, `next`, `previous`, `queue`, `queue:<id>`
  (skipToQueueItem), `seek:<seconds|+N|-N>`, `speed:<x>`, `rate:<spec>`,
  `uri:<uri>` (playFromUri), `queue_add:<id>`, `queue_add:<pos>:<id>`,
  `queue_remove:<id>`; anything else is sent verbatim as a **custom
  action** (app-defined, case-sensitive ids — see per-app files).

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
