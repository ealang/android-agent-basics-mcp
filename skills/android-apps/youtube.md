---
name: android-youtube
description: YouTube app. Start any video by URL on screen, then pause, play, seek and switch videos by URL while the app stays in the foreground. No catalogue, queue or account content is visible.
verified: 2026-09-06
---

# YouTube

Package `com.google.android.youtube`. Any video starts by URL through a
VIEW intent, which opens the app on screen; while the app's UI is open,
the session takes transport and switches videos by URL. With the UI
closed only transport on the current video works, and with the process
dead nothing works. The app exposes none of the account's content, so
video ids come from outside: a web search, the YouTube Data API or a
known URL.

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

Cold-starts the app, plays the video and leaves a fully drivable session.
On a locked phone the video starts when the user unlocks; without
`via_unlock_gate` a locked-phone launch is discarded.

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

## While the UI is open

```
media_command {"app": "com.google.android.youtube", "action": "pause"}
media_command {"app": "com.google.android.youtube", "action": "play"}
media_command {"app": "com.google.android.youtube", "action": "seek:<seconds>"}
media_command {"app": "com.google.android.youtube", "action": "uri:https://www.youtube.com/watch?v=<id>"}
```

`uri:` switches playback to any video by its watch URL. Advertised: play,
pause, stop, next, seek, play-from-uri, rating. Read-backs carry the
real title, position and duration.

## While the UI is closed, process alive

Transport on the current video still works. `uri:` does not: the session
still advertises it but drops the URL, and may instead launch the app
and restart the last video, which is visible and confusing. Do not send
`uri:` to a backgrounded YouTube; fire the VIEW intent instead.

## Process dead

Nothing works on the session: `play` answers "Required premium account to
perform action in background", a canned rejection that appears on
Premium accounts too, and play-from-search and play-from-id answer
"Action not supported". Do not retry variations; use the VIEW intent.

## No content is visible

The browse root is empty, there is no queue, and liked videos,
subscriptions, history, downloads and library are not exposed: guessed
node ids return empty, as every unknown node does. Do not probe. The
only readable state is the now-playing metadata of the loaded video.
