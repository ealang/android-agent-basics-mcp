---
name: android-pocket-casts
description: Pocket Casts podcast app. Resume or start playback, play any subscribed show or episode by name or id, read and edit the Up Next queue, skip to the next episode, jump and change speed, search the catalogue, refresh the feeds; all of it works cold with the screen locked.
verified: 2026-11-06
---

# Pocket Casts

Package `au.com.shiftyjelly.pocketcasts`. Playback, search and the queue
work through the browser route, from a cold process with the screen
locked. Two exported broadcast receivers add what the session refuses:
next episode, queue editing, downloads, a feed refresh. Playback
continues after the connection closes. `media_key` does not work on this
app.

| Capability | Pocket Casts |
|---|---|
| Screen off | ✓ everything below |
| Cold start | ✓ process dead, `play` revives it at the saved position |
| Resume, pause | ✓ ✓ |
| Play by name | ✓ any subscribed show or episode |
| Play by id or URL | ✓ id from a browse · ✗ URL, `uri:` is ignored |
| Transport | ✗ previous episode · ✓ next episode, by broadcast · ✓ seek · ✓ jump, +30 s and −10 s · ✓ speed, stepped through the app's presets |
| Now playing | ✓ title, position, duration, speed |
| Queue | ✓ read · ✓ jump to an entry · ✓ add to top or bottom · ✓ move · ✓ remove, by archiving the episode |
| Library | ✓ subscriptions, new releases, in progress, downloads |
| Search | ✓ the whole catalogue, shows only |
| Also | ✓ refresh the feeds · ✓ download an episode |

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

Search covers the whole Pocket Casts catalogue, not just subscriptions,
and returns shows as directories; browse one for its episodes. Play by
id resumes the episode's saved position. `uri:<url>` is accepted and
ignored on both routes.

## Tree and ids

Root `__ROOT__` holds `__PODCASTS__` (every subscribed show as a
directory), New Releases, In Progress, `__DOWNLOADS__` and `__FILES__`.
The node `__UP_NEXT__` is never listed but browsable and returns the
real Up Next queue, current episode first:

```
media_browse {"app": "au.com.shiftyjelly.pocketcasts", "node": "__UP_NEXT__"}
```

Ids are `<folder-uuid>#<episode-uuid>`; Downloads entries are `#<uuid>`.
They are opaque: lift them from a fresh browse rather than storing them.
The part after `#` is the episode uuid the broadcasts below take.

## Transport

```
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "pause"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "seek:<seconds>"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "jumpFwd"}
media_command {"app": "au.com.shiftyjelly.pocketcasts", "action": "changeSpeed"}
```

- `pause` works on either route. `seek:` takes an absolute second count
  or `+N`/`-N`.
- `jumpFwd` is +30 s and `jumpBack` −10 s. The session's `next` and
  `previous` do the same two jumps; neither changes episode. Custom
  actions are case-sensitive: `jumpfwd` is dropped, `jumpFwd` works.
- `speed:<x>` is accepted and ignored. `changeSpeed` steps through the
  app's presets, 0.6 → 0.8 → 1.0 → 1.2 → 1.4 → 1.6 → 1.8 → 2.0 → 3.0 and
  round to 0.6. `media_sessions` reports `Speed: <x>x` at any setting
  but 1.0, where the field is absent.
- Next episode is a broadcast:

```
send_intent
{
  "action": "au.com.shiftyjelly.pocketcasts.action.NEXT",
  "package": "au.com.shiftyjelly.pocketcasts",
  "mode": "broadcast"
}
```

  It drops the current episode from Up Next and plays the first entry at
  its saved position. There is no previous episode: a dropped episode
  leaves the queue, and the session's `previous` is a 10 s jump. The same
  receiver takes `SKIP_FORWARD` (+30 s) and `SKIP_BACKWARD` (−10 s) in
  place of `NEXT`.

## Read the queue, jump within it

```
media_browse   {"app": "au.com.shiftyjelly.pocketcasts", "node": "__UP_NEXT__"}
media_command  {"app": "au.com.shiftyjelly.pocketcasts", "action": "queue"}
media_command  {"app": "au.com.shiftyjelly.pocketcasts", "action": "queue:<id>"}
```

`__UP_NEXT__` is the full queue with browse ids, current episode first.
The session's `queue` is the same list without the current episode,
under numeric ids; `queue:<id>` takes one of those and starts that entry
at its saved position. `queue_add:` and `queue_remove:` on the session
are refused ("doesn't support queue management operations"); editing is
the broadcast below.

## Edit the queue, download, archive

One broadcast, with the episode uuid and the operation as string extras:

```
send_intent
{
  "action": "au.com.shiftyjelly.pocketcasts.action.NOTIFICATION_ADD_TO_UP_NEXT",
  "package": "au.com.shiftyjelly.pocketcasts",
  "mode": "broadcast",
  "extras": [
    {"key": "EPISODE_UUID", "type": "string", "value": "<episode-uuid>"},
    {"key": "EXTRA_ACTION", "type": "string", "value": "<operation>"}
  ]
}
```

`EXTRA_ACTION` chooses what happens; all values start with
`au.com.shiftyjelly.pocketcasts.action.`:

- `NOTIFICATION_PLAY_NEXT` — puts the episode at the top of Up Next,
  right after the current one. An episode already in the queue moves
  there.
- `INTENT_ACTION_PLAY_LAST` — puts it at the bottom; also moves an
  existing entry.
- `INTENT_ACTION_ARCHIVE` — removes the episode from Up Next by
  archiving it: it is hidden from its show's episode list and its
  download, if any, is deleted. Adding it back to Up Next with either
  operation above unarchives it; the download does not come back on its
  own.
- `NOTIFICATION_DOWNLOAD_EPISODE` — downloads the episode, on any
  network. It appears under `__DOWNLOADS__` once complete.

There is no read-back from the broadcast itself; `__UP_NEXT__` and
`__DOWNLOADS__` show the result.

## Refresh the feeds

```
send_intent
{
  "action": "au.com.shiftyjelly.pocketcasts.action.REFRESH_PODCASTS",
  "package": "au.com.shiftyjelly.pocketcasts",
  "mode": "broadcast"
}
```

Pulls every subscribed show for new episodes, the same refresh the app
runs on its own schedule. There is no read-back; the app's own
last-refresh time is the only sign.

## `media_key`

Swiped away, the press does not revive the app. Paused with the
notification up, the press tears the player down and leaves a `stopped`
session that advertises `play` but ignores it. The browser-route `play`
above revives it.
