package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.util.Log

/**
 * Watches other apps' playback so CarPlay music can step aside while the head unit's own voice
 * assistant speaks.
 *
 * On this head unit the assistant does not merely duck us: its audio policy moves the primary
 * output to the DSP's VR source, and that source has its own much louder fixed level. Music that
 * keeps playing is then heard at voice level, and the media volume keys stop addressing it.
 * Android reports the assistant's transient focus for only part of the answer, so the focus
 * callbacks cannot bound the loud window; the assistant's own playback can.
 *
 * [AudioPlaybackConfiguration]'s liveness and owner are hidden framework APIs, so they are read
 * reflectively. This head unit runs Android 8.1, where that is unrestricted; when the members are
 * unavailable the monitor simply never reports speech and nothing else changes.
 */
internal class AssistantPlaybackMonitor(
    context: Context,
    private val onChange: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val ownUid = context.applicationInfo.uid
    private val assistantUids: Set<Int> =
        listOf("com.baidu.che.codriver", "com.baidu.carlife", "com.baidu.car.codriver")
            .mapNotNull { name ->
                runCatching { context.packageManager.getPackageUid(name, 0) }.getOrNull()
            }
            .toSet()
    private var active = false
    private var registered = false

    private val isActiveMethod = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("isActive")
    }.getOrNull()
    private val clientUidMethod = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
    }.getOrNull()

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                evaluate(configs)
            }
        }
    } else null

    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "playback callbacks unavailable on API ${Build.VERSION.SDK_INT}")
            return
        }
        val current = callback ?: return
        if (registered) return
        registered = runCatching { manager?.registerAudioPlaybackCallback(current, null); true }
            .getOrDefault(false)
        if (!registered) return
        // A callback only fires on change; adopt whatever is already playing.
        runCatching { evaluate(manager?.activePlaybackConfigurations.orEmpty()) }
    }

    fun detach() {
        if (!registered) return
        registered = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                callback?.let { manager?.unregisterAudioPlaybackCallback(it) }
            }
        }
        active = false
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val speaking = configs.any { config ->
            val uid = runCatching { clientUidMethod?.invoke(config) as? Int }.getOrNull()
            val playing = runCatching { isActiveMethod?.invoke(config) as? Boolean }.getOrNull()
            val ours = uid == ownUid
            if (playing != true || ours) return@any false
            // UID and PID are hidden from a normal app on this build (both come back as -1), so
            // the usage the assistant declares is what identifies its speech reliably. CarPlay
            // music is USAGE_MEDIA (1); the head unit's assistant speaks as USAGE_ASSISTANT (16)
            // and its navigation prompts as USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.
            val usage = config.audioAttributes?.usage
            uid in assistantUids || usage == AUDIO_USAGE_ASSISTANT
        }
        if (speaking == active) return
        active = speaking
        Log.i(TAG, "car assistant speaking=$speaking")
        onChange(speaking)
    }

    private companion object {
        const val TAG = "DiPlay-AssistMon"
        const val AUDIO_USAGE_ASSISTANT = 16
    }
}
