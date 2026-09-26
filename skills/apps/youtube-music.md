# YouTube Music

- **Package**: `com.google.android.apps.youtube.music`
- **Tier**: browser route refused; **fully drivable anyway** — revive
  with a targeted media-button intent, then play named content by
  `music.youtube.com` URL through the live session
- **Verified on device**: 2026-09-26

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

Verified 2026-09-26, twice:

- Revived a **stopped** session (the dead-handle state that ignores all
  session commands) to PLAYING, position advancing.
- Worked with the **screen locked**, and took audio focus from a
  playing Pocket Casts (which paused, as focus rules dictate).

What did **not** work, tested the same day — don't re-probe:

- `mode: broadcast` at its exported
  `androidx.media.session.MediaButtonReceiver`: delivered (manifest
  receiver matched), ignored. Single DOWN and a down/up pair both.
- `mode: service`: Android refuses the start itself — background apps
  may not start another app's plain service. `foreground_service` is
  the one delivery that both arrives and is honored.

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

## Named content — one route works: URI on the live session

**`play_from_uri` is honored** where everything else is refused.
Verified 2026-09-26: on a live session,

```
media_command app: "youtube music", route: session,
  action: "uri:https://music.youtube.com/watch?v=<id>"
```

switched playback to the named track within seconds ("PLAYING,
<title>, 1s of 359s"). Chained with the revival recipe
above, "play this specific song" works from nothing, hands free:
revive → wait for the session → `uri:` the watch URL.

The missing half is name → URL: the app resolves a URL it is handed
but answers no searches, so the lookup happens out of band (a web
search for the `music.youtube.com/watch` link — see
[media-discovery.md](../media-discovery.md) on out-of-band APIs).

Every other content-selection route is refused — tested, don't
re-probe:

- `play_from_search` / `play_from_media_id` on a live session:
  **advertised but refused** — `search:<query>` answers `error: Cannot
  play track.` and pins the session in the error state (recoverable
  with a plain `play`).
- `android.media.action.MEDIA_PLAY_FROM_SEARCH` at its main activity
  (the Google Assistant voice-play intent, which its manifest accepts):
  launches the app to the home screen and **silently ignores the
  query** — no search, no playback. Tested unlocked 2026-09-26.
- The browser route can never help: `onGetRoot` refuses non-allowlisted
  signatures (see [unavailable.md](unavailable.md)).

Untested (and now redundant): VIEW intents at `music.youtube.com` URLs
launching the activity — the session `uri:` route does the same job
without needing the screen unlocked.
