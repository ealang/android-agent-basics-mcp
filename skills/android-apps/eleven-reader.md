---
name: android-eleven-reader
description: Eleven Reader, the ElevenLabs reading app. Nothing can be driven. No media browser, no media-button listener, no exported service. Do not re-probe.
verified: 2026-09-26
---

# Eleven Reader

`io.elevenlabs.readerapp`. Nothing to drive; do not re-probe.

- No MediaBrowserService, so the browser route does not exist.
- No MEDIA_BUTTON listener on any receiver or service, so the targeted
  media-button revival has nothing to aim at.
- No exported service surface: the two exported services are
  permission-guarded framework plumbing, and the player service is
  internal.
- The only exported surface is activity-level (deep links, document
  SEND/VIEW, PROCESS_TEXT): screen required, no playback control.

A session the user starts by hand can be steered through the live-session
route like any other, but nothing can be started or chosen from the phone.
