---
name: android-aimp
description: AIMP local music player. Resume from a dead process with the screen locked, browse the library and playlists, play any track by id or exact title, pause, skip, seek, jump to a playlist entry; one service-start call before the first play. No speed, no queue editing, no search.
verified: 2026-11-07
---

# AIMP

Package `com.aimp.player`. Open browser route over the whole local
library, with one precondition: the player service has to be *started*
before anything is played through the browser route. AIMP's single
service is both its MediaBrowserService and its playback engine, and it
never promotes itself out of the bound state on its own, so a
`media_play` against a stopped service fails with an error and nothing
plays. One `send_intent` at the service first, and everything else
works: play by id, pause, resume, skip, seek, jump within the playlist.
Playback continues after the connection closes.

| Capability | AIMP |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `com.aimp.service.action.play` to the service resumes the last track |
| Resume, pause | ✅ ✅ |
| Play by name | ✅ exact track title · a query matching nothing starts some other track rather than failing |
| Play by id or URL | ✅ playlist entry, playlist resume item · ✅ library file, and it replaces the current playlist with the file's folder · URL: not tested |
| Transport | ✅ previous track · ✅ next track · ✅ seek · ✅ jump, as a relative seek · ❌ speed, ignored |
| Now playing | ✅ title, position, duration, rating type |
| Queue | ✅ read, the current playlist · ✅ jump to an entry · ❌ add, ignored · ❌ move · ❌ remove, ignored |
| Library | ✅ device folders, albums, artists, genres, tracks, playlists |
| Search | ❌ `media_search` answers with an error |
| Also | ✅ repeat mode toggle · stop the service |

## Start the service

```
send_intent
{
  "mode": "foreground_service",
  "component": "com.aimp.player/com.aimp.player.service.AppService",
  "action": "com.aimp.service.action.play"
}
```

This starts the service and resumes whatever AIMP last played, at its
saved position, within a few seconds. It is the whole call for "play my
music". For a specific track, follow it with a `media_play`.

## Browse

```
media_browse {"app": "com.aimp.player"}
media_browse {"app": "com.aimp.player", "node": "<id>"}
```

Root holds Folders (`folder`), My Music (`ml`) and the playlists, each
`playlist/<uuid>`.

- Folders is the device storage tree, one `folder/local://primary/<path>`
  directory per folder, with a "Play with sub-folders" item at each
  level. Tracks are `folder/local://primary/<path>/<file>`.
- My Music holds Albums, Artists, Genres and Tracks, each a
  `folder/musicdb://local/...` directory with a "Play" item first, then
  the tracks as `folder/local://...` file ids.
- A playlist directory's first entry is `playlist:resume/<uuid>`, then
  its tracks as `playlist:track/<uuid>/<n>`, zero-based.

## Play

```
media_play {"app": "com.aimp.player", "media_id": "playlist:track/<uuid>/<n>"}
media_play {"app": "com.aimp.player", "media_id": "playlist:resume/<uuid>"}
media_play {"app": "com.aimp.player", "query": "<exact track title>"}
```

With the service started, a playlist entry id switches to that track
and keeps playing after the call returns; the resume item resumes the
playlist where it left off. An index past the playlist's end answers
"error: unspecified" with an empty title.

Playing a library file id (`folder/local://...`) plays that file **and
replaces the current playlist's contents with the file's folder**; the
previous entries are gone and nothing through the session puts them
back. Play playlist entries unless replacing the playlist is intended.

`query` with an exact track title switches to that track. A query that
matches nothing does not fail: it starts some other track from the
playlist, so a misspelt title changes what is playing.

## Transport

```
media_command {"app": "com.aimp.player", "route": "session", "action": "pause"}
media_command {"app": "com.aimp.player", "route": "session", "action": "play"}
media_command {"app": "com.aimp.player", "route": "session", "action": "next"}
media_command {"app": "com.aimp.player", "route": "session", "action": "previous"}
media_command {"app": "com.aimp.player", "route": "session", "action": "seek:<seconds>"}
media_command {"app": "com.aimp.player", "route": "session", "action": "seek:+30"}
```

`next` and `previous` skip tracks. `seek:` takes an absolute second
count or `+N`/`-N`. `speed:` is accepted and ignored.

Service actions, delivered the same way as the start call with the
action swapped: `com.aimp.service.action.play`, `.nextTrack`, `.stop`.
`.stop` stops the service: the session reads "connecting" with an empty
title, and a `media_play` after it answers "error: unspecified" until
the start call is sent again.

The session advertises play, pause, stop, skip_to_next, skip_to_previous,
skip_to_queue_item, fast_forward, rewind, seek_to, play_from_media_id,
play_from_search, play_from_uri and set_rating, plus the custom actions
toggleLiked, exit, prevGroup, nextGroup, toggleRepeatMode and
toggleShuffleMode under their full `com.aimp.service.action.` names.
`toggleRepeatMode`, sent as the action verbatim, cycles the repeat mode;
the read-back's label for it shows the current mode.

## Playlist as queue

```
media_command {"app": "com.aimp.player", "route": "session", "action": "queue"}
media_command {"app": "com.aimp.player", "route": "session", "action": "queue:<index>"}
```

The queue read is the current playlist, titles index-numbered;
`queue:<index>` jumps to that entry and plays. `queue_add:` in both
forms and `queue_remove:` are accepted and leave the playlist unchanged.
