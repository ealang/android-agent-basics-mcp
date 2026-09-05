# YouTube

- **Package**: `com.google.android.youtube`
- **Tier**: split personality — **dead while idle, transport-drivable once
  playing**
- **Verified on device**: 2026-09-04

Mechanisms: [media-control.md](../media-control.md).

## The rule

YouTube's media session ignores strangers until playback is active, then
grants transport control over it. What flips it is *any* active playback,
started from the app's own UI. There is no way to start the first
playback from here.

## While idle (nothing playing)

- `playFromSearch`, `playFromMediaId` → error "Action not supported".
- `play` → error "Required premium account to perform action in
  background". **A canned rejection, not an account check** — it appears
  on a Premium account with the app foregrounded. Don't chase it.
- Advertised actions: none. Nothing works; don't retry variations.

## While playing (user started a video)

- Real now-playing metadata (title, position, duration).
- Advertised: play, pause, stop, skip_to_next, seek_to,
  **play_from_uri**, set_rating.
- `pause` / `play` — work.
- **`uri:https://www.youtube.com/watch?v=<id>` works** — switches
  playback to an arbitrary video by plain watch URL. This is the
  payload: once warm, any video is one command away.

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

The missing piece is the initial kick. A future generic intent-sending
capability could fire a VIEW intent at a watch URL to start the first
playback (visibly, via the UI), after which everything above applies.
Until then: if the user has YouTube playing, transport control plus
play-by-URL; if not, nothing.
