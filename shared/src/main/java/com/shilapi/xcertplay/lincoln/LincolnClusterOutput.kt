package com.shilapi.xcertplay.lincoln

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Forwards CarPlay guidance and now-playing to a Lincoln/Ford instrument-cluster app that listens
 * for plain broadcasts (see [LincolnClusterProtocol]).
 *
 * This follows [CarPlayGlance], the same snapshot the home-screen widget uses, so it does not read
 * the widget or depend on resource ids the way the standalone bridge did. It is off by default and
 * only broadcasts when [LincolnClusterSettings] has it enabled and a connection is up.
 */
object LincolnClusterOutput {
    private const val TAG = "DiPlay-Lincoln-Cluster"

    @Volatile private var context: Context? = null
    @Volatile private var listener: ((CarPlayGlance.Snapshot) -> Unit)? = null

    // Guards the "owned" flags so a stale snapshot cannot clear state another write just set.
    private val lock = Any()
    private var navOwned = false
    private var musicOwned = false
    // What we last told the cluster, so a missing route actively clears a stale "arrived" card
    // left by an earlier session instead of only stopping new writes.
    private var navStateOn: Boolean? = null
    private var lastNavKey: String? = null
    private var lastMusicKey: String? = null
    private var lastRemainKey: String? = null
    private var remainOwned = false
    private var navSent = 0
    private var musicSent = 0
    private var remainSent = 0
    private var lastReason = "init"
    // The last state acted on, so the 1 s tick is cheap: an unchanged glance with unchanged
    // switches is skipped, while a real change (a route expiring, or a switch flipped without a
    // new frame) still runs the forwarding and clearing below.
    private var lastSnapshot: CarPlayGlance.Snapshot? = null
    private var lastNavEnabled: Boolean? = null
    private var lastMusicEnabled: Boolean? = null

    // Guidance has expiry deadlines (a stale route retires ~30 s after the last frame, and an
    // empty maneuver list hides after 3 s). Those only advance when snapshot() is called, and a
    // head unit without the home-screen widget placed never calls it, so a finished route's last
    // instruction — Apple Maps' "arrived" card — stayed on the cluster until the next connection.
    // Tick the glance ourselves so the same expiry the widget relies on also clears the cluster.
    private const val TICK_MILLIS = 1_000L
    @Volatile private var ticker: HandlerThread? = null
    @Volatile private var tickHandler: Handler? = null
    private val tick = object : Runnable {
        override fun run() {
            if (context == null) return
            onSnapshot(CarPlayGlance.snapshot())
            tickHandler?.postDelayed(this, TICK_MILLIS)
        }
    }

    /** Called once from the app; attaches to the glance stream and forwards from then on. */
    fun attach(appContext: Context) {
        val app = appContext.applicationContext
        context = app
        if (listener != null) return
        val next: (CarPlayGlance.Snapshot) -> Unit = { onSnapshot(it) }
        listener = next
        CarPlayGlance.addListener(next)
        startTicking()
        // A session may already be running when the setting is enabled.
        onSnapshot(CarPlayGlance.snapshot())
    }

    fun detach() {
        listener?.let { CarPlayGlance.removeListener(it) }
        listener = null
        context = null
        stopTicking()
        synchronized(lock) {
            navOwned = false
            musicOwned = false
            remainOwned = false
            navStateOn = null
            lastNavKey = null
            lastMusicKey = null
            lastRemainKey = null
            lastSnapshot = null
            lastNavEnabled = null
            lastMusicEnabled = null
        }
    }

    fun statusLine(): String = synchronized(lock) {
        "navSent=$navSent musicSent=$musicSent remainSent=$remainSent" +
            " navOwned=$navOwned musicOwned=$musicOwned remainOwned=$remainOwned"
    }

