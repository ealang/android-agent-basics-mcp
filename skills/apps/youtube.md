# YouTube

- **Package**: `com.google.android.youtube`
- **Tier**: split personality — **dead while idle, fully drivable once
  playing**
- **Verified on device**: 2026-09-04

Mechanisms: [media-control.md](../media-control.md).

## The rule

YouTube's media session ignores strangers until playback is active, then
hands over real control. What flips it is *any* active playback, started
from the app's own UI.

## While idle (nothing playing)

- Browse root is `__EMPTY_ROOT__` — browse deliberately withheld.
- `playFromSearch`, `playFromMediaId` → error "Action not supported".
- `play` → error "Required premium account to perform action in
  background". **This string is a canned rejection, not an account
  check** — it appears even on a Premium account with the app
  foregrounded. Don't chase it.
- Advertised actions: none. Nothing works; don't retry variations.

## While playing (user started a video)

The session comes alive:

- Real now-playing metadata (title, position, duration).
- Advertised: play, pause, stop, skip_to_next, seek_to,
  **play_from_uri**, set_rating.
- `pause` / `play` — work (confirmed on device).
- **`uri:https://www.youtube.com/watch?v=<id>` works** — switches
  playback to an arbitrary video by plain watch URL, verified by title
  and advancing position. This is the payload: once warm, any video is
  one command away.

## Automation consequence

The only missing piece is the initial kick. A future generic
intent-sending capability could fire a VIEW intent at a watch URL to
start the first playback (visibly, via the UI), after which the session
is live and everything above applies. Until then: if the user has
YouTube playing, full control; if not, nothing.
