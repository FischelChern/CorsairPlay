package com.shilapi.xcertplay.lincoln

import android.content.Context
import android.os.Build

/**
 * Whether this head unit is a Lincoln/Ford one.
 *
 * Used to decide which vendor surfaces to show: the BYD cards and ADB vehicle data have no receiver
 * on a Lincoln/Ford unit, and the Lincoln cluster output is the one that matters there instead. The
 * answer is a property of the hardware, so a generic test device reports false and keeps the BYD
 * paths covered by the upstream suite.
 */
object LincolnVehicle {
    /** The Freescale MEK / i.MX8 boards the Lincoln/Ford cluster units are built on. */
    fun isHardware(): Boolean =
        (Build.HARDWARE.equals("freescale", ignoreCase = true) &&
            Build.BOARD.equals("MEK", ignoreCase = true)) ||
            Build.MODEL.equals("MEK-MX8Q", ignoreCase = true)

    /**
     * True on the Lincoln/Ford hardware, or where the cluster app that draws the dashboard card is
     * installed (so a unit whose board string differs still counts once that app is there).
     */
    fun eligible(context: Context): Boolean =
        isHardware() || installed(context, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
