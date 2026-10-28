---
name: android-simple-radio
description: Simple Radio streaming app. Play any station by name, play favourites and recents, pause; a stream keeps going once started.
verified: 2026-09-02
---

# Simple Radio

- **Package**: `com.streema.simpleradio`
- **Tier**: open — browse and play work
- **Survives disconnect**: yes
- **Verified on device**: 2026-09-02

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## Tree and ids

Tiny tree: `__RECENT__`, `__FAVORITE__`, `__RECOMMENDED__`. Station ids
look like `__FAVORITE__|1719` (node-prefixed numeric).

## What works

- `playFromMediaId` — streams, keeps streaming after disconnect.
- `playFromSearch` — free text ("<station name>") resolved server-side,
  switched stations. This is the best route: stations not in the small
  tree are reachable by name.
- `pause` — works. The advertised-actions set updates **live** with state
  (while paused, `pause` drops out and `play` remains) — an honest
  dynamic bitmask, useful as a state signal.
- Custom action: `...ACTION_FAVORITE`.

## Quirks

- No metadata title in read-backs while playing ("Now loaded" absent) —
  don't rely on title verification for this app; the state and the
  user's ears are the signal.
