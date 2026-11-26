package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ActivationSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() = ActivationSettings.clearAllForTest(context)

    @Test fun deviceIdIsStable() {
        val first = ActivationSettings.deviceId(context)
        assertEquals(first, ActivationSettings.deviceId(context))
        assertTrue(first.length >= 36)
    }

    @Test fun activationPersistsTokenAndExpiry() {
        assertFalse(ActivationSettings.isActivated(context))
        ActivationSettings.saveActivation(context, "device-token", "2027-01-01T00:00:00Z")
        assertTrue(ActivationSettings.isActivated(context))
        assertEquals("device-token", ActivationSettings.uploadToken(context))
        assertNotNull(ActivationSettings.expiresAt(context))
        ActivationSettings.clear(context)
        assertFalse(ActivationSettings.isActivated(context))
    }
}
