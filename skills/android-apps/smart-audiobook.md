---
name: android-smart-audiobook
description: Smart AudioBook Player. Play, pause, jump, change file, bookmark and set the sleep timer by firing its service actions from a dead process; play any book in the library by name.
verified: 2026-09-26
---

# Smart AudioBook Player

Package `ak.alizandro.smartaudiobookplayer`. The browser route is
refused (signature allowlist, do not re-probe) and there is no
media-button listener, but the player service exports named actions as
plain intent filters, so one `send_intent` resumes the current book from
a dead process, and the live session then plays any book in the library
by name. There is no absolute seek; seeking is jumps and file
boundaries.

## Resume from a dead process

```
send_intent
{
  "mode": "foreground_service",
  "component": "ak.alizandro.smartaudiobookplayer/ak.alizandro.smartaudiobookplayer.PlayerService",
  "action": "ak.alizandro.smartaudiobookplayer.ActionPlay"
}
```

No extras: the action name is the command. Resumes the current book at
the saved speed with no live session anywhere. A force-stopped app stays
unreachable.

The same call with another action name drives the rest of the service:

| Action (prefix `ak.alizandro.smartaudiobookplayer.`) | Effect |
|---|---|
| `ActionPlay` | resume |
| `ActionPrevFile` | restart the current file |
| `ActionRewindBig` / `ActionFwdBig` | jump about a minute, app-configured |

The service declares `ActionPause`, `ActionPlayPause`,
`ActionRewindSmall`, `ActionFwdSmall`, `ActionNextFile`,
`ActionAddBookmark`, `ActionEnableSleepTimer`, `ActionDisableSleepTimer`
and `ActionExit` on the same filter.

## Any book by name

```
media_command
{
  "app": "ak.alizandro.smartaudiobookplayer",
  "route": "session",
  "action": "search:<book title>"
}
```

Free text against the local library; playback switches to the named
book within the read-back. A non-matching query leaves the current book
playing with no error, so the read-back's title is the report. From a
dead process: `ActionPlay`, then the search once `media_sessions` lists
the app.

## Session

```
media_sessions {}
media_command {"app": "ak.alizandro.smartaudiobookplayer", "route": "session", "action": "pause"}
```

Reports state, book title, position and speed. Advertised: play, pause,
play_pause, play_from_media_id, play_from_search, and custom actions
mirroring `ActionRewindSmall`, `ActionFwdSmall` and `ActionExit`.

## Seeking

`seek:` is not advertised and is ignored. Seek with the service actions:
`ActionPrevFile` is the one-press "back to the start of this chapter";
`ActionRewindBig` and `ActionFwdBig` move about a minute per press, so
for a larger offset repeat the press, reading the position back between
presses. Each jump is audible while playing, like pressing the widget's
button. Right after `ActionPrevFile` a paused session's read-back omits
the position line until playback resumes; the session is fine, send
`play`.
