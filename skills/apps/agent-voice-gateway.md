# Agent Voice Gateway

`com.agentvoicegateway.app` (fully verified on-device 2026-09-28,
including a live start/stop cycle). A
voice-conversation app. Unlike the media apps, it was *designed* to be
driven: it exports a ContentProvider for reads and commands, and a
launch activity for starting sessions. No media session, no
MediaBrowserService — do not approach it with the `media_*` tools.

Self-contained: no `intent_scan` needed. The generic tools used here
are `provider_query`, `provider_call`, and `send_intent`.

## Reads: two provider queries

Authority `com.agentvoicegateway.app.automation`, no permission
required. Both answer synchronously even from a dead process (the
provider waits up to 5 s for the app to initialise).

```
provider_query  uri: content://com.agentvoicegateway.app.automation/conversations
```

One row per conversation: `id`, `name`, `unread`, `active` (0/1).

```
provider_query  uri: content://com.agentvoicegateway.app.automation/status
```

One row: the active conversation (`active_conversation_id`,
`active_conversation_name`) and whether a session is running. That is
all the status says — the app keeps its internals to itself.

## Command: stop the session

```
provider_call  authority: com.agentvoicegateway.app.automation  method: stop
```

Returns a Bundle: `ok = true` plus `conversation_id`/`conversation_name`
on acceptance, `ok = false` plus `error` otherwise (`"No session is
active."` when there is nothing to stop). **`ok = true` means "heard",
not "done"** — the stop is asynchronous by design; read `/status`
afterwards to confirm. `stop` is the only method; anything else
returns `ok = false` with an error naming it.

## Starting a session: a launch intent, not the provider

```
send_intent
  mode: activity
  component: com.agentvoicegateway.app/com.agentvoicegateway.app.AutomationStartActivity
  extras: [{key: conversation, type: string, value: "<name, id, or unique substring>"}]
```

- `conversation` optional: absent starts the most recently played
  conversation.
- Optional `{key: mode, type: string, value: "since_last_sent"}` is
  the catch-up start: replay everything after the user's last sent
  message instead of just the unread backlog. Any other mode value is
  refused.
- **Fire-and-forget by design**: no match, an ambiguous match, or a
  bad mode produce nothing the caller can see — only a line in the
  app's own event log. Always read `/status` ~2–3 s after a start and
  check that the expected conversation is running.
- The activity is a trampoline (no visible screen); the app handles
  keyguard itself and auto-closes once the session is up.

## Verified / untested (2026-09-28)

Verified on the device: both queries; the full start/stop cycle —
start intent with a `conversation` extra brought the named session up
(confirmed via `/status` ~3 s later), a live `provider_call stop`
answered `ok = true` with the conversation id and name, and `/status`
confirmed the session down; catch-up mode (`since_last_sent`), which
replayed a message sent moments before the start; and the error
Bundle for an unknown method. The contracts come from the app's own
source (`AutomationProvider.kt`, `AutomationStartActivity.kt` in the
agent-voice-gateway repo), not guessed from a scan.

phone-mcp also has first-class tools for this app
(`start_voice_session`, `stop_voice_session`, `voice_status`) — this
file is the generic-route equivalent, written to prove the generic
tier can cover it.
