package com.animo.ar.data

import android.content.Context
import com.animo.ar.BuildConfig

/**
 * App settings. The Lux3D key belongs to the user and is encrypted with the Android Keystore.
 * Debug builds may prefill a developer key from android/local.properties; release builds never
 * contain a key.
 */
class Settings(context: Context) {

    private val secure = SecureStore(context)
    private val prefs = context.getSharedPreferences("animo", Context.MODE_PRIVATE)

    var apiKey: String
        get() {
            val stored = secure.get(KEY)
            if (stored.isNotBlank()) return stored
            return if (BuildConfig.DEBUG) BuildConfig.LUX3D_DEBUG_KEY else ""
        }
        set(value) = secure.put(KEY, value.trim())

    val usingDebugKey: Boolean
        get() = BuildConfig.DEBUG &&
            BuildConfig.LUX3D_DEBUG_KEY.isNotBlank() &&
            secure.get(KEY).isBlank()

    /** The built-in sample is copied once; deleting it does not make it reappear. */
    var sampleSeeded: Boolean
        get() = prefs.getBoolean("sample_seeded", false)
        set(value) = prefs.edit().putBoolean("sample_seeded", value).apply()

    private companion object {
        const val KEY = "lux3d_api_key"
    }
}
