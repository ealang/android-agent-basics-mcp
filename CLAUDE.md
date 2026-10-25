# Working on phone-mcp

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

Two kinds of first-class tools survive beside them, and they are the
only kinds that should ever be added:

- **Phone-itself tools** — `get_location`, the `find_my_phone_*`
  pair, the notification pair. Capabilities of the phone
  with no generic route. Not app integrations at all.
- **Pebble platform tools** — the watch is a platform (there is no
  generic Android route to a BLE peripheral), so watch operations are
  first-class the way phone operations are: generic over *watchapps*,
  specific to none.

An integration that needs a permission gets a manifest line and
grant-guiding UI, not a tool.
If a proposed tool names a third-party app, the answer is a skill file.

## Responses: only what the caller can act on

**Don't surface a distinction the caller cannot act on.** If two outcomes lead
to the same next step, they are one outcome. Diagnostic detail that matters to
a maintainer goes to logcat, not into the response.

**Don't report an ordinary state as an error.** `isError` is the MCP result
flag for a failed tool call. Set it only when the user has to do something,
and name the exact fix in the message (which app is missing, which permission
to grant, in which Settings screen). The shipped tool list is the guide: match
its tone and level of detail.

## Validation: what a first-class tool owes the caller

This section is the doctrine of the first-class tools. The generic
tools deliberately do almost none of it — they execute what they are
told and report what Android said.

A tool either does the job or says why it could not. It never hands back a
maybe, and it never asks the caller to go and check.

**The caller is not expected to verify by default.** Whatever checking is
worth doing, the tool does before it answers. "It may or may not have worked,
call the status tool" is not an acceptable response: it encourages unnecessary
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
failure is expensive.** The watch acknowledges an AppMessage, so the watchapp
handshake waits for it. The clock app exposes nothing at all, so a timer set by
intent is reported done once the intent is away — and that is the end of it,
not a hedge. Judge by what a silent failure costs: media that does not play is
obvious within seconds and needs no confirmation; a timer that never got set is
invisible until it matters, which is why the watch one waits.

**Budget the wait against the failure, not the success.** A confirmation loop
returns the moment it sees what it wants, so the cost is only paid when
something is wrong. Ten seconds is the ceiling. Prefer a few.

**Retry only what retrying fixes.** Flaky Bluetooth and a cold ContentProvider,
yes. A refusal, no. Never retry something that might already have succeeded —
that is how the user ends up with two timers.

**Error strings are short, fixed, and safe to read aloud.** One or two
sentences naming the app and the Settings screen, written to be repeated to the
user. Never an exception message, a stack trace, a package name, or anything
interpolated from the system — not because a secret is expected, but so that no
one ever has to check. Diagnostic detail goes to logcat, where it belongs.

**Prefixes: `FAILED:` on errors, plain prose on success.** `isError` already
carries the distinction; the prefix keeps the text self-describing in a client
that does not show the flag. There
is no CONFIRMED and no UNCERTAIN — "we did it" and "we did it and watched it
happen" are the same thing to the caller, and a tool that is unsure should be
fixed rather than labelled.

**Status tools answer the user's questions, not the tools'.** A "what's
playing" tool exists because that is a thing people ask. No status tool exists
to prop up an action tool.

**Launching another app is still fire-and-forget underneath.** We hold the one
`SYSTEM_ALERT_WINDOW` grant and the companion holds nothing, so a launch intent
has no return path and none can be invented. What changed is what we do about
it: preflight the launch so the failures worth naming are named, and where the
companion can offer a read-back, ask it for one.

## Skills

`skills/` is current behaviour only, stated succinctly and rewritten in
place when the device says otherwise. No changelog narrative, no discovery
story — git keeps the history. The authoring rules are in
`skills/README.md`; read them before adding or editing a skill file.

