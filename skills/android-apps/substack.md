---
name: android-substack
description: Substack app audio. Play recent podcast episodes and narrated posts from subscribed publications, from a dead process with the screen locked; read and edit the play queue, pause, seek, set speed. No search and no route to older posts.
verified: 2026-11-07
---

# Substack

Package `com.substack.app`. The browser route plays recent audio from
subscribed publications and reads and edits the queue; the session
takes transport, seek and speed. It has no search, no route to older
posts, ids that must come from a fresh browse, and a queue that dies
with the process. Playback continues after the connection closes.

| Capability | Substack |
|---|---|
| Screen off | ✅ everything below |
| Cold start | ✅ process dead, `media_play` by id from a fresh browse |
| Resume, pause | ✅ ✅ |
| Play by name | ❌ query accepted, nothing changes |
| Play by id or URL | ✅ id from a browse · ❌ URL, `uri:` ignored |
| Transport | ❌ previous, restarts the current item · ✅ next · ✅ seek · ✅ jump, as a relative seek · ✅ speed |
| Now playing | ✅ title, position, duration, speed |
| Queue | ✅ read · ✅ jump to an entry · ✅ add at a position · ❌ move · ✅ remove |
| Library | ✅ recent podcast episodes, all recent audio; a few weeks back |
| Search | ❌ `media_search` never answers |
| Also | — |

## Find and play

```
media_browse {"app": "com.substack.app"}
media_browse {"app": "com.substack.app", "node": "<id>"}
media_browse {"app": "com.substack.app", "node": "<id>", "offset": 40}
media_play   {"app": "com.substack.app", "media_id": "<id>"}
```

The root holds Podcasts (recent podcast episodes across the subscribed
publications, a flat list) and All audio (recent audio across
subscriptions, narrated posts included, paged 40 at a time through
`offset`). Both reach back a few weeks; older posts are absent and
nothing plays them.

Ids are JSON blobs the app mints. Lift one verbatim from a fresh browse
every time: a hand-built blob, even with a valid public mp3 in it, is
refused by play and the app falls back to the last-loaded item. Never
store them.

Play by id works from a dead process. An item listed with
`durationMs: 0`, a narration not yet generated when it was listed, plays
with its real duration once loaded.

## Transport

```
media_command {"app": "com.substack.app", "action": "pause"}
media_command {"app": "com.substack.app", "action": "play"}
media_command {"app": "com.substack.app", "action": "next"}
media_command {"app": "com.substack.app", "action": "seek:<seconds>"}
media_command {"app": "com.substack.app", "action": "seek:+30"}
media_command {"app": "com.substack.app", "action": "speed:1.5"}
```

`next` moves to the next queue item. `previous` restarts the current
item from any position and never moves to the previous one. `seek:`
takes an absolute second count or `+N`/`-N`. `speed:<x>` sets the
speed, shown in the read-back.

## Queue

```
media_command {"app": "com.substack.app", "action": "queue"}
media_command {"app": "com.substack.app", "action": "queue:<index>"}
media_command {"app": "com.substack.app", "action": "queue_add:<pos>:<id>"}
media_command {"app": "com.substack.app", "action": "queue_remove:<id>"}
```

The queue read returns the current item plus the autoplay feed,
index-numbered with ids. `queue:<index>` jumps to an entry. Insert lands
at the exact index; a malformed add is silently ignored. Remove takes
the entry's id. The queue is per-process state that dies with the app,
so set it up immediately before playing.

## Does not work

- `media_search`: unimplemented, never answers. `media_play` with
  `query` is accepted and does nothing.
- `uri:` on the session: advertised and inert, for raw audio URLs and
  post URLs alike.
- Saved, liked and history: no such nodes. An unknown node id returns
  empty rather than an error.

## Finding older content: the web API

The anonymous web API answers what the session cannot, but it cannot
mint playable ids, so it identifies content only:

- `https://<publication>.substack.com/api/v1/archive?sort=new&search=<query>&limit=<n>`
  returns posts of any age (id, slug, title, audience, post_date).
- `https://<publication>.substack.com/api/v1/posts/<slug>` returns the
  post, with `audio_items[].audio_url` for the narration (withheld on
  paid-only posts).

Use it to name the item, then look for it in the browse tree. Content
outside the tree's window stays unplayable through the media session.
