---
name: android-smart-audiobook
description: Smart AudioBook Player. Play, pause, jump, change file, bookmark and set the sleep timer by firing its service actions from a dead process; play any book in the library by name.
verified: 2026-09-26
---

# Smart AudioBook Player

- **Package**: `ak.alizandro.smartaudiobookplayer`
- **Tier**: browser route refused (signature allowlist); **fully drivable
  anyway** — its player service exports named actions as plain intent
  filters, and the live session honors `play_from_search`, so any book
  plays by name from a dead process
- **Verified on device**: 2026-09-26 (v11.9.0)

Mechanisms: [media-control.md](../media-control.md).

## The service actions — the whole remote control, one intent each

The app has **no MEDIA_BUTTON listener at all** (no MediaButtonReceiver,
no MEDIA_BUTTON filter on any service), so the YT Music revival recipe
does not apply. It doesn't need to: `PlayerService` declares thirteen
named actions, and `send_intent` fires them directly — no KeyEvent
extra, the action name *is* the command:

```
send_intent
  mode: foreground_service
  component: ak.alizandro.smartaudiobookplayer/ak.alizandro.smartaudiobookplayer.PlayerService
  action: ak.alizandro.smartaudiobookplayer.ActionPlay
```

Verified 2026-09-26: with no live session anywhere, this one call
resumed the current book — PLAYING, position advancing, at the app's
saved speed.

The full action vocabulary (prefix each with
`ak.alizandro.smartaudiobookplayer.`):

| Action | Effect |
|---|---|
| `ActionPlay` / `ActionPause` / `ActionPlayPause` | transport |
| `ActionRewindBig` / `ActionRewindSmall` | jump back (app-configured sizes) |
| `ActionFwdSmall` / `ActionFwdBig` | jump forward |
| `ActionPrevFile` / `ActionNextFile` | file within the book |
| `ActionAddBookmark` | bookmark at current position |
| `ActionEnableSleepTimer` / `ActionDisableSleepTimer` | sleep timer |
| `ActionExit` | stop and tear down |

Note what this buys over any session route: bookmark and sleep timer are
reachable **even with no live session** — verbs the media-session
grammar has no words for.

Only `ActionPlay` is verified so far; the rest are the same filter on
the same service, declared in the same manifest block.

## Any book by name — `play_from_search` is honored

Verified 2026-09-26: on the live session,

```
media_command app: "smart audiobook", route: session,
  action: "search:<book title>"
```

switched playback to the named book within the 3s read-back ("PLAYING,
<title>, 2s of 90141s"). Free text against the local
library, no ids, no URLs. A non-matching outcome is silent (the session
stays on the current book and keeps playing — no error state), so
verify by title in the read-back, not by "sent".

Chained with the revival intent: **"play book X" works from a dead
process** — `ActionPlay` → wait for the session → `search:<title>`.

## While a session is live

- `media_sessions` shows it: state, book title, position, speed.
- Advertised: play, pause, play_pause, play_from_media_id,
  play_from_search. Custom actions mirror three of the service actions
  (ActionRewindSmall, ActionFwdSmall, ActionExit) — with the service
  route available there is no reason to prefer them.

## Seeking: no absolute seek — jumps and file boundaries

`seek_to` is **not advertised and genuinely ignored** (`seek:0` on a
playing session: position kept advancing — verified 2026-09-26). The
seeking vocabulary is the service actions, all fired with the
`send_intent` recipe above (only the action name changes):

- **`ActionPrevFile` restarts the current file** — verified: one press
  from 43s in landed at the file's start. The conventional prev-track
  contract; presumably a second press within the first seconds crosses
  to the prior file (untested). This is the one-press "back to the
  beginning of the chapter".
- `ActionRewindBig` / `ActionFwdBig` — ≈60s per press (app-configured,
  measured 2026-09-26). For arbitrary offsets: repeat, verifying
  position in the read-back between presses.
- `ActionRewindSmall` / `ActionFwdSmall` — smaller, app-configured.

Quirk: **right after `ActionPrevFile`, a paused session's read-back
omits the position line entirely** — position is unset until playback
resumes. Not a dead session; send `play` and read again.

Note the jumps land audibly at each intermediate point while playing —
this is the widget jump button pressed repeatedly, not a seek bar.

## Not usable / untested

- **Browser route refused** — `onGetRoot` signature allowlist, confirmed
  2026-09-02 and 2026-09-04. Don't re-probe.
- Untested: session `play_from_media_id` (advertised; id format unknown
  and now redundant — search does the job), and `MEDIA_PLAY_FROM_SEARCH`
  at its `PlayerActivity` (also redundant).
- Untested: revival from screen locked and from a naturally
  long-dead process (the verified runs had the screen unlocked).
- Force-stopped remains unreachable, as everywhere.
