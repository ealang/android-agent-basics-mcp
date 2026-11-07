---
name: android-audible
description: Audible audiobook app. Revive from a dead process with the screen locked, play any library book by name, pause, move by chapter, seek, jump, set speed, read the chapter list and jump to a chapter. The browser route is refused.
verified: 2026-11-07
---

# Audible

Package `com.audible.application`. The browser route is refused at
connection by a signature allowlist, but the app revives from a dead
process with a targeted media-button intent followed by a session
`play`, and the live session then plays any library book by name, takes
transport, seeks and jumps, sets the speed, and exposes the chapter list
as its queue. No library browse, no search that lists results, and no
sleep timer.

| Capability | Audible |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, a targeted media-button intent then `play` |
| Resume, pause | ✅ ✅ |
| Play by name | ✅ any library book, `search:<title>` · a title not in the library leaves the current book playing, no error |
| Play by id or URL | ❌ browser refused, no ids · URL: not tested |
| Transport | ✅ previous chapter · ✅ next chapter · ✅ seek, within the chapter · ✅ jump, ±30 s custom actions or a relative seek · ✅ speed |
| Now playing | ✅ book title, position and duration per chapter, speed |
| Queue | ✅ read, the chapter list · ✅ jump to a chapter · ❌ add · ❌ move · ❌ remove, browser refused |
| Library | ❌ browser refused |
| Search | ❌ browser refused · `search:` plays rather than lists |
| Also | — |

## Revive from a dead process

```
send_intent
{
  "mode": "foreground_service",
  "component": "com.audible.application/com.audible.application.AndroidMediaBrowserService",
  "action": "android.intent.action.MEDIA_BUTTON",
  "extras": [{"key": "android.intent.extra.KEY_EVENT", "type": "key_event", "value": "play"}]
}
```

then, about three seconds later, once `media_sessions` lists Audible:

```
media_command {"app": "com.audible.application", "route": "session", "action": "play"}
```

The intent brings the process up and publishes the session, but audio
does not start until the session `play`. A freshly published session
reads "no state" with a blank title; that is the pre-play state, not a
failure. The `play` resumes the last-played book at its saved position.

## Any book by name

```
media_command {"app": "com.audible.application", "route": "session", "action": "search:<book title>"}
```

The read-back shows buffering with the new title, and playback starts on
its own at the book's saved position; no follow-up `play`. A title not in
the library leaves the current book playing with no error; the read-back
shows which book is loaded. From a dead process: revive, wait for the
session, then search.

## Chapters, seek, jump, speed

```
media_command {"app": "com.audible.application", "route": "session", "action": "next"}
media_command {"app": "com.audible.application", "route": "session", "action": "previous"}
media_command {"app": "com.audible.application", "route": "session", "action": "seek:<seconds>"}
media_command {"app": "com.audible.application", "route": "session", "action": "seek:+30"}
media_command {"app": "com.audible.application", "route": "session", "action": "speed:1.5"}
media_command {"app": "com.audible.application", "route": "session", "action": "ACTION_FORWARD_AssistantDrive"}
media_command {"app": "com.audible.application", "route": "session", "action": "ACTION_BACKWARD_AssistantDrive"}
```

- `next` and `previous` move by chapter. `previous` restarts the current
  chapter unless the position is within a few seconds of its start, in
  which case it goes to the previous chapter.
- `seek:` takes an absolute second count within the current chapter, or
  `+N`/`-N`: `seek:0` is the start of this chapter, not of the book.
- The two custom actions jump ±30 s, clamped at the chapter start.
  `rewind` and `fast_forward` are advertised and ignored.
- `speed:<x>` sets the playback speed, remembered per book. The
  read-back shows `Speed:` at anything but 1x, where the line is absent.

## Chapter list as queue

```
media_command {"app": "com.audible.application", "route": "session", "action": "queue"}
media_command {"app": "com.audible.application", "route": "session", "action": "queue:<index>"}
```

The queue read is the loaded book's chapter list, index-numbered;
`queue:<index>` jumps to that chapter and plays. Add, move and remove
are browser-route edits, so they are refused with the browser.

## Now playing

`media_sessions` reports state, the book title, position and duration
of the current chapter, and the speed when it is not 1x. Playback
continues after the connection closes.
