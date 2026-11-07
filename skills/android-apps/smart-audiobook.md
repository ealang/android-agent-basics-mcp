---
name: android-smart-audiobook
description: Smart AudioBook Player. Resume from a dead process with the screen locked by firing its service actions, play any book in the library by name, pause, jump by 10 s or a minute, restart the file. No seek, no speed, no queue.
verified: 2026-11-07
---

# Smart AudioBook Player

Package `ak.alizandro.smartaudiobookplayer`. The browser route is
refused at connection by a signature allowlist and there is no
media-button listener, but the player service exports named actions as
plain intent filters, so one `send_intent` resumes the current book from
a dead process, and the live session then plays any book in the library
by name and pauses. There is no seek and no speed control from outside;
moving within a book is jumps and file boundaries.

| Capability | Smart AudioBook Player |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `ActionPlay` to the player service |
| Resume, pause | ✅ ✅ |
| Play by name | ✅ any library book, `search:<title>` · a title not in the library leaves the current book playing, no error |
| Play by id or URL | ❌ browser refused, no ids · URL: not tested |
| Transport | ❌ previous chapter · ❌ next chapter, `next` and `previous` are ±10 s jumps · ❌ seek, ignored · ✅ jump, ±10 s on the session, about ±60 s and restart-the-file by service action · ❌ speed, ignored |
| Now playing | ✅ book title, position and duration for the whole book; no speed |
| Queue | ❌ none on the session, browser refused |
| Library | ❌ browser refused |
| Search | ❌ browser refused · `search:` plays rather than lists |
| Also | — |

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
its saved position within a few seconds, from a dead process with the
screen locked, and playback continues after the connection closes.

The same call with another action name drives the rest of the service:

| Action (prefix `ak.alizandro.smartaudiobookplayer.`) | Effect |
|---|---|
| `ActionPlay` | resume |
| `ActionPrevFile` | restart the current file |
| `ActionRewindBig` / `ActionFwdBig` | jump about a minute, app-configured |

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
book at its saved position within the read-back. A non-matching query
leaves the current book playing with no error; the read-back shows which
book is loaded. From a dead process: `ActionPlay`, then the search once
`media_sessions` lists the app.

## Session

```
media_sessions {}
media_command {"app": "ak.alizandro.smartaudiobookplayer", "route": "session", "action": "pause"}
media_command {"app": "ak.alizandro.smartaudiobookplayer", "route": "session", "action": "play"}
media_command {"app": "ak.alizandro.smartaudiobookplayer", "route": "session", "action": "next"}
media_command {"app": "ak.alizandro.smartaudiobookplayer", "route": "session", "action": "previous"}
```

Reports state, book title, and position and duration of the whole book.
`next` and `previous` jump +10 s and −10 s, the same as the advertised
custom actions mirroring `ActionFwdSmall` and `ActionRewindSmall`; a
custom action mirroring `ActionExit` is also advertised. The session has
no queue.

## Moving within a book

`seek:` is not advertised and is ignored, as is `speed:`. Move with the
jumps: `next` and `previous` for ±10 s, `ActionRewindBig` and
`ActionFwdBig` for about a minute per press, `ActionPrevFile` for the
start of the current file. For a larger offset repeat the press, reading
the position back between presses. Each jump is audible while playing,
like pressing the widget's button. Right after `ActionPrevFile` a paused
session's read-back omits the position line until playback resumes.
