---
name: android-youtube
description: YouTube app. Start a video by URL, which needs the phone unlocked; then, with the screen locked, pause, play, seek, skip to the next video and switch videos by URL while a session exists. No catalogue, queue or account content is visible.
verified: 2026-11-07
---

# YouTube

Package `com.google.android.youtube`. A video starts by URL through a
VIEW intent, and that launch needs the phone unlocked: a session only
reliably appears once the user has let the app open. From then on the
session is drivable with the screen locked: pause, play, seek, next,
and switching to any other video by URL. With no session, every session
command is refused. The app exposes none of the account's content, so
video ids come from outside: a web search, the YouTube Data API or a
known URL.

| Capability | YouTube |
|---|---|
| Screen off | ✅ transport and switching by URL, once a session exists |
| Cold start | ❌ the VIEW launch is reliable only through the unlock gate, so the user unlocks; the ungated launch produced a session from a force-stopped process and nothing from a warm one |
| Resume, pause | ✅ ✅ while a session exists · ❌ with no session, refused |
| Play by name | ❌ query accepted, nothing changes |
| Play by id or URL | ❌ id, "Action not supported" · ✅ any watch URL, `uri:` on the session |
| Transport | ❌ previous, restarts the current video · ✅ next, autoplay's next video · ✅ seek · ✅ jump, as a relative seek · ❌ speed, ignored |
| Now playing | ✅ title, position, duration, rating |
| Queue | ❌ none |
| Library | ❌ empty root |
| Search | ❌ `media_search` suspends the connection |
| Also | — |

## Start a video

```
send_intent
{
  "action": "android.intent.action.VIEW",
  "data": "https://www.youtube.com/watch?v=<id>",
  "package": "com.google.android.youtube",
  "mode": "activity",
  "via_unlock_gate": true
}
```

Opens the app on the video and leaves a drivable session. On a locked
phone the video starts when the user unlocks.

Without `via_unlock_gate` the result depends on state the caller cannot
see: from a force-stopped process the video loaded and a session
appeared with the screen locked, from a warm process with no session
the same intent did nothing. Not a route to rely on.

To open search results instead of a video, one tap from playing:

```
send_intent
{
  "action": "android.media.action.MEDIA_PLAY_FROM_SEARCH",
  "package": "com.google.android.youtube",
  "mode": "activity",
  "extras": [{"key": "query", "type": "string", "value": "<query>"}]
}
```

## While a session exists

```
media_command {"app": "com.google.android.youtube", "action": "pause"}
media_command {"app": "com.google.android.youtube", "action": "play"}
media_command {"app": "com.google.android.youtube", "action": "seek:<seconds>"}
media_command {"app": "com.google.android.youtube", "action": "seek:+30"}
media_command {"app": "com.google.android.youtube", "action": "next"}
media_command {"app": "com.google.android.youtube", "action": "uri:https://www.youtube.com/watch?v=<id>"}
```

All of these work with the screen locked and the app's UI closed, as
long as `media_sessions` lists YouTube. `uri:` switches playback to any
video by its watch URL and plays it. `next` goes to autoplay's next
video; `previous` restarts the current one. `seek:` takes an absolute
second count or `+N`/`-N`. `speed:` is ignored. Read-backs carry the
real title, position and duration, and playback continues after the
connection closes.

## No session

With no session listed, `play` and `uri:` answer "Required premium
account to perform action in background", a canned rejection that
appears on Premium accounts too, and play by id answers "Action not
supported". The VIEW intent is the only route back to a session.

## No content is visible

The browse root is empty, there is no queue, and liked videos,
subscriptions, history, downloads and library are not exposed: guessed
node ids return empty, as every unknown node does. `media_search`
suspends the connection. The only readable state is the now-playing
metadata of the loaded video.
