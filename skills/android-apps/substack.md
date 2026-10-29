---
name: android-substack
description: Substack app audio. Play recent podcast episodes and narrated posts from subscribed publications, read and edit the play queue, pause, seek. No search and no route to older posts.
verified: 2026-09-04
---

# Substack

Package `com.substack.app`. The browser route plays recent audio from
subscribed publications and reads and edits the queue; it has no search,
no route to older posts, ids that must come from a fresh browse, and a
queue that dies with the process. Playback continues after the
connection closes.

## Find and play

```
media_browse {"app": "com.substack.app"}
media_browse {"app": "com.substack.app", "node": "<id>"}
media_play   {"app": "com.substack.app", "media_id": "<id>"}
```

The root holds Podcasts (subscribed publications with podcasts, the only
view of subscriptions) and All audio (recent audio across subscriptions,
narrated posts included). All audio reaches back a few weeks; older
posts are absent and nothing plays them.

Ids are JSON blobs the app mints. Lift one verbatim from a fresh browse
every time: a hand-built blob, even with a valid public mp3 in it, is
refused by play and the app falls back to the last-loaded item. Never
store them.

## Queue and transport

```
media_command {"app": "com.substack.app", "action": "queue"}
media_command {"app": "com.substack.app", "action": "queue_add:<pos>:<id>"}
media_command {"app": "com.substack.app", "action": "pause"}
media_command {"app": "com.substack.app", "action": "seek:<seconds>"}
```

The queue read returns the current item plus the autoplay feed, with
ids. Insert lands at the exact index; a malformed add is silently
ignored. The queue is per-process state that dies with the app, so set
it up immediately before playing.

## Does not work

- `media_search`: unimplemented, times out. `media_play` with `query`
  is accepted and does nothing.
- `uri:` on the session: advertised and inert, for raw audio URLs and
  post URLs alike.
- Saved, liked and history: no such nodes. An unknown node id returns
  empty rather than an error.
- A post published today may show `durationMs: 0` and hang in buffering
  until Substack generates its narration server-side. Try again later.

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
