# Skills — what each app can do, without experimenting

phone-mcp exposes generic automation interfaces rather than including
support for any particular app; these files are the knowledge that
drives specific apps through them — verified recipes, found by
experiment on a real device. The split:

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

## Writing and updating these files

A skill file states current behavior, succinctly — it is read as
operating knowledge, so every sentence should change what a caller does
next.

- **Current state only.** State what works and the correct way to do
  it. Don't narrate how it was discovered, what the file used to say,
  or the bug that prompted the edit — when behavior changes, rewrite
  the sentence. Git keeps the history; this directory is only the conclusions.
- **One date survives:** the "verified on device" stamp, because it
  says how stale a file might be.
- **Negative results are welcome, as warnings, with the reason** —
  "signature-locked, don't re-probe", "accepted then silently ignored"
  — not as experiment logs.
- **Nothing outside the repo's own world.** No paths into agent
  workspaces or group folders, no references to private apps that are
  not documented here deliberately, and no snapshots of the user's
  actual data (alarm rows, play history) — describe the schema, never
  the contents.

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
- [alarms-and-timers.md](alarms-and-timers.md) — timers and alarms through the AlarmClock
  intents, handled by whatever app the system resolves them to: setting
  works, dismissing is a silent no-op, nothing can be read back.
- [watch-control.md](watch-control.md) — driving a Pebble watchapp through
  the `pebblekit_*` family: launch, raw AppMessage, the one-call
  launch-and-send handshake, the silent discard when the app is not
  running, and what an ACK does and does not prove.

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
| Google Maps | [apps/google-maps.md](apps/google-maps.md) | start navigation hands-free via the navigation URI; everything else is one tap |
| Samsung Clock | [apps/samsung-clock.md](apps/samsung-clock.md) | set timer works; dismiss is a silent no-op; stopwatch signature-locked — don't re-probe |
| Sleep as Android | [apps/sleep-as-android.md](apps/sleep-as-android.md) | alarm provider closed to us and its WRITE signature-locked — no retime, enable or delete; don't re-probe |
| Agent Voice Gateway | [apps/agent-voice-gateway.md](apps/agent-voice-gateway.md) | designed for automation: provider reads + `provider_call` stop + start intent |
| Agent Timer (Pebble) | [apps/pebble/agent-timer.md](apps/pebble/agent-timer.md) | one `pebblekit_launch_and_send` call starts a timer; ping key 1, duration key 20 |
| Eleven Reader, others | [apps/unavailable.md](apps/unavailable.md) | no usable route — don't re-probe |

## Future generic capabilities (not yet built)

Candidates for the same treatment once the tool surface exists:

- Receiving AppMessages *from* the watch — designed, not yet built: a
  mailbox the agent drains, fed by PebbleKit 2 delivery for watchapps
  that declare phone-mcp as their companion.
