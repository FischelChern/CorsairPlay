package com.shilapi.xcertplay.lincoln

import android.content.Context

/**
 * Lincoln/Ford head-unit video path.
 *
 * The tested firmware (Freescale MEK / i.MX8, e.g. MEK-MX8Q on the Lincoln/Ford instrument-cluster
 * units) releases main-screen frames late when the decode worker drains the codec itself: a frame
 * waits for the worker to come back from its queue, which shows as jank and, on a busy screen, a
 * backlog the car then burns CPU to catch up. The verified Lincoln build offers this as **Fast
 * video**, and it is on by default there because those units play smoothly with a dedicated output
 * thread. This keeps the same behaviour here: on by default only on that hardware, and the driver
 * can turn it off if their firmware prefers the shared-thread path.
 */
object LincolnVehicleVideo {
    private const val PREFS = "diplay_vehicle_video"
    private const val KEY_INDEPENDENT_OUTPUT = "independent_output"

    /** True on the Lincoln/Ford Freescale head units this path is tuned for. */
    fun eligible(): Boolean = LincolnVehicle.isHardware()

    /** The dedicated video output thread; on by default on the eligible head units. */
    fun independentOutput(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INDEPENDENT_OUTPUT, true)

    fun setIndependentOutput(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_INDEPENDENT_OUTPUT, enabled).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
