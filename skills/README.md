# Skills — what each app can do, without experimenting

This directory is the distilled, agent-facing output of the empirical work
in `docs/media-survey.md` (which remains the raw lab notebook, wrong turns
included). The split:

- **Generic skills** (this directory) describe *mechanisms* — capabilities
  phone-mcp exposes that work the same way against any app. They are the
  Android APIs, remoted.
- **Per-app skills** (`apps/`) describe *data* — what one specific app
  answers when driven through those mechanisms: its id format, tree shape,
  what works, what silently doesn't, and every quirk found on a real device.

An agent (or a user of this repo) pulls the generic skill for the mechanism
it needs plus the files for the apps actually installed. Nothing here
requires re-discovery: if a per-app file says search is unimplemented,
don't try search.

## Generic skills

- [media-control.md](media-control.md) — driving apps through their
  MediaBrowserService: connection tiers, the proven play pattern, transport
  commands, queue read/write, verification.
- [media-discovery.md](media-discovery.md) — every way to find content:
  browse, session search, queue, now-playing, and out-of-band web APIs.
- [app-launch.md](app-launch.md) — when a launch is needed at all, and the
  one class of app that must be foregrounded.

## Per-app skills

| App | File | Verdict |
|---|---|---|
| Pocket Casts | [apps/pocket-casts.md](apps/pocket-casts.md) | everything works |
| Podcast Addict | [apps/podcast-addict.md](apps/podcast-addict.md) | everything works |
| Simple Radio | [apps/simple-radio.md](apps/simple-radio.md) | everything works |
| Libro.fm | [apps/librofm.md](apps/librofm.md) | everything works; cleanest interface |
| Substack | [apps/substack.md](apps/substack.md) | works with sharp edges |
| AIMP | [apps/aimp.md](apps/aimp.md) | plays only while connected |
| YouTube | [apps/youtube.md](apps/youtube.md) | VIEW intent cold-starts any video; fully drivable once playing |
| Google Maps | [apps/google-maps.md](apps/google-maps.md) | start navigation hands-free; everything else is one tap |
| Audible, others | [apps/unavailable.md](apps/unavailable.md) | no usable route — don't re-probe |

Not documented here: apps with purpose-built integrations in phone-mcp
itself (the Pebble watchapps, the voice gateway, the recorder). Their
contracts live in `docs/` next to the code that implements them; these
skills cover only apps driven through the *generic* surfaces.

## Future generic capabilities (not yet built)

Candidates for the same treatment once the tool surface exists:

- Generic Pebble AppMessage commands (the watch equivalent of intents).
- Active-MediaSession control via notification-listener — would reach apps
  with no MediaBrowserService (e.g. Eleven Reader) while they are playing.
