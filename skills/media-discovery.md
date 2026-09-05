# Media discovery — every way to find something playable

Ordered from most to least universal. Per-app files state which of these
each app actually supports.

## 1. Browse the tree (`media_browse`)

Always available in the open tier. What the tree *contains* is the app's
choice and varies enormously:

- Full catalog of owned/subscribed content (Libro.fm: entire library;
  Pocket Casts: all 57 subscribed shows).
- A recency window only (Substack: ~weeks of recent audio; older posts
  are simply absent with no route to them).
- **Unlisted nodes exist.** Pocket Casts answers `__UP_NEXT__` though it
  never lists it. Probing guessed node ids is worth one attempt per app —
  but note some apps (Substack) return *empty* rather than an error for
  unknown ids, which makes negative results inconclusive.

## 2. Session search (`media_search`)

The highest-value route where it works — free text in, playable ids out —
but the rarest: of the surveyed open-tier apps only Pocket Casts and
Libro.fm implement it. Unimplemented search *times out* rather than
erroring.

`media_play` with `query` (playFromSearch) is related but blind: the app
resolves the text and immediately plays its best guess. Works in Pocket
Casts, Podcast Addict, Simple Radio. No result list, no confirmation of
what it picked beyond the read-back title.

## 3. Read the queue (`media_command action=queue`)

The current play queue with titles and ids. Genuine discovery in apps
that maintain a persistent queue (Pocket Casts' Up Next), less so where
the queue is session-ephemeral autoplay filler (Substack).

## 4. Now-playing metadata

Every read-back includes the loaded item's title/position/duration. Zero
discovery reach, but answers "what is this?".

## 5. Out-of-band: the app's public web API

When the session's discovery is too weak, the vendor's public web API may
find anything ever published — e.g. Substack's anonymous archive search
(`https://<pub>.substack.com/api/v1/archive?search=<q>`) and post lookup
(`/api/v1/posts/<slug>`, which even exposes the TTS mp3 URL).

**The hard limit**: web APIs find content but usually **cannot mint ids
the app will play**. Substack refuses hand-built ids and ignores
playFromUri, so its web API is discovery-only. Treat this route as a way
to answer "does it exist / what is it called", then look for the item in
the browse tree — not as a play path.

## What is typically NOT discoverable

Saved items, likes, listening history, and account state are generally
not exposed to media-session strangers (verified absent in Substack;
Pocket Casts' In Progress node is the exception that proves the rule).
Assume absent unless the per-app file says otherwise.
