---
name: android-audible
description: Audible audiobook app. Revive from a dead process, play any library book by name, pause, seek to an absolute position within the chapter. Sleep timer and speed are reachable only through screen-bound deep links.
verified: 2026-09-27
---

# Audible

Package `com.audible.application`. The browser route is refused
(signature allowlist, do not re-probe), but the app revives from a dead
process with a targeted media-button intent followed by a session
`play`, and the live session then plays any library book by name, takes
transport and seeks to an absolute position. The relative jumps are
broken. Sleep timer and speed have no session verb.

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
failure. The `play` resumes the last-played book.

## Any book by name

```
media_command {"app": "com.audible.application", "route": "session", "action": "search:<book title>"}
```

The read-back shows buffering with the new title, and playback starts on
its own; no follow-up `play`. From a dead process: revive, wait for the
session, then search.

## Seek and transport

```
media_command {"app": "com.audible.application", "route": "session", "action": "seek:<seconds>"}
media_command {"app": "com.audible.application", "route": "session", "action": "pause"}
```

`seek:` is honoured exactly, playing or paused, within the current
chapter: `seek:0` is the start of this chapter, not of the book. For a
relative move, read the position, compute, and seek absolutely. The
relative verbs do nothing: `rewind` and `fast_forward` are advertised
and ignored, and the custom `ACTION_BACKWARD_AssistantDrive` and
`ACTION_FORWARD_AssistantDrive` are inert. Do not use them.

## Screen-bound only

Sleep timer and playback speed have no session verb. The app's launcher
activity accepts `audible://google-assistant/` deep links with paths
such as `cancel-sleep-timer`, `end-of-chapter-sleep-timer`,
`faster-playback-speed` and `slower-playback-speed`; these open the UI
on screen through a VIEW intent in activity mode and are the only route
to those settings.
