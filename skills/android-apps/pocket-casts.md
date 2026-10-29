---
name: android-pocket-casts
description: Pocket Casts podcast app. Resume or start playback, play any subscribed show or episode by name or id, read and jump within the Up Next queue, change speed; all of it works cold with the screen locked.
verified: 2026-10-02
---

# Pocket Casts

Package `au.com.shiftyjelly.pocketcasts`. Everything works through the
browser route, from a cold process with the screen locked: resume, play
by name, play by id, search, the queue, speed. Playback continues after
the connection closes. `media_key` does not work on this app.

## Resume or revive

```
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "play"}
```

Resumes a paused episode, and starts the app from fully swiped away at
the saved position. No search or id needed.

## Play something else by name

```
media_play {"app": "au.com.shiftyjelly.pocketcasts", "query": "<show or episode>"}
```

The app resolves the text to the right show and starts an episode. To
pick an exact item first:

```
media_search {"app": "au.com.shiftyjelly.pocketcasts", "query": "<text>"}
media_play   {"app": "au.com.shiftyjelly.pocketcasts", "media_id": "<id>"}
```

Play by id resumes the episode's saved position.

## Tree and ids

Root `__ROOT__` holds `__PODCASTS__` (every subscribed show as a
directory), New Releases, In Progress, `__DOWNLOADS__` and `__FILES__`.
The node `__UP_NEXT__` is never listed but browsable and returns the
real Up Next queue:

```
media_browse {"app": "au.com.shiftyjelly.pocketcasts", "node": "__UP_NEXT__"}
```

Ids are `<folder-uuid>#<episode-uuid>`; Downloads entries are `#<uuid>`.
They are opaque: lift them from a fresh browse rather than storing them.

## Queue, transport, custom actions

```
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "queue"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "queue:<id>"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "pause"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "jumpFwd"}
```

- "Next episode" is read the queue, then `queue:<id>` of the entry after
  the current one; the jump resumes that episode's saved position.
  `next` is not advertised and is silently ignored.
- Custom actions: `jumpBack`, `jumpFwd`, `changeSpeed`, `star`,
  `markAsPlayed`. Case-sensitive: `jumpfwd` is dropped, `jumpFwd` works.
- `pause` works on either route.

## `media_key`

Swiped away, the press does not revive the app. Paused with the
notification up, the press tears the player down and leaves a `stopped`
session that advertises `play` but ignores it. The browser-route `play`
above revives it.
