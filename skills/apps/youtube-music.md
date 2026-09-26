# YouTube Music

- **Package**: `com.google.android.apps.youtube.music`
- **Tier**: browser route refused; **resume-and-steer only, via the live
  session** — no cold start, no named content
- **Verified on device**: 2026-09-24

Mechanisms: [media-control.md](../media-control.md).

## The rule

Everything depends on whether a **live media session** exists — which it
does while the app is playing or freshly paused, and at no other time.
The app being open is not enough: a session is born from playing
something, not from launching the app.

### Session alive (playing or paused) — transport works

- `media_sessions` shows it: state, title, position, thumbs rating,
  custom actions (Like, Dislike, Shuffle, Repeat).
- `media_command` with `route: session` — `pause`, `play`, and by
  advertisement next/previous/seek (transport verified via pause/play;
  the rest advertised, untested individually).
- `media_key play` resumes it — the same KEYCODE_MEDIA_PLAY the car
  sends over AVRCP. Works with the screen off, and still works after
  the app has dropped off the recents list (recents ≠ process death).
- Match by package or "youtube music" — bare "youtube" is ambiguous
  with the YouTube app.

### Named content — refused even on a live session

`play_from_search` and `play_from_media_id` are **advertised but
refused**: `search:<query>` answers `error: Cannot play track.` and
pins the session in the error state. Recoverable — a plain `play`
resumes the previous track — but nothing was gained. The app accepts
transport from the system's routing, not content selection from a
stranger's controller. Don't re-probe; pick content on the phone (or
via the untested UI route below).

### No session — nothing works

- The browser route can never help: `onGetRoot` refuses non-allowlisted
  signatures (see [unavailable.md](unavailable.md)). No browse, no
  session token from a connection.
- After a **force stop**, `media_key` does nothing: stopped-state apps
  are excluded from media-button restarts by platform design, until the
  user opens the app again.
- Whether `media_key` can revive a **naturally dead** process (the state
  a car head unit meets, and apparently resumes) is **untested** — the
  one open question. If it fails too, no session ever means no control.

## Session decay is fast

Losing an audio-focus fight can erase the session within a minute: YT
Music was playing, another app started on top, and by the next look YT
Music had no session at all — not paused-and-resumable, gone. A paused
session is an opportunity with a shelf life, not a persistent handle.

## Cold start: unlock gate only

The only way to start YT Music from nothing is its UI. A locked phone
silently discards background activity launches, so this needs
`send_intent` with `via_unlock_gate: true` — the user unlocks once and
the launch proceeds. VIEW intents at `music.youtube.com` URLs are the
obvious payload but are **untested** on this app (the pattern is proven
on YouTube, [youtube.md](youtube.md)). Bottom line for a locked phone
with nothing playing: there is no hands-free start; that is YT Music
policy, not a phone-mcp gap.
