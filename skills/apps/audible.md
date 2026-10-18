# Audible

`com.audible.application` (verified on v26.36.11, 2026-09-27). Browser
route refused (`onGetRoot` allowlist — don't re-probe), but fully
reachable through the media-button revival plus its session, which is
the richest in the survey: any book by name via search, real absolute
seek, and the full transport grammar.

Self-contained: everything below runs without an `inspect`. The
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

## Any book by name: session search, and it auto-plays

`play_from_search` is honored (verified 2026-09-27: switched from one
title to another by name):

```
media_command  app: "audible"  route: session  action: "search:<book title>"
```

Read-back showed "buffering" with the new title within 3 s, then
playback started **on its own** — search auto-plays, no follow-up
`play` needed. Verify the switch by the title in the read-back, never
by "sent" (a non-matching query may fail silently; unverified here,
but that is the Smart AudioBook failure mode and the read-back costs
nothing).

Chained recipe, dead process → named book playing: revival intent →
wait for the session → `search:<title>`.

## Seeking: real absolute seek — the only app in the survey with one

`seek:<seconds>` on the session route is honored exactly, even while
paused, on both a Preview stream and a library title (verified:
`seek:120` landed at 120 s paused; `seek:0` restarted a chapter).
Position is within the current chapter/file, not the whole book —
`seek:0` means "start of this chapter". Prefer it over any jump-button
approach.

The relative jumps are broken, so don't fall back to them:
`rewind`/`fast_forward` are advertised but ignored (position kept
advancing through a `rewind` sent mid-playback), and the custom
`ACTION_BACKWARD_AssistantDrive`/`ACTION_FORWARD_AssistantDrive`
actions were equally inert (tested paused and playing, Preview stream,
2026-09-27). Relative moves: read the position, compute, `seek:<n>`.

## The session's full advertised surface

play, pause, play_pause, stop, skip_to_next, skip_to_previous,
skip_to_queue_item, fast_forward, rewind, **seek_to**,
play_from_media_id, play_from_search, play_from_uri, prepare. Custom:
`ACTION_BACKWARD_AssistantDrive`, `ACTION_FORWARD_AssistantDrive`,
`ACTION_BOOKMARKS_AssistantDrive` (bookmark).

Verified working: revival, play, pause, seek_to, play_from_search.
Verified broken: rewind, fast_forward, ACTION_BACKWARD. Untested:
play_from_uri, skip_to_next/previous, skip_to_queue_item, prepare,
stop, the bookmark action, ACTION_FORWARD.

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
