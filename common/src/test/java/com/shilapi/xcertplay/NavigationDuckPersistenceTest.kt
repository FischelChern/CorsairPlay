package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.NavigationDuckSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NavigationDuckPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test fun freshInstallUsesTheShorterDuckAndLongerRestore() {
        assertEquals(300, AirPlayPersistence.loadNavigationDuckDownMs(context))
        assertEquals(900, AirPlayPersistence.loadNavigationDuckUpMs(context))
    }

    @Test fun eachDirectionPersistsIndependently() {
        AirPlayPersistence.saveNavigationDuckDownMs(context, 200)
        AirPlayPersistence.saveNavigationDuckUpMs(context, 1_500)
        assertEquals(200, AirPlayPersistence.loadNavigationDuckDownMs(context))
        assertEquals(1_500, AirPlayPersistence.loadNavigationDuckUpMs(context))
    }

    @Test fun oneSharedFadeFromAnOlderBuildKeepsApplyingToBothDirections() {
        prefs.edit().putInt("navigation_duck_ramp_ms", 500).commit()
        assertEquals(500, AirPlayPersistence.loadNavigationDuckDownMs(context))
        assertEquals(500, AirPlayPersistence.loadNavigationDuckUpMs(context))
    }

    @Test fun outOfRangeValuesAreClamped() {
        prefs.edit().putInt("navigation_duck_down_ms", 0)
            .putInt("navigation_duck_up_ms", 99_999).commit()
        assertEquals(NavigationDuckSettings.MIN_RAMP_MS, AirPlayPersistence.loadNavigationDuckDownMs(context))
        assertEquals(NavigationDuckSettings.MAX_RAMP_MS, AirPlayPersistence.loadNavigationDuckUpMs(context))
    }
}
