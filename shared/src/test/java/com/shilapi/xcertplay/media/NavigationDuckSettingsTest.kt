package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDuckSettingsTest {
    @Test fun defaultDbIsAboutTwelveBelowFullScale() {
        assertEquals(-12, NavigationDuckSettings.DEFAULT_DB)
        assertEquals(0.2512f, NavigationDuckSettings.linear(NavigationDuckSettings.DEFAULT_DB), 0.001f)
        assertEquals(0.5012f, NavigationDuckSettings.linear(-6), 0.001f)
        // 0 dB is outside the offered range; the clamp keeps the deepest supported -3 dB.
        assertEquals(0.7079f, NavigationDuckSettings.linear(0), 0.001f)
    }

    @Test fun aShallowerRequestIsAcceptedAndExtremesAreClamped() {
        // "I do not want it this quiet" is a smaller dB number: -6 dB keeps half the amplitude.
        assertEquals(-6, NavigationDuckSettings.clampDb(-6))
        assertEquals(-3, NavigationDuckSettings.clampDb(0))
        assertEquals(-30, NavigationDuckSettings.clampDb(-80))
    }

    @Test fun rampAndHoldAreClampedAndNeverSilenceMusic() {
        assertEquals(2_000, NavigationDuckSettings.clampRampMs(9_000))
        assertEquals(100, NavigationDuckSettings.clampRampMs(0))
        assertEquals(300, NavigationDuckSettings.DEFAULT_DUCK_MS)
        assertEquals(900, NavigationDuckSettings.DEFAULT_RESTORE_MS)
        assertEquals(100, NavigationDuckSettings.clampDuckMs(0))
        assertEquals(2_000, NavigationDuckSettings.clampRestoreMs(9_000))
        assertEquals(3_000, NavigationDuckSettings.clampHoldMs(9_000))
        assertEquals(300, NavigationDuckSettings.clampHoldMs(0))
        assertTrue("Even the deepest setting stays audible", NavigationDuckSettings.linear(NavigationDuckSettings.MIN_DB) > 0f)
    }

    @Test fun everyPresetIsAlreadyInRange() {
        assertEquals(NavigationDuckSettings.RAMP_PRESETS.sorted(), NavigationDuckSettings.RAMP_PRESETS)
        assertEquals(NavigationDuckSettings.HOLD_PRESETS.sorted(), NavigationDuckSettings.HOLD_PRESETS)
        for (ms in NavigationDuckSettings.RAMP_PRESETS) {
            assertEquals(ms, NavigationDuckSettings.clampRampMs(ms))
        }
        for (ms in NavigationDuckSettings.HOLD_PRESETS) {
            assertEquals(ms, NavigationDuckSettings.clampHoldMs(ms))
        }
    }
}
