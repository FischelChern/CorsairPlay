package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [NavigationDuckingTest.VolumeTrack::class])
class NavigationDuckingTest {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val speechAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val tracks = mutableListOf<AudioTrack>()

    @After fun cleanup() {
        tracks.forEach(AudioTrack::release)
        VolumeTrack.volumes.clear()
    }

    @Test fun guidanceDucksMusicAndReleasesAfterTheHold() {
        val coordinator = AudioFocusCoordinator(null, false)
        coordinator.setNavigationDuckingEnabled(true)
        val music = track()
        coordinator.acquire(music, AudioChannel.MEDIA, attributes)
        assertTrue(volumes(music).isEmpty())

        val now = 1_000_000_000L
        coordinator.navigationAudioDetected(now)
        // A 20 ms cadence reaches the ducked level inside the 300 ms ramp.
        for (step in 1..20) coordinator.tickNavigationDucking(now + step * 20_000_000L)
        assertEquals(0.25f, volumes(music).last(), 0.0001f)

        // Guidance keeps extending the hold, so music stays ducked.
        coordinator.navigationAudioDetected(now + 400_000_000L)
        coordinator.tickNavigationDucking(now + 800_000_000L)
        assertEquals(0.25f, volumes(music).last(), 0.0001f)

        // A pause longer than the hold ramps back to unity.
        var after = now + 1_300_000_001L
        for (step in 1..20) coordinator.tickNavigationDucking(after + step * 20_000_000L)
        assertEquals(1f, volumes(music).last(), 0.0001f)
    }

    @Test fun callsAndOffByDefaultAreNeverDucked() {
        val off = AudioFocusCoordinator(null, false)
        val music = track()
        off.acquire(music, AudioChannel.MEDIA, attributes)
        off.navigationAudioDetected(1_000_000_000L)
        for (step in 1..20) off.tickNavigationDucking(1_000_000_000L + step * 20_000_000L)
        assertTrue(volumes(music).isEmpty())

        val on = AudioFocusCoordinator(null, false)
        on.setNavigationDuckingEnabled(true)
        val call = track()
        on.acquire(call, AudioChannel.MEDIA, speechAttributes)
        on.navigationAudioDetected(1_000_000_000L)
        for (step in 1..20) on.tickNavigationDucking(1_000_000_000L + step * 20_000_000L)
        assertTrue("A loudspeaker call played as media must keep its level", volumes(call).isEmpty())
    }

    @Test fun disablingRestoresMusicImmediately() {
        val coordinator = AudioFocusCoordinator(null, false)
        coordinator.setNavigationDuckingEnabled(true)
        val music = track()
        coordinator.acquire(music, AudioChannel.MEDIA, attributes)
        coordinator.navigationAudioDetected(1_000_000_000L)
        for (step in 1..20) coordinator.tickNavigationDucking(1_000_000_000L + step * 20_000_000L)
        assertEquals(0.25f, volumes(music).last(), 0.0001f)
        coordinator.setNavigationDuckingEnabled(false)
        assertEquals(1f, volumes(music).last(), 0.0001f)
    }

    @Test fun loudNavigationPcmStartsDuckingAndSilenceDoesNot() {
        val sink = AndroidMediaSink(navigationDuckingEnabled = true)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod(
            "audioRenderer", AudioStreamId::class.java, AudioFormat::class.java,
        ).apply { isAccessible = true }
            .invoke(sink, AudioStreamId(96, "nav"), AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "default"))
        val track = track()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.NAVIGATION)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            type.getDeclaredField("startThresholdBytes").apply { isAccessible = true }.setInt(renderer, 1)
            val write = type.getDeclaredMethod(
                "writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            val coordinator = sink.javaClass.getDeclaredField("audioFocusCoordinator")
                .apply { isAccessible = true }.get(sink)
            val lastAudio = coordinator.javaClass.getDeclaredField("lastNavigationAudioNs")
                .apply { isAccessible = true }

            write.invoke(renderer, ByteArray(2_048), 0, 2_048)
            assertEquals(0L, lastAudio.getLong(coordinator))

            val loud = ByteArray(2_048)
            for (index in loud.indices step 2) {
                loud[index] = 0xff.toByte()
                loud[index + 1] = 0x7f
            }
            write.invoke(renderer, loud, 0, loud.size)
            assertNotNull(coordinator)
            assertTrue("A loud guidance chunk must arm ducking", lastAudio.getLong(coordinator) > 0L)
        } finally {
            sink.close()
        }
    }

    private fun volumes(track: AudioTrack): List<Float> = VolumeTrack.volumes[track].orEmpty()

    private fun track(): AudioTrack =
        AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)

    @Implements(AudioTrack::class)
    class VolumeTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            volumes.getOrPut(track) { mutableListOf() }.add(left)
            return AudioTrack.SUCCESS
        }
        companion object { val volumes = linkedMapOf<AudioTrack, MutableList<Float>>() }
    }
}
