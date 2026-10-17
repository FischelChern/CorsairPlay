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
        var now = 1_000_000_000L
        val coordinator = AudioFocusCoordinator(null, false)
        coordinator.nanoTime = { now }
        coordinator.setNavigationDuckingEnabled(true)
        assertEquals(1f, coordinator.musicPcmGain, 0.0001f)

        coordinator.navigationAudioDetected()
        // A 20 ms cadence reaches the ducked level inside the 300 ms ramp.
        repeat(20) { now += 20_000_000L; coordinator.tickNavigationDucking() }
        assertEquals(0.25f, coordinator.musicPcmGain, 0.0001f)

        // Guidance keeps extending the hold, so music stays ducked.
        now += 300_000_000L
        coordinator.navigationAudioDetected()
        now += 300_000_000L
        coordinator.tickNavigationDucking()
        assertEquals(0.25f, coordinator.musicPcmGain, 0.0001f)

        // A pause longer than the hold ramps back to unity.
        now += 1_000_000_000L
        repeat(20) { now += 20_000_000L; coordinator.tickNavigationDucking() }
        assertEquals(1f, coordinator.musicPcmGain, 0.0001f)
    }

    @Test fun callsAndOffByDefaultAreNeverDucked() {
        var now = 1_000_000_000L
        val off = AudioFocusCoordinator(null, false)
        off.nanoTime = { now }
        off.navigationAudioDetected()
        repeat(20) { now += 20_000_000L; off.tickNavigationDucking() }
        assertEquals(1f, off.musicPcmGain, 0.0001f)

        val on = AudioFocusCoordinator(null, false)
        on.nanoTime = { now }
        on.setNavigationDuckingEnabled(true)
        val call = track()
        on.acquire(call, AudioChannel.MEDIA, speechAttributes)
        on.navigationAudioDetected()
        repeat(20) { now += 20_000_000L; on.tickNavigationDucking() }
        assertEquals(0.25f, on.musicPcmGain, 0.0001f)
        // A loudspeaker call is mapped to MEDIA but is speech; the renderer skips scaling it.
    }

    @Test fun disablingRestoresMusicImmediately() {
        var now = 1_000_000_000L
        val coordinator = AudioFocusCoordinator(null, false)
        coordinator.nanoTime = { now }
        coordinator.setNavigationDuckingEnabled(true)
        coordinator.navigationAudioDetected()
        repeat(20) { now += 20_000_000L; coordinator.tickNavigationDucking() }
        assertEquals(0.25f, coordinator.musicPcmGain, 0.0001f)
        coordinator.setNavigationDuckingEnabled(false)
        assertEquals(1f, coordinator.musicPcmGain, 0.0001f)
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

    @Test fun musicSamplesAreScaledWhileDuckingAndRestoredAfterwards() {
        val sink = AndroidMediaSink(navigationDuckingEnabled = true)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod(
            "audioRenderer", AudioStreamId::class.java, AudioFormat::class.java,
        ).apply { isAccessible = true }
            .invoke(sink, AudioStreamId(100, "media"), AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media"))
        val nav = AudioStreamId(96, "nav")
        val navRenderer = AndroidMediaSink::class.java.getDeclaredMethod(
            "audioRenderer", AudioStreamId::class.java, AudioFormat::class.java,
        ).apply { isAccessible = true }
            .invoke(sink, nav, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "default"))
        val track = track()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.MEDIA)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            type.getDeclaredField("startThresholdBytes").apply { isAccessible = true }.setInt(renderer, 1)
            // Skip the one-off fade-in of a real session so the bytes stay comparable.
            type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(renderer, true)
            val write = type.getDeclaredMethod(
                "writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            // Unity: the samples reach the track untouched.
            val music = loudBytes()
            val untouched = music.copyOf()
            write.invoke(renderer, music, 0, music.size)
            org.junit.Assert.assertArrayEquals(untouched, music)

            // Arm guidance from the navigation renderer, let the ramp settle, then play music again.
            val coordinator = sink.javaClass.getDeclaredField("audioFocusCoordinator")
                .apply { isAccessible = true }.get(sink) as AudioFocusCoordinator
            var fake = 1_000_000_000L
            coordinator.nanoTime = { fake }
            coordinator.navigationAudioDetected()
            repeat(20) { fake += 20_000_000L; coordinator.tickNavigationDucking() }
            assertEquals(0.25f, coordinator.musicPcmGain, 0.001f)

            val ducked = loudBytes()
            write.invoke(renderer, ducked, 0, ducked.size)
            val peak = (0 until ducked.size / 2).maxOf { index ->
                kotlin.math.abs(((ducked[index * 2].toInt() and 0xff) or (ducked[index * 2 + 1].toInt() shl 8)).toShort().toInt())
            }
            assertTrue("Music PCM must be quieter while guidance speaks, peak=$peak", peak < 9_000)
            assertTrue("Music PCM must not be silenced, peak=$peak", peak > 1_000)

            // Turning the switch off restores unity without touching AudioTrack volume.
            sink.setNavigationDuckingEnabled(false)
            val restored = loudBytes()
            write.invoke(renderer, restored, 0, restored.size)
            assertTrue(volumes(track).isEmpty())
            assertEquals(16_384, volumeOf(restored))
        } finally {
            sink.close()
        }
    }

    private fun volumes(track: AudioTrack): List<Float> = VolumeTrack.volumes[track].orEmpty()

    private fun loudBytes(): ByteArray = ByteArray(2_048).also { buffer ->
        for (index in buffer.indices step 2) {
            buffer[index] = 0x00
            buffer[index + 1] = 0x40 // 16384
        }
    }

    private fun volumeOf(data: ByteArray): Int = (0 until data.size / 2).maxOf { index ->
        kotlin.math.abs(((data[index * 2].toInt() and 0xff) or (data[index * 2 + 1].toInt() shl 8)).toShort().toInt())
    }

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
