---
name: android-librofm
description: Libro.fm audiobook app. Play any owned audiobook from cold by ISBN or by title search, pause; ids are stable and may be stored.
verified: 2026-09-04
---

# Libro.fm

Package `fm.libro.librofm`. Fully open browser route, the cleanest of
the media apps: any owned book plays from a cold process by ISBN, title
search returns exact items, and ids are public and permanent. Playback
continues after the connection closes.

## Play a book

```
media_play {"app": "fm.libro.librofm", "media_id": "<isbn>"}
```

Works cold, resuming the saved position at the user's remembered
playback speed. Ids are the book's ISBN: stable and public, so an ISBN
may be stored and reused indefinitely instead of re-browsed.

## Find a book

```
media_search {"app": "fm.libro.librofm", "query": "<title>"}
media_browse {"app": "fm.libro.librofm"}
```

Search returns the matching item with its ISBN. The root holds Recent
and Library; Library is the entire owned collection, no recency window.

## Transport

```
media_command {"app": "fm.libro.librofm", "action": "pause"}
```

Position and duration in read-backs are per chapter track, not the whole
book.
