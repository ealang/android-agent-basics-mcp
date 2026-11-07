---
name: android-simple-radio
description: Simple Radio streaming app. Play any station by name or from favourites and recents, from a dead process with the screen locked; pause, step through recent stations. A stream keeps going once started.
verified: 2026-11-07
---

# Simple Radio

Package `com.streema.simpleradio`. Open browser route: any station plays
by name, including stations in none of the user's lists, or by id from
the favourites, recents and recommended lists, from a dead process with
the screen locked. The stream continues after the connection closes.
Now-playing state is split between two read-backs, below.

| Capability | Simple Radio |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `media_play` by name or id |
| Resume, pause | ✅ ✅ |
| Play by name | ✅ any station, resolved server-side · a nonsense query also answers PLAYING |
| Play by id or URL | ✅ id from favourites, recents or recommended · URL: not tested |
| Transport | ✅ previous · ✅ next, through the recent stations · ❌ seek, ignored · ❌ jump · ❌ speed, ignored |
| Now playing | ✅ split: title from `media_sessions` while playing, state from a command read-back |
| Queue | ✅ read, the recent stations · ✅ jump to an entry · add, move, remove: not tested |
| Library | ✅ recents, favourites, recommended |
| Search | ❌ `media_search` answers with an error |
| Also | favourite the current station, custom action `ACTION_FAVORITE`: not tested |

## Play a station

```
media_play {"app": "com.streema.simpleradio", "query": "<station name>"}
```

The name is resolved server-side and the station switches. This is the
route to use for any station; the tree only covers the user's own lists:

```
media_browse {"app": "com.streema.simpleradio", "node": "__FAVORITE__"}
media_play   {"app": "com.streema.simpleradio", "media_id": "<id>"}
```

Root nodes are `__RECENT__`, `__FAVORITE__` and `__RECOMMENDED__`.
Station ids are node-prefixed numerics such as `__FAVORITE__|<n>`.

## Pause, next, previous

```
media_command {"app": "com.streema.simpleradio", "action": "pause"}
media_command {"app": "com.streema.simpleradio", "action": "play"}
media_command {"app": "com.streema.simpleradio", "action": "next"}
media_command {"app": "com.streema.simpleradio", "action": "queue"}
media_command {"app": "com.streema.simpleradio", "action": "queue:<index>"}
```

`next` and `previous` step through the queue, which is the recently
listened stations, index-numbered; `queue:<index>` jumps to one. `seek:`
and `speed:` are accepted and ignored.

## Now playing

The app's state is read in two halves:

- `media_sessions` lists the app only while a stream is playing, with
  the station title but "no state" and no advertised actions. Once
  paused the session disappears from the list.
- A `media_command` read-back carries the state (PLAYING, buffering,
  paused) and a position that means nothing for a live stream, never
  the title.

So the title comes from `media_sessions` while playing, and the state
from a command read-back.
