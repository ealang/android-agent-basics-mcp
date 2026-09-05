# Libro.fm

- **Package**: `fm.libro.librofm`
- **Tier**: open — the cleanest interface surveyed; everything works
- **Survives disconnect**: assumed yes (played past read-back; not
  explicitly stress-tested)
- **Verified on device**: 2026-09-04

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## Tree and ids

Root: **Recent** and **Library** — Library is the *entire owned
collection*, no recency window.

Ids are plain **ISBNs** (Neuromancer = `9781101536698`). Stable, public,
durable: **ids may be hard-coded in skills/automations indefinitely** —
the one surveyed app where storing ids beats re-browsing.

## What works

- `playFromMediaId(ISBN)` — works **cold**: app unopened for weeks,
  playing within 3s, resumed saved position *and* remembered playback
  speed (1.2x).
- Session search (`media_search`) — implemented; exact title → exact item
  with its ISBN.
- `pause` — works.
- Advertised: pause, play, play_pause, stop, skip_to_next/previous,
  skip_to_queue_item, fast_forward, rewind, seek_to, play_from_media_id,
  play_from_search, play_from_uri, prepare, set_rating.

## Notes

- Position/duration in read-backs are per **chapter track** (e.g. "75s of
  300s"), not the whole book.
- Broad advertised set (queue ops, uri, rating) exists but only the core
  path above has been exercised.
