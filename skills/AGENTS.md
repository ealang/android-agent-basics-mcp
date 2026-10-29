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

## Before writing: discover, then verify

A file is written from what the device did, not from what the app's
manifest suggests it could do. The work runs in this order.

- **Start from the app's job.** Decide what the user mainly wants from
  the app before probing anything: resume and play a show for a podcast
  app, start navigation for a maps app, start a timer for a clock. That
  headline job, hands-free with the screen locked, is what the README
  marker rates; queue reading, search, speed and the rest are the extras
  to try once it works.
- **Read the nearest existing file first.** Another app of the same kind
  has usually met the same obstacle, and its file shows the route that
  solved it and the shape the new file should take.
- **Discover with the generic tools.** `media_apps` says whether the app
  has a browser service; `inspect` lists its exported services,
  receivers and providers with the actions their filters declare. The
  generic skill files give the routes and the order to try them. A route
  that `inspect` reveals is a lead, not a result, until it has been
  fired.
- **Verify every claim on the device.** Predict what a call should do,
  send it, and compare the read-back against the prediction: a media
  session's state, title and advancing position; a timer appearing; a
  navigation screen up. "Sent without error" proves nothing. Where no
  tool can read the outcome back, the user confirms it.
- **Test the conditions the file will claim.** Screen locked means the
  screen is actually off, not the app merely in the background. Try
  transport verbs both while paused and while playing. A play or search
  test must switch to different content, since resuming what was already
  there proves nothing. Read the session again after the connection has
  closed, because some apps stop when it does. Try a stored id later, so
  the file can say whether ids outlive a session.
- **Test from cold.** The hardest case, and the one a media app will
  usually meet, is a cold start: process dead, no notification, no live
  session, screen locked. Force stop (App info, Force stop) produces it
  on demand and is the standard cold test. A route that only works while
  the app is warm is a 🟡, and the file says so.
- **Run a failure down to its cause.** A negative result goes in the file
  with its reason, so the test is not finished at "did not work":
  distinguish a connection refused by allowlist, an action advertised
  but ignored, a permission guard, and a call accepted that does
  nothing.
- **Acceptance.** An agent with no familiarity with the project drives
  the app from the finished file alone. If it needs an `inspect` or a
  question, the file is not self-contained yet.

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
