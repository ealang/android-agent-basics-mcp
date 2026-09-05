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
- **YouTube Music** (`com.google.android.apps.youtube.music`)
- **Telegram** (`org.telegram.messenger`)

## YouTube — connects, but playback is paywalled

`com.google.android.youtube`. The session accepts strangers (empty browse
root, rating type reported), but every play route errors: playFromSearch
and playFromMediaId → "Action not supported"; plain play → **"Required
premium account to perform action in background"**. Media-session play
with no YouTube UI foregrounded is background playback, a Premium
feature. On a non-Premium account nothing works and nothing is
advertised. Retry playFromSearch only if the account gains Premium.

## Eleven Reader — no MediaBrowserService at all

Absent from `media_apps`: it ships no browser service, so this entire
mechanism cannot see it. If it posts a media notification while playing,
the active-MediaSession route (notification-listener permission) could
control transport — a possible future phone-mcp capability, noted in
[../README.md](../README.md), not a current one.

## Bluetooth system app

`com.android.bluetooth` appears in `media_apps` but browsing errors; it's
plumbing for AVRCP, not a target. Ignore.
