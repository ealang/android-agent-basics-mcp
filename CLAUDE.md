# Working on phone-mcp

## The calling agent is a small model

Assume the model on the other end of these tools is a weak one. It may be a
local or cheap model with little room for reasoning, and it will be reading tool
descriptions and result strings without much capacity to interpret them.

So the interface stays as simple as it can be, and everything the app can work
out for itself, it works out for itself. Concretely:

**Try everything internally rather than exposing the choice.** If a command has
several possible routes, the app tries them and reports the outcome. The caller
is never asked to pick a strategy, retry, or reason about device state like
whether the phone is locked. Prefer one code path that works everywhere over
branching the caller has to understand, even at some cost elsewhere —
`Podcast.play` clears Pocket Casts' activity stack in every case rather than
only when it must, because one proven path beats two conditional ones.

**Don't surface a distinction the caller cannot act on.** If two outcomes lead
to the same next step, they are one outcome. Real diagnostic detail that matters
to a human maintainer goes to logcat, not into the response. `play_podcast`
answers UNCERTAIN whether or not a media session ever appeared, because the
caller would do the same thing either way; which happened is in the log.

**Don't report an ordinary state as an error.** `isError = true` means the user
has to do something. An idle media player answering "what's playing?" is a
normal answer — returning it as a failure makes the agent apologise for a
non-problem. Reserve errors for genuine faults, and name the exact fix in the
message (which app is missing, which permission to grant, in which Settings
screen).

**Keep descriptions to what changes a decision.** No durations, no side effects
the caller cannot use, no explanation of mechanism. State what the tool does and
what it cannot do.

## Validation: what a tool owes the caller

A tool either does the job or says why it could not. It never hands back a
maybe, and it never asks the caller to go and check.

**The caller never verifies.** Whatever checking is worth doing, the tool does
before it answers. "It may or may not have worked, call the status tool" is the
one shape that is banned outright: it doubles the work for a model that is
already the weakest part of the system, and in practice it just reports success
anyway. If a tool did not fail, the agent is entitled to tell the user it is
done.

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
`set_watch_timer` waits for it. The clock app exposes nothing at all, so
`set_timer` reports success once the intent is away — and that is the end of
it, not a hedge. Judge by what a silent failure costs: a podcast that does not
play is obvious within seconds and needs no confirmation; a timer that never
got set is invisible until it matters, which is why the watch one waits.

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
carries the distinction; the prefix is there because a weak model skims. There
is no CONFIRMED and no UNCERTAIN — "we did it" and "we did it and watched it
happen" are the same thing to the caller, and a tool that is unsure should be
fixed rather than labelled.

**Status tools answer the user's questions, not the tools'.** `voice_status`
and `podcast_now_playing` exist because "who am I talking to" and "what's
playing" are things people ask. None of them exists to prop up an action tool.

**Launching another app is still fire-and-forget underneath.** We hold the one
`SYSTEM_ALERT_WINDOW` grant and the companion holds nothing, so a launch intent
has no return path and none can be invented. What changed is what we do about
it: preflight the launch so the failures worth naming are named, and where the
companion can offer a read-back, ask it for one. `docs/companion-apps.md` is
the full pattern, including the companion app's side.

## Docs

`docs/` holds the empirical findings behind the awkward parts — how each
integration was arrived at, including the approaches that failed and the
conclusions later proved wrong. When behaviour changes on the device, update
the relevant doc alongside the code; the wrong turns are kept on purpose,
because they are what stops the same dead ends being retried.

`docs/companion-apps.md` is written for agents working on the *other* side —
a custom app that phone-mcp should be able to drive. Point them at it before
they design an automation interface, and fold anything new we learn back into
it.
