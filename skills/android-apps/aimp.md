---
name: android-aimp
description: AIMP local music player. Play any track from the library hands-free with the screen locked, pause, resume, skip to the next track; needs one service-start call before the play call.
verified: 2026-10-29
---

# AIMP

Package `com.aimp.player`. Everything works, with one precondition: the
player service has to be *started* before anything is played through the
browser route. AIMP's single service is both its MediaBrowserService and
its playback engine, and it never promotes itself out of the bound state
on its own, so playback begun by a browser client alone ends the moment
the connection closes, about three seconds after the call. One
`send_intent` at the service first, and playback outlives every
connection; pause, resume and next track then work.

## Start the service

```
send_intent
{
  "mode": "foreground_service",
  "component": "com.aimp.player/com.aimp.player.service.AppService",
  "action": "com.aimp.service.action.play"
}
```

This starts the service and resumes whatever AIMP last played. It is the
whole call for "play my music". For a specific track, follow it with a
`media_play`.

## Browse and play

```
media_browse {"app": "com.aimp.player"}
media_browse {"app": "com.aimp.player", "node": "playlist/<uuid>"}
media_play   {"app": "com.aimp.player", "media_id": "<id>"}
```

Root holds Folders, My Music and the playlists, each a `playlist/<uuid>`
directory whose first entry is a `playlist:resume/<uuid>` item and whose
tracks are `playlist:track/<uuid>/<n>`. The whole library is listed to
any client, including voice memos stored as tracks. With the service
started, `media_play` of a track id switches to that track and keeps
playing after the call returns.

## Transport

Service actions, delivered the same way as the start call with the
action swapped: `com.aimp.service.action.play`, `.stop`, `.nextTrack`.
The session route is equivalent while a session exists:

```
media_command {"app": "com.aimp.player", "route": "session", "action": "pause"}
media_command {"app": "com.aimp.player", "route": "session", "action": "play"}
```

The session advertises play, pause, stop, skip_to_next, skip_to_previous,
skip_to_queue_item, fast_forward, rewind, seek_to, play_from_media_id,
play_from_search, play_from_uri and set_rating, plus the custom actions
toggleLiked, exit, prevGroup, nextGroup, toggleRepeatMode and
toggleShuffleMode under their full `com.aimp.service.action.` names.

## Without the service start

`media_play` or `media_command` alone, on a process whose service is not
started, yields a read-back of PLAYING and about three seconds of audio,
then silence as the connection closes. A `stop` leaves the service
stopped again, so the start call is needed before the next play.
