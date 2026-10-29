---
name: android-media-control
description: How to drive any media app through the media browser route, the live-session route, and the targeted media-button intent that revives an app neither route can reach. Read before using the media tools on an app without its own skill file.
---

# Media control — driving apps through their media sessions

Three routes, in the order to try them:

1. **Browser route** — starts anything in an app that accepts the
   connection, from a cold process, screen locked. The default.
2. **Session route** — steers an app that is already playing or paused,
   including apps that refuse the browser route. Cannot start anything.
3. **Targeted media button** — a `send_intent` recipe that wakes an app
   which refuses the browser route and has no live session.

An app's file in `android-apps/` says which routes it answers and gives
the calls; this file is for an app without one. In every call, `app`
takes the package name (a name also works, fuzzy-matched, but packages
are unambiguous).

## Browser route

`media_browse`, `media_search`, `media_play` and `media_command` bind the
app's MediaBrowserService. The app's process wakes with no activity, no
window and no keyguard involvement, so playback starts on a locked phone
from a cold app.

```
media_browse   {"app": "<package>"}                      root listing
media_browse   {"app": "<package>", "node": "<id>"}      a node's children; "offset" pages
media_search   {"app": "<package>", "query": "<text>"}   playable ids, where implemented
media_play     {"app": "<package>", "media_id": "<id>"}  play an id from a browse
media_play     {"app": "<package>", "query": "<text>"}   the app resolves the text and plays
media_command  {"app": "<package>", "action": "pause"}   transport, queue, custom actions
```

Listings mark each item `[dir]` or `[playable]` and carry the id to play
it. Lift ids verbatim from a fresh browse; whether they can be stored is
per-app (the file says), and a hand-built id is refused at play time.

Who may connect is the app's decision. `media_apps` lists every app with
a browser service; an app absent from it has no browser route. A
connection the app refuses is a signature allowlist, permanent; the
other two routes remain. An app that connects but shows an
empty root withholds browsing; play and transport may still work.

Most apps keep playing after the connection closes. The per-app file
says when one does not.

## Session route

`media_sessions` and `media_command` with `"route": "session"` drive any
live session on the phone, whatever the app's browser policy. Needs the
notification-access grant. A session exists while the app plays or sits
freshly paused; a `stopped` session is a dead handle that shows metadata
and ignores every command, and a session can vanish within a minute of
losing an audio-focus fight.

```
media_sessions {}
media_command  {"app": "<package>", "route": "session", "action": "pause"}
```

`media_key {"key": "play"}` presses a system media button. It is
untargetable: the system routes it to whatever it considers the most
recent media app and can revive that app as a headset button would. It
suits a bare play or pause with no named target; some apps ignore it or
mishandle it (their files say), and an app holding the audio session, such as a voice
assistant mid-conversation, can capture the press.

## Targeted media button

For an app whose browser route is refused and which has no live session.
One intent starts the app's media service and carries the key press as
an extra; the service is the one whose intent filter lists
`MEDIA_BUTTON`, found with `inspect`:

```
send_intent
{
  "mode": "foreground_service",
  "component": "<package>/<its media service>",
  "action": "android.intent.action.MEDIA_BUTTON",
  "extras": [{"key": "android.intent.extra.KEY_EVENT", "type": "key_event", "value": "play"}]
}
```

The `key_event` extra is the press; without it the intent is an empty
envelope. This is the system's own headset-revival mechanism, aimed at
one app: it wakes the process and resumes the last content with no live
session and no unlock. `"mode": "broadcast"` at the app's exported
MediaButtonReceiver is delivered and ignored, and `"mode": "service"` is
refused by Android, so `foreground_service` is the delivery.

The same move generalises: `inspect` shows an app's exported services and
the actions their filters declare, and `foreground_service` fires them.
Some apps expose a vocabulary richer than the media-button contract this
way.

## Transport, queue and state

Every `media_play` and `media_command` result reads the session back
after a beat: state, title, position and the actions the session
advertises. PLAYING with a position that advances is playback; a stale
title at position 0 means the app refused the id.

- An action the session does not advertise is silently ignored, not
  errored. Some apps update the advertisement live.
- `"queue"` returns the current queue with ids; `"queue:<id>"` jumps to
  an entry. This is how to skip in apps that do not advertise `next`.
- `"queue_add:<id>"`, `"queue_add:<pos>:<id>"` and `"queue_remove:<id>"`
  edit the queue where advertised, browser route only.
- `"seek:<seconds>"` is absolute; `"seek:+N"` and `"seek:-N"` relative.
  `"speed:<multiplier>"` sets the rate.
- `"uri:<uri>"`, `"search:<query>"` and `"media:<id>"` are the
  session-route forms of play-from-uri, play-from-search and
  play-from-id.
- Any other action is sent verbatim as a custom action. Ids are
  app-defined and case-sensitive; the read-back lists them.

Force stop blocks two routes until the user opens the app again: no
broadcast reaches it, and `media_key` does not revive it. The browser
route still reaches a force-stopped app.
