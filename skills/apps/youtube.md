# YouTube

- **Package**: `com.google.android.youtube`
- **Tier**: split personality — **dead while idle, transport-drivable once
  playing** — but a VIEW intent cold-starts it into the fully drivable
  state (see Automation consequence)
- **Verified on device**: 2026-09-06

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
**`play_from_uri` drops the URI** even though still advertised — tested
with multiple URLs and a known-good control id; the same URL that failed
here switched instantly once the UI was foregrounded. Instead of loading
the URI it may *launch the app and restart the last-loaded video* as a
fallback — visible, and confusing to the user, so don't fire URI loads
at a backgrounded YouTube. `m.youtube.com` vs `www.youtube.com` makes no
difference.

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

Play-by-URL requires the YouTube UI on screen, and `send_intent` opens
it. Verified live (2026-09-06): a VIEW intent at a watch URL —

    action: android.intent.action.VIEW
    data: https://www.youtube.com/watch?v=<id>
    package: com.google.android.youtube
    mode: activity

— cold-starts the app, plays the video, and brings up a fully drivable
media session (transport works, `play_from_uri` advertised for
subsequent switches while the UI stays up). Match `media_command` by
package, not "youtube" — the name is ambiguous with YouTube Music. On
a locked phone add `via_unlock_gate: true`, since the launch needs the
screen; the video then starts when the user unlocks.

`android.media.action.MEDIA_PLAY_FROM_SEARCH` with a string extra
`query` (verified 2026-09-06) opens the search **results** for the
query but does not auto-play the top hit — one tap from playing, like
Maps' search-along-route. Use it for "find X on YouTube"; use the VIEW
intent when a video id or URL is already known. The scan also shows
`vnd.youtube:<id>` as a VIEW scheme (untested; the watch URL already
covers it). Video ids still must come from outside — web search or a
known URL; the app exposes no catalog.

So: fire the VIEW intent to start any video; UI open = full control
including play-by-URL; UI closed = transport on the current video only.
