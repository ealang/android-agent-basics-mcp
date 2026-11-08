---
name: android-eleven-reader
description: Eleven Reader, the ElevenLabs reading app. Nothing can be driven. No media browser, no media-button listener, no exported service.
verified: 2026-09-26
---

# Eleven Reader

Package `io.elevenlabs.readerapp`. Nothing can be driven.

| Capability | Eleven Reader |
|---|---|
| Screen off | ❌ nothing can be driven |
| Cold start | ❌ no media browser, no media-button listener, no exported service |
| Resume, pause | not tested, on a session the user started by hand |
| Play by name | ❌ |
| Play by id or URL | ❌ deep links open the app on screen, no playback control |
| Transport | not tested, on a session the user started by hand |
| Now playing | not tested |
| Queue | ❌ |
| Library | ❌ no browser |
| Search | ❌ no browser |

- No MediaBrowserService, so the browser route does not exist.
- No MEDIA_BUTTON listener on any receiver or service, so the targeted
  media-button revival has nothing to aim at.
- No exported service surface: the two exported services are
  permission-guarded framework plumbing, and the player service is
  internal.
- The only exported surface is activity-level (deep links, document
  SEND/VIEW, PROCESS_TEXT): screen required, no playback control.

A session the user starts by hand would be reachable through the
live-session route; that is not tested. Nothing can be started or chosen
from the phone.
