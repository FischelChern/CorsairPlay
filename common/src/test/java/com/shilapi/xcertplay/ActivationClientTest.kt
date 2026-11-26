package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ActivationClientTest {
    @Test fun returnsDeviceTokenFromSuccessfulActivation() {
        val connection = mock(HttpURLConnection::class.java)
        val output = ByteArrayOutputStream()
        val response = """{"active":true,"device_id":"device-1","expires_at":"2027-01-01T00:00:00Z","upload_token":"device-token"}"""
        `when`(connection.outputStream).thenReturn(output)
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(ByteArrayInputStream(response.toByteArray()))

        val result = ActivationClient.activate("device-1", " code ") { connection }

        assertTrue(result.active)
        assertEquals("device-token", result.uploadToken)
        assertEquals("2027-01-01T00:00:00Z", result.expiresAt)
        verify(connection).setRequestMethod("POST")
        verify(connection).setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val body = output.toString("UTF-8")
        assertTrue(body.contains("\"device_id\":\"device-1\""))
        assertTrue(body.contains("\"code\":\"code\""))
        verify(connection).disconnect()
    }

    @Test fun returnsServerErrorCode() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(403)
        `when`(connection.errorStream).thenReturn(
            ByteArrayInputStream("""{"error":"invalid_code"}""".toByteArray()),
        )

        val result = ActivationClient.activate("device-1", "bad") { connection }

        assertFalse(result.active)
        assertEquals("invalid_code", result.error)
        verify(connection).disconnect()
    }
}
