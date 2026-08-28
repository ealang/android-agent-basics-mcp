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

**Never report success for something unverified.** Where the outcome can be
observed, observe it before claiming it, and where it cannot, say so plainly —
the UNCERTAIN wording exists so the agent asks the user instead of assuming.
A tool that lies about success is worse than one that fails loudly.

**Launching another app is fire-and-forget.** The standard pattern for driving
companion apps is a launch intent from our background service — we hold the one
`SYSTEM_ALERT_WINDOW` grant, the companion holds nothing — and there is no
result channel, so none should be invented. A launch tool reports what it
requested, not what happened; that satisfies the rule above by claiming
nothing. Where confirmation matters, it is a separate status read, not a wait
loop inside the tool: a failed hands-free launch is obvious to the user, and a
capable agent checks status afterwards. `docs/companion-apps.md` is the full
pattern, including the companion app's side.

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
