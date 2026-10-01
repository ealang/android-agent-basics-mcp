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

- [media-control.md](media-control.md) — driving apps through their media
  sessions, by two routes: the MediaBrowserService (connection tiers, the
  proven play pattern, browse/queue) and the active-session route
  (`media_sessions`, `media_key`, `route: session`) that reaches apps the
  browser route cannot — plus the targeted media-button recipe
  (`send_intent` with a `key_event` extra) that revives an app neither
  route can reach.
- [media-discovery.md](media-discovery.md) — every way to find content:
  browse, session search, queue, now-playing, and out-of-band web APIs.
- [app-launch.md](app-launch.md) — when a launch is needed at all, and the
  one class of app that must be foregrounded.
- [watch-control.md](watch-control.md) — driving a Pebble watchapp: launch,
  raw AppMessage, the silent discard when the app is not running, and what
  an ACK does and does not prove.

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
| YouTube Music | [apps/youtube-music.md](apps/youtube-music.md) | fully drivable despite refused browser route: targeted-button revival + play-by-URL |
| Smart AudioBook Player | [apps/smart-audiobook.md](apps/smart-audiobook.md) | fully drivable despite refused browser route: revive via exported service actions, any book by name via session search |
| Audible | [apps/audible.md](apps/audible.md) | fully drivable despite refused browser route: media-button revival (two-step) + the richest session, including real absolute seek |
| Google Maps | [apps/google-maps.md](apps/google-maps.md) | start navigation hands-free; everything else is one tap |
| Samsung Clock | [apps/samsung-clock.md](apps/samsung-clock.md) | timers and alarms via public actions; stopwatch signature-locked — don't re-probe |
| Agent Voice Gateway | [apps/agent-voice-gateway.md](apps/agent-voice-gateway.md) | designed for automation: provider reads + `provider_call` stop + start intent |
| Agent Timer (Pebble) | [apps/pebble-agent-timer.md](apps/pebble-agent-timer.md) | key table for the generic path; `set_watch_timer` is easier |
| Eleven Reader, others | [apps/unavailable.md](apps/unavailable.md) | no usable route — don't re-probe |

Does an app ever deserve a tool of its own instead of a skill file? It
almost never does — phone-mcp is generic-first (`CLAUDE.md`,
`docs/generic-first.md`), and the voice gateway is the proof: its
app-specific tools were removed once its skill file covered everything
they did (its arrival here is what `provider_call` was built for), so
the generic route is now its only route, and every remaining per-app
tool is being converted the same way. The one worked example of
app-aware surface that stays is the Pebble watchapp handshake
(start → ping until ACKed → send once) — a *sequence* that cannot be
expressed as fire-and-forget generic calls, generalized across
watchapps rather than owned by any one of them. The Agent Timer file
is the key table that rides on it.

## Future generic capabilities (not yet built)

Candidates for the same treatment once the tool surface exists:

- Receiving AppMessages *from* the watch — designed, not yet built: a
  mailbox the agent drains, fed by PebbleKit 2 delivery for watchapps
  that declare phone-mcp as their companion. `docs/pebble-platform.md`
  has the design and its limits.
