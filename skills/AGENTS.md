# Skill files — how they are organised and written

phone-mcp exposes generic automation interfaces rather than support for
any particular app. These files are the knowledge that drives specific
apps through them: verified recipes, found by experiment on a real
device, written so that an agent reading one can act without
rediscovering anything. If a file says search is unimplemented, don't
try search.

## Layout

- **Top level** — generic skills. Each describes a *mechanism* phone-mcp
  exposes that works the same way against any app: an Android or Pebble
  API, remoted, and how to use it well.
- **`android-apps/`** — one file per Android app: what that app answers
  when driven through the mechanisms, its ids, its quirks, what silently
  fails.
- **`pebble-apps/`** — one file per Pebble watchapp: its UUID, its
  message keys, the calls that drive it.

An app file is self-contained. Its calls are written out in full even
when a generic file explains the same mechanism, so a reader with only
the app file can act.

The user-facing summary, app by app, is the pair of tables in the root
README, Android then Pebble, ✅ rows first. They are maintained by hand: a
new app file gets a row.

## Front matter

Every file starts with YAML front matter in the shape agent skill
loaders expect, and nothing else goes there:

```yaml
---
name: android-pocket-casts
description: Pocket Casts podcast app. Resume or start playback, play any
  subscribed show by name, read and jump within the queue.
verified: 2026-10-02
---
```

- `name` — the filename's stem with a platform prefix, `android-` or
  `pebble-`, for app files and generic files alike. Filenames themselves
  stay short.
- `description` — one or two sentences: which app or mechanism, and what
  can be done with it, so an agent reading only this line knows whether
  to open the file. Lead with the app's name. Mention what does not work
  only when it is the headline.
- `verified` — the date of the last on-device check, app files only.
  Date alone, no app version: the assumption is that the app was current
  when tested, and a version string is stale by the time anyone reads it.

## Writing the body

A skill file states current behaviour, succinctly. It is read as
operating knowledge, so every sentence should change what a caller does
next.

- **Header shape.** After the front matter: the title, one line naming
  the package (or the watchapp's UUID), then the verdict. No other
  metadata in the body; the front matter holds it.
- **Verdict first.** Open with what the app can and cannot do, in a
  sentence or two, before any detail.
- **Literal calls.** Every working route is shown as the exact call an
  agent would make: tool, arguments, typed extras. The `app` argument is
  always the package name. Use placeholders for the variable parts
  (`<title>`, `<id>`), never a real title, query or address from the
  test device.
- **What did not work, with the reason.** Negative results are stated
  as facts with the cause, in terms a reader can weigh: refused at
  connection by a signature allowlist, advertised but ignored, guarded
  by a signature-level permission, accepted and does nothing. Not as
  experiment logs, and not as instructions.
- **Neutral on verification and retrying.** A file describes what a
  call does and what its result contains. It does not tell the caller
  to verify, re-read, poll, retry or refrain from any of those; the
  calling agent decides what its situation needs.
- **Current state only.** State what works and the correct way to do it.
  Don't narrate how it was discovered, what the file used to say, or the
  bug that prompted the edit; when behaviour changes, rewrite the
  sentence. Git keeps the history. The only date in a file is the
  `verified` stamp.
- **No untested, future or to-do sections.** A route is either verified
  and documented or left out. Settle it before the file ships, or omit
  it.
- **Nothing outside the repo's own world.** No paths into agent
  workspaces, no references to apps deliberately not documented here,
  and no snapshots of the user's own data (alarm rows, play history,
  library contents). Describe the schema, never the contents.
