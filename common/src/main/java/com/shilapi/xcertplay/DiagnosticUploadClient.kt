package com.shilapi.xcertplay

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/** Uploads the already-redacted diagnostic report to a driver-configured HTTPS endpoint. */
internal object DiagnosticUploadClient {
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024

    fun isValidEndpoint(value: String): Boolean {
        val url = runCatching { URL(value.trim()) }.getOrNull() ?: return false
        return url.protocol.equals("https", ignoreCase = true) &&
            url.host.isNotBlank() &&
            url.userInfo == null
    }

    fun upload(
        endpoint: String,
        token: String,
        report: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): Int {
        if (!isValidEndpoint(endpoint)) throw IOException("Diagnostic upload endpoint must be HTTPS")
        val connection = openConnection(URL(endpoint.trim())) as? HttpURLConnection
            ?: throw IOException("Diagnostic upload endpoint did not open an HTTP connection")
        try {
            val bytes = report.toByteArray(Charsets.UTF_8)
            val bearer = token.trim()
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
            connection.setRequestProperty("Accept", "text/plain")
            if (bearer.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setFixedLengthStreamingMode(bytes.size.toLong())
            connection.outputStream.use { it.write(bytes) }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) drain(connection.inputStream) else drain(connection.errorStream)
            if (statusCode !in 200..299) throw HttpStatusException(statusCode)
            return statusCode
        } finally {
            connection.disconnect()
        }
    }

    private fun drain(stream: InputStream?) {
        stream?.use { input ->
            val buffer = ByteArray(1024)
            var total = 0
            while (total < MAX_RESPONSE_BYTES) {
                val remaining = MAX_RESPONSE_BYTES - total
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                total += read
            }
        }
    }

    class HttpStatusException(val statusCode: Int) : IOException("Diagnostic upload failed with HTTP $statusCode")
}
