---
name: android-simple-radio
description: Simple Radio streaming app. Play any station by name, play favourites and recents, pause; a stream keeps going once started.
verified: 2026-09-02
---

# Simple Radio

Package `com.streema.simpleradio`. Any station plays by name through the
browser route, including stations not in the small browse tree, and the
stream continues after the connection closes.

## Play a station

```
media_play {"app": "com.streema.simpleradio", "query": "<station name>"}
```

The name is resolved server-side and the station switches. This is the
route to use; the tree only covers the user's own lists:

```
media_browse {"app": "com.streema.simpleradio", "node": "__FAVORITE__"}
media_play   {"app": "com.streema.simpleradio", "media_id": "<id>"}
```

Root nodes are `__RECENT__`, `__FAVORITE__` and `__RECOMMENDED__`.
Station ids are node-prefixed numerics such as `__FAVORITE__|<n>`.

## Pause, state, favourite

```
media_command {"app": "com.streema.simpleradio", "action": "pause"}
```

The advertised actions change with state: while paused, `pause` drops
out and `play` remains, so the advertisement is a reliable state signal.
Read-backs carry no title while a stream plays, only the state. One custom action, ending `ACTION_FAVORITE`, appears in the
read-back; send it verbatim to favourite the current station.
