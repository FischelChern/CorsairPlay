package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

class DiagnosticUploadClientTest {
    @Test fun acceptsOnlyHttpsEndpointsWithoutCredentials() {
        assertTrue(DiagnosticUploadClient.isValidEndpoint("https://example.com/report"))
        assertFalse(DiagnosticUploadClient.isValidEndpoint("http://example.com/report"))
        assertFalse(DiagnosticUploadClient.isValidEndpoint("https://user:pass@example.com/report"))
        assertFalse(DiagnosticUploadClient.isValidEndpoint("not a url"))
    }

    @Test fun postsRedactedReportWithBearerToken() {
        val connection = mock(HttpURLConnection::class.java)
        val output = ByteArrayOutputStream()
        val report = "Lincoln 0.2.24 · diagnostic report"
        `when`(connection.outputStream).thenReturn(output)
        `when`(connection.responseCode).thenReturn(204)

        val status = DiagnosticUploadClient.upload("https://example.com/report", " secret-token ", report) { connection }

        assertEquals(204, status)
        verify(connection).setRequestMethod("POST")
        verify(connection).setConnectTimeout(10_000)
        verify(connection).setReadTimeout(30_000)
        verify(connection).doOutput = true
        verify(connection).useCaches = false
        verify(connection).setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
        verify(connection).setRequestProperty("Authorization", "Bearer secret-token")
        verify(connection).setFixedLengthStreamingMode(report.toByteArray(Charsets.UTF_8).size.toLong())
        assertEquals(report, output.toString("UTF-8"))
        verify(connection).disconnect()
    }

    @Test fun reportsServerErrorStatus() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(500)
        `when`(connection.errorStream).thenReturn(ByteArrayInputStream("rejected".toByteArray()))

        val failure = runCatching {
            DiagnosticUploadClient.upload("https://example.com/report", "", "report") { connection }
        }.exceptionOrNull()

        assertTrue(failure is DiagnosticUploadClient.HttpStatusException)
        assertEquals(500, (failure as DiagnosticUploadClient.HttpStatusException).statusCode)
        verify(connection).disconnect()
    }
}
