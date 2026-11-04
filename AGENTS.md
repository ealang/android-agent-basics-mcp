# Working on android-agent-basics-mcp

## Generic-first: what the tool surface is

The product is **generic tools plus skill files**. The tools —
`inspect`, `send_intent`, `intent_resolve`, the `provider_*` and
`media_*` families — are raw Android capabilities, remoted, for a
capable agent. Per-app knowledge lives in `skills/`: an agent explores
an app through the generic tools, and what it learns becomes a skill
file, not a new tool. Maps is the worked example — `send_intent`
experiments → recorded findings → a skill file over geo URIs.

The generic tools execute exactly what they are told: no guards beyond
our own grants, no protection against a send the target will silently
discard. Where a mechanism is offered at all it is opt-in and
default-off (`send_intent`'s `via_unlock_gate`) — the caller's
judgment, never our guard. Adding a guard to a generic tool constrains
what it can express; don't.

Two kinds of specific tools sit beside them:

- **Phone-itself tools** — `get_location`, `set_flashlight`, the
  `find_my_phone_*` pair, the notification pair, the volume pair.
  Capabilities of the phone with no generic route. Not app
  integrations at all.
- **Pebble tools** — the `pebblekit_*` four. The watch is a platform
  (there is no generic Android route to a BLE peripheral), so watch
  operations are specific the way phone operations are: generic over
  *watchapps*, specific to none.

An integration that needs a permission gets a manifest line and
grant-guiding UI, not a tool.
If a proposed tool names a third-party app, the answer is a skill file.

**No app-specific grants.** That manifest line is a platform permission.
A permission defined by another app — a content provider's own
READ/WRITE, a vendor's guard on an internal entry point — is never
declared here, however useful the surface behind it: it ties the
generic server to one app, and the signature-level ones cannot be
granted to us at all. When an app's surface sits behind such a grant,
the route is a bridge: a separate bridge or plugin app that can hold the
grant and re-exposes the function over an interface Agent Basics MCP reaches
through the generic tools, an exported provider or service. If the
capability deserves protection, the bridge checks its caller's
signature. The bridge then gets a skill file like any other app.

## Responses: state what was done, or what was observed

Decide the level of reporting from what the platform gives back.

- **Fire-and-forget: say what was done**, in terms of the boundary
  crossed. "START sent to the Pebble app." "Notification posted." Not
  the effect, which nobody observed.
- **Observable: say what was observed**, and no more. "The watch
  acknowledged the message." "No ACK or NACK came back within 5s. It
  may or may not have arrived."
- **No assumptions, no advice.** Do not guess a cause or tell the
  caller to check something. A fix is named only when preflight
  established it: the app is missing, the grant is not held, no watch
  is connected.
- **Only distinctions the caller can act on.** Two outcomes with the
  same next step are one outcome. Maintainer detail goes to logcat.

`isError` is the MCP result flag for a failed tool call. Set it only
when the user has to do something, and name the fix. Prefix those
messages `FAILED:`, plain prose otherwise; the prefix keeps the text
self-describing in a client that does not show the flag. Keep strings
short, fixed, and safe to read aloud: no exception text, package names,
or anything interpolated from the system. The shipped tool list is the
guide for tone and level of detail.

## Validation: what a specific tool checks before it answers

The generic tools execute what they are told and report what Android
said. The rules below are for the phone-itself and Pebble tools, which
wrap one capability each and can check its preconditions up front.

**The caller is not expected to verify by default.** Whatever checking is
worth doing, the tool does before it answers. "Call the status tool to see
whether it worked" is not an acceptable response: it encourages unnecessary
validation, doubling the work for the caller and adding noise to the
conversation with the end user. The default tool usage is fire-and-forget, and
in most cases a failure is obvious to the end user. In exceptional cases, where
failure is unacceptable and verification is possible, a caller may choose to
verify afterwards.

**Preflight everything, always.** Android answers the questions that matter
instantly and for free — is the app installed (`getPackageInfo`, with the
package listed in `<queries>`), will an intent resolve (`resolveActivity`), do
we hold the grant (`Settings.canDrawOverlays`, `checkSelfPermission`), is the
watch connected (CoreApp's state provider). That covers the whole class of
failure the user can actually do something about. `Preflight` holds these, one
function per subsystem, each returning either null or the sentence to show;
every handler starts with one. Nothing there may block or wait.

**Preflight our side of the boundary, not theirs.** Everything checked is one
of four things: a grant *we* hold, whether the target app exists, device or
system state, or something the target app says about itself when asked. Never
another app's permissions. A permission missing there surfaces whenever that app
happens to need it — which can be long after launch — so it is not a
precondition in the first place; and the alternative is re-deriving every grant
every automated app needs, in this codebase, kept in sync by hand. Apps we drive
are assumed to have been installed, configured and run once by their owner.

**Confirm only where the platform offers a read-back, and only where silent
failure is expensive.** The watch acknowledges an AppMessage, so
`pebblekit_launch_and_send` waits for the ACK. A clock app exposes nothing at
all, so a timer set through `send_intent` is reported done once the intent is
away — and that is the end of it, not a hedge. Judge by what a silent failure
costs: media that does not play is obvious within seconds and needs no
confirmation; a message the watchapp never received is invisible until it
matters, which is why that one waits.

**Budget the wait against the failure, not the success.** A confirmation loop
returns the moment it sees what it wants, so the cost is only paid when
something is wrong. Ten seconds is the ceiling. Prefer a few.

**Retry only what retrying fixes.** Flaky Bluetooth and a cold ContentProvider,
yes. A refusal, no. Never retry something that might already have succeeded —
that is how the user ends up with two timers.

**Status tools answer the user's questions, not the tools'.** A "what's
playing" tool exists because that is a thing people ask. No status tool exists
to prop up an action tool.

## Skills

`skills/` is current behaviour only, stated succinctly and rewritten in
place when the device says otherwise. No changelog narrative, no discovery
story — git keeps the history. The layout, the front matter and the
authoring rules are in `skills/AGENTS.md`; read it before adding or
editing a skill file.

