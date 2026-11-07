---
name: android-youtube-music
description: YouTube Music app. Resume playback from a dead process with the screen locked, play any song or playlist by its music.youtube.com URL, pause, next, previous, seek, like, shuffle, repeat, read the queue. Content is chosen by URL only, found out of band.
verified: 2026-11-06
---

# YouTube Music

Package `com.google.android.apps.youtube.music`. The browser route is
refused at connection by a signature allowlist, but the app is fully
drivable: a targeted media-button intent revives it from a dead process
with the screen locked, and the live session then plays any song or
playlist by `music.youtube.com` URL and takes transport. Content is
chosen by URL only; search, play-by-id and queue editing are accepted
by the session and ignored.

| Capability | YouTube Music |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, a targeted media-button intent revives it |
| Resume, pause | ✅ ✅ |
| Play by name | ❌ browser refused, `search:` ignored |
| Play by id or URL | ❌ id, `media:` ignored · ✅ any `music.youtube.com` song or playlist URL |
| Transport | ✅ previous · ✅ next · ✅ seek · ✅ jump, as a relative seek · ❌ speed, `speed:` ignored |
| Now playing | ✅ title, position, duration, rating; no artist or album |
| Queue | ✅ read, titles only, 25 entries · ❌ jump to an entry · ❌ add · ❌ move · ❌ remove, all ignored |
| Library | ❌ browser refused |
| Search | ❌ browser refused, `search:` ignored |
| Also | ✅ like, dislike, clear · ✅ shuffle on/off · ✅ repeat off/all/one |

## Revive

```
send_intent
{
  "mode": "foreground_service",
  "component": "com.google.android.apps.youtube.music/com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService",
  "action": "android.intent.action.MEDIA_BUTTON",
  "extras": [{"key": "android.intent.extra.KEY_EVENT", "type": "key_event", "value": "play"}]
}
```

Resumes whatever was last loaded, audibly, from a stopped session or no
session at all, screen locked, taking audio focus from any other app. A
session appears within about a second.

The other deliveries fall short: `"mode": "broadcast"` at the app's
MediaButtonReceiver is delivered and ignored; `"mode": "service"` is
refused by Android; `"value": "pause"` is delivered but publishes no
session, so only `play` revives.

## Play a song or playlist by URL

```
media_command
{
  "app": "com.google.android.apps.youtube.music",
  "route": "session",
  "action": "uri:https://music.youtube.com/watch?v=<id>[&list=<playlist id>]"
}
```

Works with the screen locked, playing or paused. If `media_sessions`
lists YouTube Music as playing or paused, send it. If the session is
absent or `stopped`, revive first, then send the URL as soon as the
session appears; the previous track plays until the URL lands, so the
shorter the gap, the shorter the blip. The read-back shows PLAYING with
the requested title within a few seconds; the first read can still show
`buffering` or an empty title. A song URL replaces the whole queue with
that song followed by the app's own picks.

The fallback is a VIEW intent at the same URL, which opens the app and
plays at once with no blip, but needs the screen:

```
send_intent
{
  "action": "android.intent.action.VIEW",
  "data": "https://music.youtube.com/watch?v=<id>[&list=<playlist id>]",
  "package": "com.google.android.apps.youtube.music",
  "mode": "activity",
  "via_unlock_gate": true
}
```

On a locked phone it plays the moment the user unlocks.

## Transport, rating, modes

```
media_sessions {}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "pause"}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "next"}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "seek:+30"}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "rate:up"}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "shuffle_action"}
```

- `pause`, `play`, `next` and `previous` all work. `previous` restarts
  the current track once it is a few seconds in; sent again straight
  away it goes to the previous track. `stop` pauses; the session stays.
- `seek:<seconds>` is absolute, `seek:+N`/`seek:-N` relative. There are
  no jump actions; a relative seek is the jump. `speed:<x>` is accepted
  and ignored.
- `rate:up`, `rate:down`, `rate:clear` set the thumbs rating. `rate:up`
  adds the track to the user's liked songs, and the Like custom action's
  label flips to "Undo like" in the read-back.
- Custom actions `shuffle_action` and `loop_mode_action` toggle: shuffle
  off/on, repeat off → all → one → off. Their labels in the read-back
  ("Shuffle on", "Repeat one") carry the current state and match the
  player screen; `thumbs_up_action` and `thumbs_down_action` are the
  same as `rate:`.
- The session reports state, title, position, duration and rating; no
  artist or album. A session can vanish within a minute of losing audio
  focus; revive when that happens.

## Queue

```
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "queue"}
```

Lists titles under numeric ids, current track first, capped at 25
entries. No artist or album, and the app's autoplay picks follow the
user's own tracks without a marker, so an album's end shows only as a
change of style, or as a title repeating. Shuffle reorders the list on
the next read. That is all the queue offers: `queue:<id>`, `queue_add:`
and `queue_remove:` are accepted and ignored, and `skip_to_queue_item`
is not advertised. The only way to change what plays next is `next` or
a new URL.

## Name to URL, out of band

- Fetch `https://music.youtube.com/search?q=<query>` with a desktop
  browser User-Agent: the HTML embeds video ids as `"videoId":"<id>"`
  and playlist ids (`list=` values starting `OLAK5uy_`). To tell
  playlists apart, fetch `https://www.youtube.com/playlist?list=<id>`
  and read its `<title>`; the page lists the playlist's video ids in
  order.
- Web-search `<artist or song> "music.youtube.com/watch"`.
- Video ids are shared with YouTube, so any `youtube.com/watch?v=<id>`
  result becomes `music.youtube.com/watch?v=<id>`; YouTube results are
  far more plentiful.
- YouTube Data API v3 `search.list` with `videoCategoryId=10` filters to
  official music; needs an API key.

## Refused or ignored

- The browser route is refused at connection, so `media_browse`,
  `media_search` and `media_play` all fail: no library, no search, no
  play by name.
- `search:<query>` and `media:<id>` on the session are accepted and
  ignored; playback continues untouched.
- `android.media.action.MEDIA_PLAY_FROM_SEARCH` brings the app to the
  foreground and never plays: bare, it ignores the query; targeted with
  the standard `focus`/`artist`/`album` extras it shows results and waits
  for a tap.
