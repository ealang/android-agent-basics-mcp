---
name: android-app-launch
description: When launching an app's UI is needed at all. Not for media playback, which works from the background, but for apps that must be in the foreground, and as a visible last resort when a media route refuses.
---

# App launch — when it is needed

Launching an app's screen is the last resort, not the first step. For
media it is never needed: the media routes wake an app's process
directly, with no activity and no keyguard, and start playback on a cold
app with the screen locked (see [media-control.md](media-control.md)).
Launch is for two cases: an app that must be in the foreground to do its
job, and content a media session refuses that the app's own UI will
play.

## The call

A launch is `send_intent` in activity mode, either a VIEW intent at a URL
the app claims or the app's exported activity named bare:

```
send_intent
{
  "action": "android.intent.action.VIEW",
  "data": "<url or scheme uri>",
  "package": "<package>",
  "mode": "activity",
  "via_unlock_gate": true
}

send_intent
{"component": "<package>/<activity>", "mode": "activity"}
```

Starting an activity from the background needs the "Display over other
apps" grant, which the tool preflights and names on failure. Always set
`"package"` when more than one app claims a scheme. On a locked phone a
launch without `via_unlock_gate` is silently discarded while the call
still reports "Started"; with it, the screen wakes to the user's own
unlock prompt and the activity starts the moment they unlock, or the
request expires after two minutes. The result string says which
happened.

## Apps that must be foregrounded

Android denies some capabilities to background processes whatever the
app wants, microphone access above all. An app that records needs a
foreground activity or a properly promoted foreground service; a
background-woken process records silence. Such an app needs a real
launch and a file of its own under `android-apps/`.

## Launch as the fallback for a refused media route

Where the media session refuses playback (a paywall on background play,
a post outside the browse window), the remaining option is a VIEW intent
at the content's URL. The app opens on screen and, depending on the app,
plays at once or waits for a tap; the per-app file says which. This is
visible and keyguard-bound, and there is no read-back beyond "Started".
