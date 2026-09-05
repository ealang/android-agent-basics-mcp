# YouTube

- **Package**: `com.google.android.youtube`
- **Tier**: split personality — **dead while idle, transport-drivable once
  playing**
- **Verified on device**: 2026-09-04

Mechanisms: [media-control.md](../media-control.md).

## The rule

Three states, in decreasing capability. What matters is whether the
**app's UI is open**, not just whether its process is alive.

### UI open with a video loaded — full control

- Real now-playing metadata (title, position, duration).
- Advertised: play, pause, stop, skip_to_next, seek_to,
  **play_from_uri**, set_rating.
- `pause` / `play` — work.
- **`uri:https://www.youtube.com/watch?v=<id>` works** — switches
  playback to an arbitrary video by plain watch URL. This is the
  payload: with the UI up, any video is one command away.

### UI closed, process still alive — transport only

The session keeps answering (metadata, advertised actions unchanged) but
**`play_from_uri` is silently dropped** even though still advertised —
tested with multiple URLs and a known-good control id. Transport on the
already-loaded video still works. Quirk: a dropped URI load may
*restart the last-loaded video* as a fallback instead. `m.youtube.com`
vs `www.youtube.com` makes no difference.

### Never started / process dead — nothing

- `playFromSearch`, `playFromMediaId` → error "Action not supported".
- `play` → error "Required premium account to perform action in
  background". **A canned rejection, not an account check** — it appears
  on a Premium account with the app foregrounded. Don't chase it.
- Advertised actions: none. Nothing works; don't retry variations.

## What is visible: only the current video

Content discovery is zero in both states, warm included:

- Browse root is `__EMPTY_ROOT__` (browse deliberately withheld), and
  stays empty while playing.
- **No queue** exposed on the session.
- **Liked videos, subscriptions, history, downloads, library: not
  visible.** A dozen guessed node ids (`__LIKED__`, `__HISTORY__`,
  `subscriptions`, …) all return empty; like Substack, YouTube answers
  *empty* rather than *error* for unknown nodes, so the probe is
  formally inconclusive — but every account surface tried is
  inaccessible.
- The only readable state is the now-playing metadata of whatever is
  currently loaded.

So: video ids must come from outside (web search, YouTube Data API, a
known URL) — the app tells us nothing about the account's content.

## Automation consequence

Play-by-URL requires the YouTube UI on screen, so the missing piece is
opening it. A future generic intent-sending capability could fire a VIEW
intent at a watch URL, which both opens the UI and starts the video in
one step — making play_from_uri redundant for the first video and
available for subsequent switches while the UI stays up. Until then:
UI open = full control including play-by-URL; UI closed = transport on
the current video only; otherwise nothing.
