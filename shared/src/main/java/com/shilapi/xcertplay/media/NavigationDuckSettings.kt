package com.shilapi.xcertplay.media

import kotlin.math.pow

/**
 * Tuning for lowering music while CarPlay navigation guidance speaks. The defaults match the
 * levels that felt natural in the car; the sliders let a driver ask for a gentler or a longer
 * change without a code change.
 */
object NavigationDuckSettings {
    /** Never quieter than -30 dB, never so shallow that the prompt is masked. */
    const val MIN_DB = -30
    const val MAX_DB = -3
    const val DEFAULT_DB = -12

    const val MIN_RAMP_MS = 100
    const val MAX_RAMP_MS = 2_000
    /** How long music takes to sink to the lowered level when a prompt starts. */
    const val DEFAULT_DUCK_MS = 300
    /** How long music takes to come back up when the prompt ends; slower reads as smoother. */
    const val DEFAULT_RESTORE_MS = 900

    /**
     * How long the ducked level survives a gap between sentences. Shorter releases sooner on
     * music-only stretches; longer hides the pumps between words.
     */
    const val MIN_HOLD_MS = 300
    const val MAX_HOLD_MS = 3_000
    const val DEFAULT_HOLD_MS = 900

    /** The values the sliders offer; every session accepts any value in range. */
    val RAMP_PRESETS = listOf(100, 150, 200, 250, 300, 400, 500, 700, 900, 1_200, 1_500, 2_000)
    val HOLD_PRESETS = listOf(300, 500, 700, 900, 1_200, 1_500, 2_000, 2_500, 3_000)

    fun clampDb(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)

    fun clampRampMs(ms: Int): Int = ms.coerceIn(MIN_RAMP_MS, MAX_RAMP_MS)

    fun clampDuckMs(ms: Int): Int = clampRampMs(ms)

    fun clampRestoreMs(ms: Int): Int = clampRampMs(ms)

    fun clampHoldMs(ms: Int): Int = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)

    /** Amplitude for a dB target; -12 dB is about 0.25, -6 dB about 0.5. */
    fun linear(db: Int): Float = 10.0.pow(clampDb(db) / 20.0).toFloat()
}
