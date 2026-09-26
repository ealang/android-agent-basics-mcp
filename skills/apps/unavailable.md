# Apps with no usable media route — don't re-probe

Verified on device 2026-09-02, re-confirmed 2026-09-04 where noted. These
conclusions are policy/architecture, not transient state; re-testing them
wastes time and (for the refused tier) cannot ever succeed from this
codebase.

## Refused at the door (signature allowlist)

`onGetRoot` returns null for callers that aren't Android Auto / Wear-class
signed packages. No browse, no session, no play — no workaround on the
MediaBrowser route.

- **Audible** (`com.audible.application`) — re-confirmed 2026-09-04
- **Smart AudioBook Player** (`ak.alizandro.smartaudiobookplayer`) —
  re-confirmed 2026-09-04
- **Google app** (`com.google.android.googlequicksearchbox`)
- **YouTube Music** (`com.google.android.apps.youtube.music`) — refused
  *on this route*, but reachable through the active-session route while
  playing or paused: see [youtube-music.md](youtube-music.md)
- **Telegram** (`org.telegram.messenger`)

## Eleven Reader — no MediaBrowserService at all

Absent from `media_apps`: it ships no browser service, so this entire
mechanism cannot see it. If it posts a media notification while playing,
the active-MediaSession route (notification-listener permission) could
control transport — a possible future phone-mcp capability, noted in
[../README.md](../README.md), not a current one.

## Bluetooth system app

`com.android.bluetooth` appears in `media_apps` but browsing errors; it's
plumbing for AVRCP, not a target. Ignore.
