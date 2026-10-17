package com.shilapi.xcertplay.media

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Asks the head unit's own voice assistant to stop recording.
 *
 * This firmware allows exactly one active capture input, and the assistant keeps a hotword
 * (voice-recognition) input open for the whole drive. A CarPlay microphone request that arrives
 * while it is open fails with AudioRecord.START_ERROR and reads silence, which is why WeChat voice
 * notes recorded nothing until Siri was opened once — that is what forced the leaked input closed.
 *
 * The assistant also leaks that input when its wake-word setting is turned off, so waiting for it
 * is not enough; its own end-of-recording command releases it. Sending it when nothing is
 * recording is harmless: the receiver simply has no session to end.
 */
internal object CarVoiceInput {
    private const val TAG = "DiPlay-VoiceInput"
    private const val PACKAGE = "com.baidu.che.codriver"
    private const val RECEIVER = "$PACKAGE.receiver.VrControlReceiver"
    private const val ACTION_END_RECORD = "com.baidu.duerosauto.action.END_RECORD"
    private const val ACTION_VOICE_INPUT_STOP = "com.baidu.duerosauto.voice_input.stop"
    private const val VOICE_INPUT_RECEIVER = "$PACKAGE.receiver.VoiceInputReceiver"

    /**
     * Ends any assistant recording so the capture input is free for CarPlay. The assistant is
     * addressed by explicit component; a missing component is reported and ignored.
     */
    fun release(context: Context) {
        send(context, ACTION_END_RECORD, RECEIVER)
        send(context, ACTION_VOICE_INPUT_STOP, VOICE_INPUT_RECEIVER)
    }

    private fun send(context: Context, action: String, component: String) {
        val intent = Intent(action).setClassName(PACKAGE, component)
        val delivered = runCatching {
            context.sendBroadcast(intent)
            true
        }.getOrDefault(false)
        Log.i(TAG, "voice input release action=$action delivered=$delivered")
    }
}
