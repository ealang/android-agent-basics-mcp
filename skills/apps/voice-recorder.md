# Voice Recorder (dev.ealang.voicerecorder)

A personal dictation appliance, built with an automation surface shaped
for this server (its repo documents it in `docs/AUTOMATION.md`).
Verified end to end through the generic tools 2026-10-01, screen off,
keyguard up.

Two operations, no parameters on either. The recorder picks its own
input (preferring the car's Bluetooth mic when connected) and saves to
the path in its own settings; there is nothing for a caller to decide.

## Start recording

An activity launch — deliberately, on the recorder's side: the
microphone foreground-service type is granted only to an app Android
considers in use, and the exported trampoline activity is what makes
the recorder briefly foreground. It shows itself over the keyguard, so
a locked phone is fine; the trampoline draws nothing and finishes
itself immediately.

```json
{
  "mode": "activity",
  "component": "dev.ealang.voicerecorder/dev.ealang.voicerecorder.AutomationStartActivity"
}
```

Needs the overlay grant, like any background activity send; the tool
preflights that. "Started activity" means the launch was accepted — a
start that then fails inside the recorder (mic refused, storage
missing) is reported by a notification on the phone, not back here.

## Stop, finalize, save

A broadcast — stopping needs neither the microphone nor the screen, so
it leaves the keyguard and whatever is on screen alone.

```json
{
  "mode": "broadcast",
  "action": "dev.ealang.voicerecorder.action.AUTOMATION_STOP",
  "package": "dev.ealang.voicerecorder"
}
```

"1 manifest receiver(s) matched" is the success signature; 0 matched
means the recorder is not installed. The stop is idempotent — the
recorder no-ops when not recording — so asking twice breaks nothing.

## Limits

- **No status read-back, by the recorder's design**: nobody asks an
  appliance whether it is still recording; the ongoing notification
  answers it on the phone. There is nothing to poll here.
- Recordings land under the output-path template in the recorder's
  settings.
