---
name: android-media-discovery
description: Ways to find something playable in a media app, from the browse tree to session search to the vendor's web API, and what is usually not discoverable at all.
---

# Media discovery — finding something playable

Five ways, from most to least universal. The browse tree always exists
where the browser route connects; session search is rare; the queue and
now-playing metadata answer narrower questions; a vendor's web API finds
anything but cannot usually play it. An app's file in `android-apps/`
says which of these it supports and what its tree holds.

## 1. Browse the tree

```
media_browse {"app": "<package>"}
media_browse {"app": "<package>", "node": "<id>"}
```

What the tree contains is the app's choice: the whole owned or
subscribed catalogue in some apps, a recency window of recent items in
others, with older content absent and no route to it. Some apps answer
node ids they never list; the per-app file names them. Apps return empty
for an unknown node, not an error, so a guessed id learns nothing.

## 2. Session search

```
media_search {"app": "<package>", "query": "<text>"}
```

Free text in, playable ids out, where implemented. Most apps do not
implement it, and an unimplemented search times out rather than
erroring; the per-app file says where it works.

`media_play` with `"query"` is related but blind: the app resolves the
text and plays its best guess at once. The read-back's title is the only
report of what it chose.

## 3. Read the queue

```
media_command {"app": "<package>", "action": "queue"}
```

The current play queue with titles and ids. Real discovery in apps that
keep a persistent queue; filler in apps whose queue is autoplay for the
current session.

## 4. Now-playing metadata

Every read-back carries the loaded item's title, position and duration.
No reach beyond the current item, but it answers "what is this?".

## 5. The vendor's public web API

Where the session's discovery is too weak, the vendor's web API may find
anything ever published. The limit: a web API finds content but cannot
mint ids the app will play. Use it to learn whether something exists and
what it is called, then find the item in the browse tree. The per-app
file gives the endpoints where this is useful.

## Not discoverable

Saved items, likes, listening history and account state are not exposed
to media-session clients. Assume absent unless the per-app file says
otherwise.
