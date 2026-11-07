---
name: android-librofm
description: Libro.fm audiobook app. Play any owned audiobook from cold by ISBN with the screen locked, find a book's ISBN by search, pause, seek, jump, set speed, jump to a track; ids are stable and may be stored. No play by name.
verified: 2026-11-07
---

# Libro.fm

Package `fm.libro.librofm`. Fully open browser route: any owned book
plays from a dead process by ISBN, catalogue search returns the matching
book with its ISBN, and ids are public and permanent. Play by name does
not work; search for the ISBN, then play by id. Playback continues after
the connection closes.

| Capability | Libro.fm |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `media_play` by ISBN |
| Resume, pause | ✅ ✅ |
| Play by name | ❌ browser query and `search:` both re-seek the loaded book; use search for the ISBN |
| Play by id or URL | ✅ ISBN, any owned book · URL: not tested |
| Transport | ❌ previous chapter · ❌ next chapter, `next` and `previous` are ±15 s jumps · ✅ seek, within the track · ✅ jump, ±15 s or a relative seek · ✅ speed, remembered per book |
| Now playing | ✅ book title, position and duration per track, speed |
| Queue | ✅ read, one entry per track · ✅ jump to a track · ❌ add, ignored · ❌ move · ❌ remove |
| Library | ✅ Recent and the whole owned collection |
| Search | ✅ `media_search` returns the book with its ISBN |
| Also | — |

## Play a book

```
media_play {"app": "fm.libro.librofm", "media_id": "<isbn>"}
```

Works cold, resuming the saved position at the book's remembered
playback speed. Ids are the book's ISBN: stable and public, so an ISBN
may be stored and reused indefinitely instead of re-browsed. A finished
book, whose saved position is at the end of its last track, loads paused
at that end.

## Find a book

```
media_search {"app": "fm.libro.librofm", "query": "<title>"}
media_browse {"app": "fm.libro.librofm"}
```

Search returns the matching item with its ISBN. The root holds Recent
and Library; Library is the entire owned collection, no recency window.

`media_play` with a `query`, and `search:` on the session, do not switch
books: both leave the loaded book in place and re-seek it to its current
position, whatever the query.

## Transport

```
media_command {"app": "fm.libro.librofm", "action": "pause"}
media_command {"app": "fm.libro.librofm", "action": "play"}
media_command {"app": "fm.libro.librofm", "action": "seek:<seconds>"}
media_command {"app": "fm.libro.librofm", "action": "seek:-15"}
media_command {"app": "fm.libro.librofm", "action": "speed:1.5"}
```

- `seek:` takes an absolute second count within the current track, or
  `+N`/`-N`.
- `next` and `previous` are jumps of +15 s and −15 s, crossing track
  boundaries; nothing moves by chapter except the queue below.
- `speed:<x>` sets the speed, remembered per book; the read-back shows
  `Speed:` at anything but 1x.
- `play` and `pause` are not a toggle: `play` while playing keeps
  playing.

## Tracks as queue

```
media_command {"app": "fm.libro.librofm", "action": "queue"}
media_command {"app": "fm.libro.librofm", "action": "queue:<index>"}
```

The queue is the loaded book's track list, one entry per track, every
entry titled with the book's name and index-numbered. `queue:<index>`
jumps to that track. `queue_add:` is accepted and leaves the queue
unchanged.

## Now playing

Position and duration in read-backs are per track, not the whole book;
the title is the book's.
