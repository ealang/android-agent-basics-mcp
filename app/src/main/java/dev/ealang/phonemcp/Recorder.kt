package dev.ealang.phonemcp

import android.content.Intent
import android.content.Context
import android.util.Log

/**
 * Drives android-voice-recorder — the user's own dictation appliance — over the
 * two-component surface described in its `docs/AUTOMATION.md`.
 *
 * Neither call takes a parameter. The recorder picks its own input, preferring
 * the Bluetooth mic when the car is connected, and writes to the path set in
 * its own settings; there is nothing here for a caller to decide.
 *
 * Both directions are fire-and-forget, and there is no status to read. The
 * recorder deliberately publishes none: "is it still recording" is not a
 * question people ask an appliance, and the ongoing notification answers it for
 * anyone who does. What that costs is stated plainly rather than papered over —
 * see [start].
 */
object Recorder {
    private const val TAG = "phone-mcp"

    const val PACKAGE = "dev.ealang.voicerecorder"

    /** The recorder's exported components. Both take no extras. */
    private const val START_ACTIVITY = "$PACKAGE.AutomationStartActivity"
    private const val ACTION_STOP = "$PACKAGE.action.AUTOMATION_STOP"

    sealed interface Result {
        /** The request was handed over. */
        data object Accepted : Result
        /** Not installed, or the component would not start. */
        data object NotAvailable : Result
    }

    /**
     * Begin recording.
     *
     * Starting is an activity launch, not a broadcast, and that is the
     * recorder's requirement rather than a choice made here: the microphone
     * foreground-service type is granted only to an app Android considers in
     * use, and an app woken by a broadcast is not. Launching its trampoline is
     * what makes it briefly foreground. We can do that because we hold
     * `SYSTEM_ALERT_WINDOW` and the recorder needs no overlay grant of its own.
     *
     * `NEW_TASK` only — never `CLEAR_TASK`. The recorder's trampoline finishes
     * itself, so there is no stale activity to route around, and clearing the
     * task would take away whatever the user had on screen for nothing.
     *
     * An activity launch has no return path, so success here means the launch
     * was accepted, not that audio is being captured. A start that fails inside
     * the recorder is currently silent — a gap it already has with its own
     * widget, recorded in its `docs/AUTOMATION.md` §5, and not one this can
     * close from the outside.
     */
    fun start(context: Context): Result = try {
        context.startActivity(
            Intent()
                .setClassName(PACKAGE, START_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(TAG, "Voice recorder: launched the start trampoline")
        Result.Accepted
    } catch (e: Exception) {
        Log.w(TAG, "Voice recorder: could not launch $START_ACTIVITY", e)
        Result.NotAvailable
    }

    /**
     * Stop, finalize and save.
     *
     * A broadcast, because stopping needs neither the microphone nor the
     * screen: it leaves the keyguard and whatever the user is looking at alone,
     * which is the point when the recording was started from the car.
     *
     * Idempotent on their side — the recorder no-ops a stop when it is not
     * recording — so a caller that has lost track of the state cannot break
     * anything by asking twice.
     */
    fun stop(context: Context): Result = try {
        context.sendBroadcast(Intent(ACTION_STOP).setPackage(PACKAGE))
        Log.i(TAG, "Voice recorder: broadcast stop")
        Result.Accepted
    } catch (e: Exception) {
        Log.w(TAG, "Voice recorder: could not broadcast $ACTION_STOP", e)
        Result.NotAvailable
    }
}
