# Audible

`com.audible.application` (verified on v26.36.11, 2026-09-27). Browser
route refused (`onGetRoot` allowlist — don't re-probe), but fully
reachable through the media-button revival plus its session, which is
the richest in the survey: real absolute seek, the full transport
grammar, and search/URI verbs (untested, see below).

Self-contained: everything below runs without an `intent_scan`. The
generic verbs (`play`, `pause`, read-backs) are in
[../media-control.md](../media-control.md).

## Reviving from a dead process — two steps, not one

Audible has the same exported androidx `MediaButtonReceiver` /
MediaBrowserService shape as YouTube Music, so the targeted
media-button recipe applies, with one difference: the wake and the
playback are separate steps. The intent brings the process up and
publishes the session, but audio does not start until a session `play`
follows.

```
send_intent
  mode: foreground_service
  component: com.audible.application/com.audible.application.AndroidMediaBrowserService
  action: android.intent.action.MEDIA_BUTTON
  extras: [{key: android.intent.extra.KEY_EVENT, type: key_event, value: play}]
```

then, once `media_sessions` shows the Audible session (≈3 s):

```
media_command  app: "audible"  route: session  action: "play"
```

Verified 2026-09-27 from no live session: the intent published the
session (state "no state", empty title), and the follow-up `play`
resumed the last-played item. Freshly published, the session may read
"no state — the command was likely ignored" with a blank title; that is
the pre-play limbo, not a failure — send `play` and re-read.

## Seeking: real absolute seek — the only app in the survey with one

`seek:<seconds>` on the session route is honored exactly, even while
paused (verified: `seek:120` landed at 120 s paused). Prefer it over
any jump-button approach.

The relative jumps are broken, so don't fall back to them:
`rewind`/`fast_forward` are advertised but ignored (position kept
advancing through a `rewind` sent mid-playback), and the custom
`ACTION_BACKWARD_AssistantDrive`/`ACTION_FORWARD_AssistantDrive`
actions were equally inert. Tested against a Preview stream
(2026-09-27) — conceivably library titles behave differently, but with
`seek_to` working there is no reason to find out.

## The session's full advertised surface

play, pause, play_pause, stop, skip_to_next, skip_to_previous,
skip_to_queue_item, fast_forward, rewind, **seek_to**,
play_from_media_id, play_from_search, play_from_uri, prepare. Custom:
`ACTION_BACKWARD_AssistantDrive`, `ACTION_FORWARD_AssistantDrive`,
`ACTION_BOOKMARKS_AssistantDrive` (bookmark).

Verified working: revival, play, pause, seek_to. Verified broken:
rewind, fast_forward, ACTION_BACKWARD. Untested: play_from_search
(content by name — the next thing worth testing), play_from_uri,
skip_to_next/previous, the bookmark action.

## Activity-level doors (screen-bound, untested)

`MainLauncher` accepts `MEDIA_PLAY_FROM_SEARCH`, product-page deep
links (`https://www.audible.com/pd/...`, `audible://` scheme), and a
set of Google Assistant deep links under `audible://google-assistant/`
whose paths name real verbs: `save-bookmark-clip`,
`download-current-listening`, `faster-playback-speed`,
`slower-playback-speed`, `cancel-sleep-timer`,
`end-of-chapter-sleep-timer`,
`set-up-sleep-timer-duration-hours-and-minutes?number_of_hours={h}&number_of_minutes={m}`.
These launch the UI (screen on, app foregrounded) — but they are the
only visible route to sleep-timer and speed control, which no session
verb offers.
