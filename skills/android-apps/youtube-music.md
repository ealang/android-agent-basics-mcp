---
name: android-youtube-music
description: YouTube Music app. Resume playback from a dead process with the screen locked, play any song or playlist by its music.youtube.com URL, pause, play, skip. Content is chosen by URL only, found out of band.
verified: 2026-10-19
---

# YouTube Music

Package `com.google.android.apps.youtube.music`. The browser route is
refused at connection by a signature allowlist, but the app is fully
drivable: a targeted media-button intent revives it from a dead process
with the screen locked, and the live session then plays any song or
playlist by `music.youtube.com` URL and takes transport. Content is
chosen by URL only; search and play-by-id on the session are refused.

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
session appears within about a second. A force-stopped app stays
unreachable.

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

Works with the screen locked. If `media_sessions` lists YouTube Music as
playing or paused, send it. If the session is absent or `stopped`,
revive first, then send the URL as soon as the session appears; the
previous track plays until the URL lands, so the shorter the gap, the
shorter the blip. The read-back shows PLAYING with the requested title.

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

## Transport

```
media_sessions {}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "pause"}
media_command {"app": "com.google.android.apps.youtube.music", "route": "session", "action": "next"}
```

The session reports state, title, position, thumbs rating and the
custom actions Like, Dislike, Shuffle and Repeat. A session can vanish
within a minute of losing audio focus; revive when that happens.

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

## Refused

- `search:<query>` on the session answers "Cannot play track" and pins
  the session in an error state, recoverable with a plain `play`.
  `media:<id>` is refused the same way.
- `android.media.action.MEDIA_PLAY_FROM_SEARCH` brings the app to the
  foreground and never plays: bare, it ignores the query; targeted with
  the standard `focus`/`artist`/`album` extras it shows results and waits
  for a tap.
- The browser route: refused at connection.
