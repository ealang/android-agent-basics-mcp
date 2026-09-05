# AIMP

- **Package**: `com.aimp.player`
- **Tier**: open — but playback **lives only while a browser client stays
  connected**
- **Survives disconnect**: **no** (the defining quirk)
- **Verified on device**: 2026-09-02/03 (including a deferred-disconnect
  experiment)

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## The lifecycle trap

When a browser client wakes AIMP and starts playback, AIMP never promotes
itself to an independent service: the moment the client disconnects, the
foreground service drops, the wakelock releases, and the media session is
destroyed — playback stops *exactly* at disconnect (proven by deferring
the disconnect 60s: audio ran 60s). Android Auto stays connected for a
whole drive, which is presumably why AIMP never handles this case.

**Consequence**: a fire-and-forget play against AIMP yields ~3 seconds of
audio and a read-back that honestly said PLAYING. Driving AIMP for real
requires holding the browser connection for the duration of playback —
a capability phone-mcp does not currently have. Until it does, treat
AIMP as *browse/inspect yes, sustained playback no*.

## Tree and ids

Fully open: Folders, My Music, playlists (`playlist/<uuid>` dirs with
tracks). **Privacy note**: any browser client can enumerate the library,
including voice memos and dictations stored as tracks.

## Advertised (richest set surveyed)

play/pause/stop, skip_to_next/previous, skip_to_queue_item, seek_to,
set_rating, play_from_media_id/search/uri. Custom: `toggleLiked`, `exit`,
`prevGroup`, `nextGroup`, `toggleRepeatMode`, `toggleShuffleMode`.

Transport commands against an *already-playing* AIMP (started from its
own UI) are untested but should work — the trap is only about who keeps
the process alive.
