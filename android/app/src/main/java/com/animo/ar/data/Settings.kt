package com.animo.ar.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** Optional virtual flashlight anchored at the camera (the baseline fill is always on). */
    var cameraLight: Boolean
        get() = prefs.getBoolean("camera_light", false)
        set(value) = prefs.edit().putBoolean("camera_light", value).apply()

    // Compose state so toggling a quality option updates the screen immediately.
    private var autoMultiViewState by mutableStateOf(prefs.getBoolean("auto_multiview", true))
    /** Generate the four reference views before modelling (better back/side fidelity). */
    var autoMultiView: Boolean
        get() = autoMultiViewState
        set(value) {
            autoMultiViewState = value
            prefs.edit().putBoolean("auto_multiview", value).apply()
        }

    private var faceCountState by mutableIntStateOf(
        prefs.getInt("face_count", MAX_FACE_COUNT).coerceIn(MIN_FACE_COUNT, MAX_FACE_COUNT)
    )
    /** Target mesh faces; the API's documented range is 10k–300k. Defaults to the maximum. */
    var faceCount: Int
        get() = faceCountState
        set(value) {
            val clamped = value.coerceIn(MIN_FACE_COUNT, MAX_FACE_COUNT)
            faceCountState = clamped
            prefs.edit().putInt("face_count", clamped).apply()
        }

    companion object {
        const val KEY = "lux3d_api_key"
        const val MIN_FACE_COUNT = 10_000
        const val MAX_FACE_COUNT = 300_000
    }
}
