package com.animo.ar.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max

/**
 * Direct translation of the CodePen `#frosted` filter:
 *
 * ```
 * <feImage ... width="1" height="1"/>
 * <feGaussianBlur in="SourceGraphic" stdDeviation="0.02"/>
 * <feDisplacementMap in2="map" scale="1" xChannelSelector="R" yChannelSelector="G"/>
 * ```
 *
 * Two deliberate adaptations for a live camera, both of which caused earlier ports to break:
 *  - the backdrop is pre-blurred once per frame by CameraBackdrop (not by a 9-tap kernel inside
 *    this shader), so one content sample at the displaced coordinate is enough;
 *  - displacement uses the element's short side on both axes. On the reference's 320px circle
 *    that is identical to `scale=1` bbox; on a 650x220 capsule it avoids dragging the straight
 *    edges sideways three times further than vertically.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassRenderer {

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    fun draw(
        canvas: Canvas,
        content: BitmapShader,
        noise: NoiseTexture,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        screenLeft: Int,
        screenTop: Int,
        screenWidth: Int,
        screenHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        // PreviewView is FILL_CENTER: map the element's window bounds into the upright frame.
        val scale = max(
            screenWidth.toFloat() / frameWidth,
            screenHeight.toFloat() / frameHeight,
        )
        val drawWidth = frameWidth * scale
        val drawHeight = frameHeight * scale
        val offsetX = (screenWidth - drawWidth) / 2f
        val offsetY = (screenHeight - drawHeight) / 2f
        val srcScale = 1f / scale

        shader.setInputShader("content", content)
        shader.setInputShader("noise", noise.shader)
        shader.setFloatUniform(
            "srcOffset",
            (left - screenLeft - offsetX) / scale,
            (top - screenTop - offsetY) / scale,
        )
        shader.setFloatUniform("srcScale", srcScale, srcScale)
        shader.setFloatUniform("sizePx", width, height)
        shader.setFloatUniform("noiseSize", noise.width.toFloat(), noise.height.toFloat())

        paint.shader = shader
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
    }

    private companion object {
        const val AGSL = """
            uniform shader content;
            uniform shader noise;
            uniform float2 srcOffset;
            uniform float2 srcScale;
            uniform float2 sizePx;
            uniform float2 noiseSize;

            // RuntimeShader inputs sample a BitmapShader with nearest filtering, which is
            // exactly the blocky grid visible on a 720x960 frame blown up to the screen.
            // Four taps give bilinear interpolation without bringing back the gaussian.
            half4 smoothSample(float2 q) {
                float2 base = floor(q - 0.5) + 0.5;
                float2 f = q - base;
                half4 c00 = content.eval(base);
                half4 c10 = content.eval(base + float2(1.0, 0.0));
                half4 c01 = content.eval(base + float2(0.0, 1.0));
                half4 c11 = content.eval(base + float2(1.0, 1.0));
                return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
            }

            half4 main(float2 fragCoord) {
                float2 p = srcOffset + fragCoord * srcScale;
                float2 uv = fragCoord / max(sizePx, float2(1.0));

                // feImage is stretched to the object bounding box; sample its R/G exactly.
                half4 map = noise.eval(uv * noiseSize);
                float span = min(sizePx.x, sizePx.y);
                float2 q = p + float2(map.r - 0.5, map.g - 0.5) * span * srcScale;

                // No gaussian kernel: one displaced coordinate, sampled smoothly. The matte
                // finish is the light white veil drawn by Modifier.liquidGlass.
                return smoothSample(q);
            }
        """
    }
}