    private fun startTicking() {
        if (tickHandler != null) return
        val thread = HandlerThread("diplay-lincoln-cluster").apply { start() }
        ticker = thread
        val handler = Handler(thread.looper)
        tickHandler = handler
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun stopTicking() {
        tickHandler?.removeCallbacksAndMessages(null)
        tickHandler = null
        ticker?.quitSafely()
        ticker = null
    }

    /** A short reason the output is currently idle, for the settings screen. */
    fun reason(snapshot: CarPlayGlance.Snapshot): String = when {
        !snapshot.connected -> "DiPlay 未连接 CarPlay"
        snapshot.maneuverType == null && snapshot.song == null -> "CarPlay 已连接，暂无导航或歌曲信息"
        else -> "就绪"
    }

    private fun onSnapshot(snapshot: CarPlayGlance.Snapshot) {
        val app = context ?: return
        val navEnabled = LincolnClusterSettings.navigation(app)
        val musicEnabled = LincolnClusterSettings.music(app)
        if (snapshot == lastSnapshot && navEnabled == lastNavEnabled && musicEnabled == lastMusicEnabled) return
        lastSnapshot = snapshot
        lastNavEnabled = navEnabled
        lastMusicEnabled = musicEnabled
        val turn = LincolnClusterProtocol.turnCode(snapshot.maneuverType, snapshot.drivingSide)
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        lastReason = when {
            !navEnabled && !musicEnabled -> "switches off"
            !snapshot.connected -> "not connected"
            snapshot.maneuverType == null -> "connected, no route"
            turn == null -> "unknown maneuver ${snapshot.maneuverType}"
            distance == null -> "distance out of range ${snapshot.distanceMeters}"
            else -> "guidance $turn ${distance}m '${snapshot.road}'"
        }
        Log.i(TAG, "glance connected=${snapshot.connected} maneuver=${snapshot.maneuverType} " +
            "distance=${snapshot.distanceMeters} road='${snapshot.road}' song='${snapshot.song}' " +
            "playing=${snapshot.playing} remain=${snapshot.remainingMeters}/${snapshot.remainingSeconds} " +
            "nav=$navEnabled music=$musicEnabled -> $lastReason")
        if (!navEnabled && !musicEnabled) {
            synchronized(lock) {
                clearNavLocked(app)
                clearRemainLocked(app)
                clearMusicLocked(app)
            }
            return
        }
        synchronized(lock) {
            if (navEnabled && snapshot.connected) {
                sendNavLocked(app, snapshot)
                sendRemainLocked(app, snapshot)
            } else {
                clearNavLocked(app)
                clearRemainLocked(app)
            }
            if (musicEnabled && snapshot.connected) sendMusicLocked(app, snapshot) else clearMusicLocked(app)
        }
    }

    private fun sendNavLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        val turn = LincolnClusterProtocol.turnCode(snapshot.maneuverType, snapshot.drivingSide)
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        if (snapshot.maneuverType == null || turn == null || distance == null) {
            clearNavLocked(app)
            return
        }
        val key = "$turn|$distance|${snapshot.road}|${snapshot.remainingMeters}|${snapshot.remainingSeconds}"
        if (navStateOn != true) {
            if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
                    .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_ON))) {
                navOwned = true
                navStateOn = true
            }
        }
        if (navOwned && key != lastNavKey) {
            val info = Intent(LincolnClusterProtocol.ACTION_NAV_INFO)
                .putExtra(LincolnClusterProtocol.EXTRA_NAME, turn)
                .putExtra(LincolnClusterProtocol.EXTRA_DISTANCE, distance)
                .putExtra(LincolnClusterProtocol.EXTRA_ROAD, LincolnClusterProtocol.sanitizeText(snapshot.road).orEmpty())
            if (broadcast(app, info)) {
                lastNavKey = key
                navSent++
                Log.i(TAG, "nav INFO $turn ${distance}m '${snapshot.road}' (#$navSent)")
            }
        }
    }

    /**
     * Total remaining distance/time on the cluster's own REMAIN channel. The cluster takes
     * `remainDistance` in metres and `remainTime` in seconds and formats both itself.
     */
    private fun sendRemainLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        if (!LincolnClusterSettings.remaining(app)) {
            clearRemainLocked(app)
            return
        }
        val meters = snapshot.remainingMeters?.toInt()?.takeIf { it in 0..LincolnClusterProtocol.MAX_REMAIN_DISTANCE_METERS }
        val seconds = snapshot.remainingSeconds?.toInt()?.takeIf { it >= 0 }
        if (meters == null && seconds == null) {
            clearRemainLocked(app)
            return
        }
        val key = "$meters|$seconds"
        if (key == lastRemainKey) return
        val intent = Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
        meters?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, it) }
        seconds?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, it) }
        if (broadcast(app, intent)) {
            lastRemainKey = key
            remainOwned = true
            remainSent++
            Log.i(TAG, "remain ${meters}m ${seconds}s (#$remainSent)")
        }
    }

    private fun sendMusicLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        // The cluster draws one text row. Music apps put the current lyric in the iAP2 title field
        // and the song name in the artist field, so send the raw title alone: the widget's combined
        // "title — artist" would otherwise carry the song name along on the lyric line.
        val title = LincolnClusterProtocol.sanitizeText(snapshot.songTitle ?: snapshot.song)
        if (title == null) {
            clearMusicLocked(app)
            return
        }
        val key = "$title|${snapshot.playing}"
        if (key == lastMusicKey) return
        if (sendMusic(app, title, snapshot.playing)) {
            lastMusicKey = key
            musicOwned = true
            musicSent++
            Log.i(TAG, "music '$title' playing=${snapshot.playing} (#$musicSent)")
        }
    }

    private fun sendMusic(app: Context, title: String, playing: Boolean): Boolean =
        broadcast(app, Intent(LincolnClusterProtocol.ACTION_MEDIA_INFO)
            .putExtra("id", "diplay-widget")
            .putExtra(LincolnClusterProtocol.EXTRA_TITLE, title)
            .putExtra(LincolnClusterProtocol.EXTRA_ARTIST, "")
            .putExtra(LincolnClusterProtocol.EXTRA_ALBUM, "")
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_NAME, LincolnClusterProtocol.SOURCE_NAME)
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_TYPE, LincolnClusterProtocol.SOURCE_TYPE)
            .putExtra(LincolnClusterProtocol.EXTRA_PLAYING, playing))

    private fun clearNavLocked(app: Context) {
        // Send OFF whenever the cluster was told ON, even if a later snapshot already dropped the
        // owned flag, so a finished route cannot leave "arrived" on the cluster.
        if (navStateOn != true) return
        if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
                .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_OFF))) {
            navOwned = false
            navStateOn = false
            lastNavKey = null
            Log.i(TAG, "nav cleared")
        }
    }

    private fun clearRemainLocked(app: Context) {
        if (!remainOwned) return
        // Distance 0 and time 0 clear the readout; the cluster keeps the last value otherwise.
        if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, 0)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, 0))) {
            remainOwned = false
            lastRemainKey = null
            Log.i(TAG, "remain cleared")
        }
    }

    private fun clearMusicLocked(app: Context) {
        if (!musicOwned) return
        if (sendMusic(app, "", false)) {
            musicOwned = false
            lastMusicKey = null
            Log.i(TAG, "music cleared")
        }
    }

    private fun broadcast(app: Context, intent: Intent): Boolean = runCatching {
        intent.setPackage(LincolnClusterSettings.clusterPackage(app))
        app.sendBroadcast(intent)
        true
    }.getOrElse {
        Log.w(TAG, "broadcast failed ${it.javaClass.simpleName}")
        false
    }
}
