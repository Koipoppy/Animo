package com.animo.ar.data

import android.content.Context
import com.animo.ar.BuildConfig

/** Backend endpoint and optional shared secret, kept across launches. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("animo", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: DEFAULT_URL
        set(value) = prefs.edit().putString("serverUrl", value.trim().trimEnd('/')).apply()

    var secret: String
        get() = prefs.getString("secret", null).orEmpty()
        set(value) = prefs.edit().putString("secret", value.trim()).apply()

    companion object {
        /**
         * Debug builds default to adb reverse / LAN over HTTP; release builds bake in the
         * deployed HTTPS backend through ANIMO_API_BASE_URL.
         */
        val DEFAULT_URL: String = BuildConfig.API_BASE_URL
    }
}
