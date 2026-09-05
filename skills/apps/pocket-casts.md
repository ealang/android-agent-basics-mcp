# Pocket Casts

- **Package**: `au.com.shiftyjelly.pocketcasts`
- **Tier**: open — browse, search, play, queue all work
- **Survives disconnect**: yes (independent foreground service)
- **Verified on device**: 2026-09-02/03

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

- `playFromMediaId` — plays, resumes saved position.
- `playFromSearch` — free text resolved to the right show, starts an
  episode. Works from cold start with screen locked.
- Session search (`media_search`) — implemented.
- `pause` / `play` — work.
- **Queue**: read works (full Up Next), `queue:<id>` (skipToQueueItem)
  works and resumes that episode's saved position. "Next episode" = read
  queue, jump to the entry after the current one.
- Custom actions: `jumpBack`, `jumpFwd` (confirmed audibly), `changeSpeed`,
  `star`, `markAsPlayed`. **Case-sensitive** — `jumpfwd` is silently
  dropped, `jumpFwd` works.

## What doesn't

- `skipToNext` — silently ignored (honestly: not advertised). Use the
  queue-jump pattern instead.
