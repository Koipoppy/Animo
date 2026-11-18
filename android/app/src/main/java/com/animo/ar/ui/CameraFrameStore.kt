package com.animo.ar.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The latest frosted camera frame, shared with the liquid-glass panels. Each panel maps this bitmap
 * into its own bounds and colour-matrixes it; reads happen in the draw phase so a new frame
 * invalidates drawing, not the whole UI tree.
 */
internal object CameraFrameStore {
    var frame by mutableStateOf<Bitmap?>(null)
        private set
    var shader by mutableStateOf<BitmapShader?>(null)
        private set
    var frameWidth by mutableIntStateOf(0)
        private set
    var frameHeight by mutableIntStateOf(0)
        private set
    var screenLeft by mutableIntStateOf(0)
        private set
    var screenTop by mutableIntStateOf(0)
        private set
    var screenWidth by mutableIntStateOf(0)
        private set
    var screenHeight by mutableIntStateOf(0)
        private set
    /** False on Settings (camera is behind an opaque backdrop) and on Scan/Play. */
    var visible by mutableStateOf(false)
        private set
    private var loggedFirstFrame = false

    fun updateVisible(value: Boolean) {
        if (visible != value) Log.i("AnimoCamera", "glass visible=$value")
        visible = value
    }

    fun updateScreen(left: Int, top: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenLeft = left
            screenTop = top
            screenWidth = width
            screenHeight = height
        }
    }

    fun updateFrame(bitmap: Bitmap) {
        // A frame posted just before the backdrop was disposed can arrive after the buffer was
        // recycled; drop it instead of crashing on BitmapShader construction.
        if (bitmap.isRecycled) return
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i("AnimoCamera", "first glass frame ${bitmap.width}x${bitmap.height}")
        }
        frame = bitmap
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        frameWidth = bitmap.width
        frameHeight = bitmap.height
    }

    fun clearFrame() {
        frame = null
        shader = null
        frameWidth = 0
        frameHeight = 0
    }
}
