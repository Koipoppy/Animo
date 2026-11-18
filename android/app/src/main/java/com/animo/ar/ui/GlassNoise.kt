package com.animo.ar.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader

/** The 200x200 R/G noise map from the original CodePen filter, shipped as an asset. */
internal class NoiseTexture(
    val shader: BitmapShader,
    val width: Int,
    val height: Int,
)

internal object GlassNoise {
    @Volatile
    private var cached: NoiseTexture? = null

    fun load(context: Context): NoiseTexture? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val bitmap = context.assets.open("noise.png").use { BitmapFactory.decodeStream(it) }
                    ?: return null
                NoiseTexture(
                    shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                    width = bitmap.width,
                    height = bitmap.height,
                )
            }.getOrNull()?.also { cached = it }
        }
    }
}
