# Substack

- **Package**: `com.substack.app`
- **Tier**: open, with sharp edges — playback works, discovery is weak,
  ids are non-forgeable, the queue is ephemeral
- **Survives disconnect**: yes
- **Verified on device**: 2026-09-03/04

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## Tree and ids

Root → **Podcasts** (subscribed publications with podcasts — the only
view of subscriptions) and **All audio** (recent audio across
subscriptions). Both derive from the signed-in account.

**Recency window only**: All audio reaches back ~weeks. Older posts are
absent from the tree and **there is no route to play them** (see below).

**Ids are self-contained JSON blobs** (uri, entityId, title, author,
publicationId, artwork, durationMs, audioType TTS/Podcast/Voiceover).
They must be lifted **verbatim from a fresh browse**: hand-built blobs —
even ones whose uri is a valid public mp3 — are displayed happily by
queue_add but **refused by playFromMediaId**, which falls back to the
last-loaded item. The app only plays ids it minted. Never store these
long-term; re-browse.

## What works

- `playFromMediaId` with a fresh genuine blob — plays (TTS narrations and
  podcast episodes), keeps playing after disconnect. Verify by advancing
  position.
- **Queue read** — works (titles + ids; current item plus autoplay feed).
- **Queue insert** — `queue_add:<pos>:<id>` inserts at the exact index
  (confirmed repeatedly). Malformed adds are silently ignored.
  `queue_remove` untested.
- Now-playing metadata, `pause`, `seek`.

## What doesn't

- **Session search: unimplemented** (times out). `playFromSearch` is
  accepted but fire-and-forget.
- **`play_from_uri`: advertised but inert** — raw S3 audio URL and
  canonical post URL both silently ignored.
- **Saved / liked / history: not exposed.** No such tree nodes; guessed
  hidden ids return empty. (Caveat: unknown node ids return *empty*, not
  an error, so hidden-node probing is inconclusive here.)
- **The queue is not durable** — per-session state that dies with the
  process (hours, not days). Set the queue up immediately before playing;
  never expect it to persist. Queue edits with forged ids are cosmetic.

## Quirks

- **Fresh TTS posts may not play yet**: a brand-new post shows
  `durationMs: 0` and can hang in "buffering" until Substack generates
  the TTS audio server-side (the same item played fine the next day).
  Treat durationMs 0 on a post published today as "try again later".
- The queue UI is inside the full-screen player behind the Up Next icon —
  users may not find it elsewhere.

## Out-of-band discovery (find, but not play)

The anonymous web API answers what the session can't:

- `https://<pub>.substack.com/api/v1/archive?sort=new&search=<q>&limit=N`
  → posts (id, slug, title, audience, post_date), any age.
- `https://<pub>.substack.com/api/v1/posts/<slug>` →
  `audio_items[].audio_url` (the TTS mp3; withheld on `only_paid` posts).

Use it to identify content, then find the item in the browse tree. It
cannot mint playable ids, so content outside the tree window stays
unplayable through the media session.
