package com.shilapi.xcertplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import org.json.JSONObject

internal data class ActivationResult(
    val active: Boolean,
    val uploadToken: String? = null,
    val expiresAt: String? = null,
    val error: String? = null,
)

internal object ActivationClient {
    const val ACTIVATE_URL = "https://www.teamcc.net/api/activate"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    fun activate(
        deviceId: String,
        code: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): ActivationResult {
        val connection = openConnection(URL(ACTIVATE_URL)) as? HttpURLConnection
            ?: throw IOException("Activation endpoint did not open an HTTP connection")
        try {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("code", code.trim())
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size.toLong())
            connection.outputStream.use { it.write(body) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readLimited(it) }.orEmpty()
            if (statusCode !in 200..299) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                return ActivationResult(false, error = error.ifBlank { "http_$statusCode" })
            }
            val json = JSONObject(text)
            return ActivationResult(
                active = json.optBoolean("active"),
                uploadToken = json.optString("upload_token").ifBlank { null },
                expiresAt = json.optString("expires_at").ifBlank { null },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("Activation response too large")
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }
}
