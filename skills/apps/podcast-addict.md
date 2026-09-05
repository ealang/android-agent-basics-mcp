# Podcast Addict

- **Package**: `com.bambuna.podcastaddict`
- **Tier**: open — browse and play work
- **Survives disconnect**: yes (independent foreground service)
- **Verified on device**: 2026-09-02

Mechanisms: [media-control.md](../media-control.md),
[media-discovery.md](../media-discovery.md).

## Tree and ids

Fully open tree. Ids are plain numeric database keys (e.g. episode
`14102`) — opaque and not guessable; re-browse to obtain them.

## What works

- `playFromMediaId` — plays.
- `playFromSearch` — free text resolved and played.
- `pause` — works.
- Advertised: play, pause, play_pause, stop, seek_to, play_from_media_id,
  play_from_search, play_from_uri, prepare.
- Custom actions: `com.bambuna.podcastaddict.REWIND`, `.FASTFORWARD`,
  `.STOP` (full package-prefixed ids).

## Untested

- Session search (`media_search`), queue operations, `play_from_uri`
  (advertised, never exercised).
