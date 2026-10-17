package com.shilapi.xcertplay.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Ford/Lincoln head unit's "audio off" key.
 *
 * The key does not mute a stream in the usual way: the car's WindowManager takes audio focus and
 * broadcasts `android.intent.action.audiooff` with `audio_off=true`, then mutes STREAM_MUSIC.
 * This head unit's HAL reports `set_volume: Function not implemented`, so that mute never reaches
 * DiPlay's AudioTrack and CarPlay keeps playing. Watching the broadcast lets the renderer silence
 * its own PCM, which is the only path that works here.
 *
 * Only media and guidance are silenced; a telephone call stays audible, matching what the car
 * itself does (calls play on STREAM_VOICE_CALL, which audio-off does not touch).
 */
object CarAudioOff {
    private const val TAG = "DiPlay-AudioOff"
    const val ACTION = "android.intent.action.audiooff"
    private const val EXTRA = "audio_off"

    @Volatile
    var muted: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    /** Starts watching the car's key. Repeated calls are ignored. */
    @Synchronized
    fun attach(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        appContext = app
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                val off = intent.getBooleanExtra(EXTRA, false)
                Log.i(TAG, "car audio off=$off")
                muted = off
                listeners.forEach { listener -> runCatching { listener(off) } }
            }
        }
        val filter = IntentFilter(ACTION)
        // The car sends it from system_server. Android 13+ requires an explicit flag for runtime
        // receivers; the sender is the platform, so an exported registration is the one that
        // receives it. The action only carries a boolean, so this exposes nothing.
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(next, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(next, filter)
            }
        }
        registered
            .onSuccess { receiver = next }
            .onFailure { Log.w(TAG, "could not watch the car's audio-off key", it) }
        // The key may already have been pressed while DiPlay was not running; the car keeps
        // STREAM_MUSIC muted, so start from that rather than from "audible".
        val alreadyMuted = runCatching {
            app.getSystemService(AudioManager::class.java)?.isStreamMute(AudioManager.STREAM_MUSIC)
        }.getOrNull() == true
        if (alreadyMuted) {
            Log.i(TAG, "media stream already muted when audio-off attached")
            muted = true
            listeners.forEach { listener -> runCatching { listener(true) } }
        }
    }

    @Synchronized
    fun detach() {
        val current = receiver ?: return
        receiver = null
        runCatching { appContext?.unregisterReceiver(current) }
        appContext = null
        muted = false
    }

    /** Called with true when the car muted its audio and false when it came back. */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
