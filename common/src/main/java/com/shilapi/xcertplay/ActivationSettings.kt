package com.shilapi.xcertplay

import android.content.Context
import java.util.UUID

/** Local activation identity and the device token returned by the activation server. */
internal object ActivationSettings {
    private const val PREFS = "corsairplay_activation"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_UPLOAD_TOKEN = "upload_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun uploadToken(context: Context): String? =
        preferences(context).getString(KEY_UPLOAD_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun expiresAt(context: Context): String? =
        preferences(context).getString(KEY_EXPIRES_AT, null)?.takeIf { it.isNotBlank() }

    fun isActivated(context: Context): Boolean = uploadToken(context) != null

    fun saveActivation(context: Context, uploadToken: String, expiresAt: String?) {
        preferences(context).edit()
            .putString(KEY_UPLOAD_TOKEN, uploadToken.trim())
            .putString(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(KEY_UPLOAD_TOKEN).remove(KEY_EXPIRES_AT).apply()
    }

    internal fun clearAllForTest(context: Context) {
        preferences(context).edit().clear().commit()
    }
}
