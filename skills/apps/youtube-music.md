# YouTube Music

- **Package**: `com.google.android.apps.youtube.music`
- **Tier**: browser route refused; **fully drivable anyway** — revive
  with a targeted media-button intent, then play named content by
  `music.youtube.com` URL through the live session
- **Verified on device**: 2026-10-19

Mechanisms: [media-control.md](../media-control.md).

## The revival recipe — works from stopped, screen locked

One `send_intent` call resumes YT Music's last content with no session,
no unlock, and no cooperation from routing:

```
send_intent
  mode: foreground_service
  component: com.google.android.apps.youtube.music/com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService
  action: android.intent.action.MEDIA_BUTTON
  extras: [{key: android.intent.extra.KEY_EVENT, type: key_event, value: play}]
```

This is one intent: starting the service is the delivery, the action is
the label the service branches on, and the KeyEvent extra is the press
itself. It is the same mechanism the system uses to revive the last
media app for a Bluetooth headset — done by hand, so the target is
*chosen* instead of left to routing.

Verified:

- Revived a **stopped** session (the dead-handle state that ignores all
  session commands) to PLAYING, position advancing.
- Worked with the **screen locked**, and took audio focus from a
  playing Pocket Casts (which paused, as focus rules dictate).
- Works with **no session listed at all** (app not running): a session
  appears within about a second, track loaded, state not yet reported —
  and already accepts `uri:` (see the play recipe below).

The press is `play`, so revival is audible: it resumes whatever was
last loaded.

What did **not** work, tested the same day — don't re-probe:

- `mode: broadcast` at its exported
  `androidx.media.session.MediaButtonReceiver`: delivered (manifest
  receiver matched), ignored. Single DOWN and a down/up pair both.
- `mode: service`: Android refuses the start itself — background apps
  may not start another app's plain service. `foreground_service` is
  the one delivery that both arrives and is honored.
- `value: pause` (to revive silently): delivered, but no session ever
  appears — nothing to send a URL to. Only `play` revives.

Still untested: a process naturally dead for hours (both verified runs
had a warm process). This is the platform's own revival path, so it is
expected to work — but expected is not verified. A **force-stopped**
app remains unreachable: platform stopped-state blocks even this.

## While a session is live (playing or paused) — steer it

- `media_sessions` shows it: state, title, position, thumbs rating,
  custom actions (Like, Dislike, Shuffle, Repeat).
- `media_command` with `route: session` — `pause`, `play`, and by
  advertisement next/previous/seek (transport verified via pause/play).
- Match by package or "youtube music" — bare "youtube" is ambiguous
  with the YouTube app.
- Session decay is fast: losing an audio-focus fight can erase the
  session within a minute. With the revival recipe above this no longer
  matters much — a dead session is one call from alive.

## Play a URL — the recipe

Content is chosen by URL only: a watch URL,
`https://music.youtube.com/watch?v=<id>`, optionally with
`&list=<playlist id>` to play it as part of that list. It is played
through the session's `play_from_uri`, honored where every other
content route is refused. Works with the screen locked.

1. **Check** `media_sessions` for YouTube Music.
2. **Playing or paused** → go to step 3.
   **Not listed, or `stopped`** → revive (recipe above), poll
   `media_sessions` until YouTube Music appears (about a second), and
   go to step 3 at once — don't wait for a state. Until the URL lands
   the previous track plays, so the shorter this gap, the shorter the
   blip.
3. **Send the URL:**

   ```
   media_command app: "youtube music", route: session,
     action: "uri:https://music.youtube.com/watch?v=<id>[&list=<playlist id>]"
   ```

   The read-back shows PLAYING with the requested title within seconds.

**Fallback — VIEW intent at the URL.** Opens the app and plays at
once, even from not running, with no blip — but only on an unlocked
phone:

```
send_intent
  action: android.intent.action.VIEW
  data: https://music.youtube.com/watch?v=<id>[&list=<playlist id>]
  package: com.google.android.apps.youtube.music
```

On a **locked** phone the launch is silently discarded (the call still
reports "Started"); with `via_unlock_gate: true` it plays the moment
the user unlocks. Use it when the recipe fails, or when the user is
holding the phone and the blip matters.

The missing half is name → URL, done out of band. Four ways:

- **Fetch the YT Music search page** —
  `https://music.youtube.com/search?q=<query>` with a desktop
  browser `User-Agent` returns HTML that embeds the result ids: video
  ids as `"videoId":"<id>"` and playlist ids (`list=` values) as
  `OLAK5uy_…`. No key, no browser. To tell playlists apart, fetch
  `https://www.youtube.com/playlist?list=<id>` and read its `<title>`;
  the same page lists the playlist's video ids in order.
- Search `<artist/song> "music.youtube.com/watch"` (or
  `site:music.youtube.com`) — the quoted domain surfaces direct links.
- **ID mapping**: video ids are shared with regular YouTube, so any
  `youtube.com/watch?v=X` result becomes
  `music.youtube.com/watch?v=X` by swapping the host. Regular YouTube
  results are far more plentiful.
- YouTube Data API v3 `search.list` (needs an API key) for a
  programmatic lookup that can filter to official music
  (`videoCategoryId=10`); a web search finds *an* upload, not
  necessarily the canonical track.

Every other content-selection route is refused — tested, don't
re-probe:

- `play_from_search` / `play_from_media_id` on a live session:
  **advertised but refused** — `search:<query>` answers `error: Cannot
  play track.` and pins the session in the error state (recoverable
  with a plain `play`).
- `android.media.action.MEDIA_PLAY_FROM_SEARCH` (the Google Assistant
  voice-play intent, which its manifest accepts): brings the app to
  the foreground and **never plays**. Bare, at its main activity, it
  ignores the query outright, cold or with a live session; targeted
  by package with the standard `focus`/`artist`/`album` extras it
  shows the search results and waits for a tap.
- The browser route can never help: `onGetRoot` refuses non-allowlisted
  signatures (see [unavailable.md](unavailable.md)).
