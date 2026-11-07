---
name: android-podcast-addict
description: Podcast Addict podcast app. Resume, play any episode of any subscribed show by id, play a downloaded one by name, next and previous episode or chapter, jump, seek, set speed, download, refresh the feeds, remove the current playlist entry; works cold with the screen locked.
verified: 2026-11-07
---

# Podcast Addict

Package `com.bambuna.podcastaddict`. Open browser route: browse, play
any episode by id, resume, pause, seek and jump, from a dead process
with the screen locked. An exported receiver takes broadcasts for a feed
refresh, downloads, next and previous episode or chapter, jumps, speed
and dropping the current playlist entry. Play by name reaches
downloaded episodes only. Playback continues after the connection
closes.

| Capability | Podcast Addict |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `play` revives it at the saved position |
| Resume, pause | ✅ ✅ `play` toggles |
| Play by name | ✅ downloaded episodes, by episode or show name · ❌ anything not downloaded |
| Play by id or URL | ✅ id from a browse, any episode of any subscribed show · URL: not tested |
| Transport | ✅ previous episode · ✅ next episode, both by broadcast · ✅ seek · ✅ jump, +30 s and −15 s · ✅ speed 1x, 1.5x, 2x by broadcast |
| Now playing | ✅ title, position, duration, speed; the title is the chapter name when the episode has chapters |
| Queue | ✅ read · ✅ jump to an entry, as play by id · ✅ add, as play by id · ❌ move · ✅ remove the current entry, by broadcast · ❌ remove any other entry |
| Library | ✅ subscriptions, recent episodes, downloads, categories; a show lists its latest 200 episodes |
| Search | ❌ browser search refused, session search covers downloads only |
| Also | ✅ refresh the feeds · ✅ download an episode · ✅ next and previous chapter · ✅ jump to the top of the playlist |

## Resume

```
media_command {"app": "com.bambuna.podcastaddict", "action": "play"}
```

`play` is a toggle: it resumes the loaded episode at its saved position,
from a `stopped` session or a dead process alike, and pauses one that is
playing. `pause` only pauses.

## Play an episode by id

```
media_browse {"app": "com.bambuna.podcastaddict", "node": "__PODCASTS__"}
media_browse {"app": "com.bambuna.podcastaddict", "node": "<show id>"}
media_play   {"app": "com.bambuna.podcastaddict", "media_id": "<episode id>"}
```

Play by id loads the episode, appends it to the end of the playlist and
starts it at its saved position, streaming it if it is not downloaded.
Ids are plain numeric database keys, opaque and not guessable; browse to
obtain them. The session can report `stopped` with the new title for
several seconds after the command while the stream buffers, then
`PLAYING`.

## Play by name

```
media_play {"app": "com.bambuna.podcastaddict", "query": "<episode or show>"}
```

Matches downloaded episodes only, by a word of the episode title or the
show name, and starts the match at its saved position. Anything not
downloaded, a subscribed show included, answers "No search result" and
leaves the session in an error state that the next command clears.
`media_search` is refused outright.

## Tree

```
media_browse {"app": "com.bambuna.podcastaddict"}
media_browse {"app": "com.bambuna.podcastaddict", "node": "<id>", "offset": 40}
```

Root `__ROOT__` holds `__CURRENT_PLAYLIST__` (the playlist, in order),
`__AUDIO_PLAYLIST__`, `__CUSTOM_PLAYLIST__` (the user's categories, each
a directory), `__DOWNLOADED__`, `__PODCASTS__` (every subscribed show as
a directory with its episode count) and `__RECENT_EPISODES__`. Episode
entries carry show, duration and age. A show's directory lists its
latest 200 episodes, newest first, 40 per page through `offset`, however
many the count says; episodes past the 200th are not reachable.

## Transport

```
media_command {"app": "com.bambuna.podcastaddict", "action": "pause"}
media_command {"app": "com.bambuna.podcastaddict", "action": "seek:<seconds>"}
media_command {"app": "com.bambuna.podcastaddict", "action": "com.bambuna.podcastaddict.FASTFORWARD"}
media_command {"app": "com.bambuna.podcastaddict", "action": "com.bambuna.podcastaddict.REWIND"}
```

- `seek:` takes an absolute second count or `+N`/`-N`.
- The custom actions take the full package-prefixed id: `FASTFORWARD`
  is +30 s, `REWIND` −15 s, `STOP` ends playback and leaves a `stopped`
  session that `play` revives. The session's `next` and `previous` are
  the same two jumps, not episode changes.
- `speed:<x>` on the session is accepted and ignored. Speed is set by
  broadcast, below.

## Broadcasts: refresh, download, episode, chapter, speed, playlist

```
send_intent
{
  "action": "com.bambuna.podcastaddict.service.player.nexttrack",
  "package": "com.bambuna.podcastaddict",
  "mode": "broadcast"
}
```

The same call with another action, all prefixed
`com.bambuna.podcastaddict.service.`; `update` is the one exception, it
goes to a different receiver but is sent exactly the same way:

- `player.nexttrack`, `player.previoustrack` — move through the
  playlist; each episode resumes at its saved position.
- `player.playfirstepisode` — jump to the top of the playlist.
- `player.nextchapter`, `player.previouschapter` — move between the
  episode's chapters; the session title changes to the chapter's name.
- `player.deletecurrentskipnexttrack`,
  `player.deletecurrentskipprevioustrack` — drop the current entry from
  the playlist and move to the next or previous one. The episode's
  download, if any, is deleted with it; the episode stays in its show's
  list.
- `player.jumpforward` (+30 s), `player.jumpbackward` (−15 s).
- `player.1xspeed`, `player.1.5xspeed`, `player.2xspeed` — set the
  speed; `media_sessions` reports `Speed: 1.5x` at anything but 1x,
  where the field is absent.
- `download.episode` with the `episodeId` long extra — download the
  episode. It appears under `__DOWNLOADED__` within seconds, while the
  download is still running, and is listed there until the file is
  deleted.
- `update`, sent to the same package without extras — refresh every
  subscribed show. New episodes appear in the show's directory and
  under `__RECENT_EPISODES__` within about a minute; there is no other
  read-back.

## Now playing

`media_sessions` reports state, title, position, duration and speed.
For an episode with chapters the title is the current chapter's name,
not the episode's; the episode is identified by its duration, or by
browsing `__CURRENT_PLAYLIST__`.

## Playlist

The playlist is read through `__CURRENT_PLAYLIST__` and any entry is
played with `media_play` by id. Playing an episode from elsewhere in the
tree by id appends it to the end of the playlist, which is the only way
to add one. The only removal is of the entry currently loaded, with the
`deletecurrentskip…` broadcasts above. Nothing reorders an entry: the
session exposes no queue, so `queue`, `queue:<id>`, `queue_add:` and
`queue_remove:` are refused ("doesn't support queue management
operations"), and the receiver's `playlist.dequeue` broadcast does
nothing, bare or with an `episodeId` extra.
