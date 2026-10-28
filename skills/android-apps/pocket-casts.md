---
name: android-pocket-casts
description: Pocket Casts podcast app. Resume or start playback, play any subscribed show or episode by name or id, read and jump within the Up Next queue, change speed; all of it works cold with the screen locked.
verified: 2026-10-02
---

# Pocket Casts

- **Package**: `au.com.shiftyjelly.pocketcasts`
- **Tier**: open — browse, search, play, queue all work
- **Survives disconnect**: yes (independent foreground service)
- **Verified on device**: 2026-09-02/03; queue browse + play-by-id +
  session read re-verified 2026-10-01; cold-start `play` and the
  media_key failures 2026-10-02

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## Tree

Root `__ROOT__`: `__PODCASTS__` (all subscribed shows as dirs), New
Releases (uuid dir, 100 eps), In Progress (uuid dir), `__DOWNLOADS__`,
`__FILES__`.

**Hidden node `__UP_NEXT__`** — never listed, but browsable: returns the
real Up Next queue.

## Ids

`<folder-uuid>#<episode-uuid>`; Downloads entries are `#<uuid>`. Opaque
but honored verbatim; re-browse rather than storing long-term.

## What works

- **Resume / revive: bare `play` (browser route, the default).**
  Works paused, and cold-starts the app from fully swiped away,
  resuming the saved position (the app applies its own courtesy
  rewind). This is the recipe; no search or media id needed.
- `playFromMediaId` — plays, resumes saved position.
- `playFromSearch` — free text resolved to the right show, starts an
  episode. Works from cold start with screen locked. Fallback — use
  it to play something *other* than the current episode.
- Session search (`media_search`) — implemented.
- `pause` — works (session or browser route).
- **Queue**: read works (full Up Next), `queue:<id>` (skipToQueueItem)
  works and resumes that episode's saved position. "Next episode" = read
  queue, jump to the entry after the current one.
- Custom actions: `jumpBack`, `jumpFwd` (confirmed audibly), `changeSpeed`,
  `star`, `markAsPlayed`. **Case-sensitive** — `jumpfwd` is silently
  dropped, `jumpFwd` works.

## What doesn't

- `skipToNext` — silently ignored (honestly: not advertised). Use the
  queue-jump pattern instead.
- **`media_key` — do not use on Pocket Casts.** Swiped away: no
  resurrection. Paused with the notification up: the press tears the
  player down (notification vanishes, nothing plays) and leaves a
  `stopped` session that still advertises `play` but ignores a
  session-route `play`. Revive with the bare browser-route `play`
  above instead.
