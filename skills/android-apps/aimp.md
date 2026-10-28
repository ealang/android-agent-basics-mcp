---
name: android-aimp
description: AIMP local music player. Browse the library and playlists and read the session; sustained playback is not possible, because audio stops the moment the connection closes.
verified: 2026-09-03
---

# AIMP

Package `com.aimp.player`. The browser route connects and the library
browses fully, but playback started this way stops the instant the
connection closes, about three seconds after the call: AIMP never
promotes itself to an independent service, so the session lives only
while a browser client stays bound, which phone-mcp cannot do. Browse
and inspect, do not play.

## Browse

```
media_browse {"app": "com.aimp.player"}
media_browse {"app": "com.aimp.player", "node": "playlist/<uuid>"}
```

Root holds Folders, My Music and the playlists, each a `playlist/<uuid>`
directory of tracks. The whole library is listed to any client,
including voice memos stored as tracks.

## Playback

```
media_play {"app": "com.aimp.player", "media_id": "<id>"}
```

Yields a read-back of PLAYING and about three seconds of audio, then
silence as the connection closes. The transport and custom actions the
session advertises are equally short-lived. Do not promise playback.
