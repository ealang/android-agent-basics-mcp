# YouTube Music

- **Package**: `com.google.android.apps.youtube.music`
- **Tier**: browser route refused; **resumable at will via a targeted
  media-button intent** — steerable while a session is live; named
  content refused on every route
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

## Named content — refused on every route

The app accepts transport, never content selection from a stranger:

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

Untested lead: VIEW intents at `music.youtube.com` URLs (the pattern is
proven on YouTube, [youtube.md](youtube.md)). Even if it works it needs
the screen unlocked — activity launches are discarded behind the
keyguard, so it would be `send_intent` with `via_unlock_gate: true`.

Bottom line: "resume what was last playing" is fully solved, hands
free, screen locked. "Play *this specific thing*" still means picking
it on the phone.
