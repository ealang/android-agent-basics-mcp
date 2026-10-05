# Apps with no ability to be automated at all — don't re-probe

This file is only for apps where **every** route has been exhausted:
browser, session, and the exported-surface scan (`intent_scan` +
`send_intent foreground_service`). A refused browser route alone no
longer qualifies — YouTube Music and Smart AudioBook Player both turned
out fully drivable through other doors and have their own files.

## Browser route refused, other routes not yet scanned

`onGetRoot` signature allowlist (verified 2026-09-02/04) — that route
is closed forever, but after the 2026-09-26 findings these need an
`intent_scan` pass before they can be called unavailable:

- **Google app** (`com.google.android.googlequicksearchbox`)

(Audible was scanned 2026-09-27 and turned out fully drivable — see
[audible.md](audible.md). Telegram has not been scanned.)

## Eleven Reader — scanned, nothing to drive

`io.elevenlabs.readerapp`, scanned 2026-09-26. No MediaBrowserService,
no MEDIA_BUTTON listener, no exported service surface at all — the two
exported services are permission-guarded framework plumbing, the player
service is internal. The only exported surface is activity-level
(deep links, document SEND/VIEW, PROCESS_TEXT): screen required, no
playback control. Transport-steering a live session is possible in
principle but doesn't clear the automation bar. Don't re-probe.

## Bluetooth system app

`com.android.bluetooth` appears in `media_apps` but browsing errors; it's
plumbing for AVRCP, not a target. Ignore.
