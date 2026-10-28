---
name: android-podcast-addict
description: Podcast Addict podcast app. Browse the library, play an episode by id or by free-text search, pause, rewind and fast-forward.
verified: 2026-09-02
---

# Podcast Addict

Package `com.bambuna.podcastaddict`. Fully open browser route: browse,
play by id, play by free text, pause, rewind and fast-forward. Playback
continues after the connection closes.

## Play

```
media_play {"app": "com.bambuna.podcastaddict", "query": "<show or episode>"}
media_play {"app": "com.bambuna.podcastaddict", "media_id": "<id>"}
```

Ids are plain numeric database keys, opaque and not guessable; browse to
obtain them:

```
media_browse {"app": "com.bambuna.podcastaddict"}
media_browse {"app": "com.bambuna.podcastaddict", "node": "<id>"}
```

## Transport

```
media_command {"app": "com.bambuna.podcastaddict", "action": "pause"}
media_command {"app": "com.bambuna.podcastaddict", "action": "com.bambuna.podcastaddict.REWIND"}
media_command {"app": "com.bambuna.podcastaddict", "action": "com.bambuna.podcastaddict.FASTFORWARD"}
media_command {"app": "com.bambuna.podcastaddict", "action": "com.bambuna.podcastaddict.STOP"}
```

The custom actions take the full package-prefixed id.
