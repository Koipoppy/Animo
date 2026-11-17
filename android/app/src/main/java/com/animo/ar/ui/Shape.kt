package com.animo.ar.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Superellipse ("squircle") rounded rectangle: |x|^n + |y|^n = r^n at each corner, n = 3.
 *
 * A circular corner meets the straight edge with a curvature jump from 0 to 1/r, which reads as a
 * visible kink. For n > 2 the corner leaves the edge with zero curvature, so the outline is G2
 * continuous all the way round. Each quarter is drawn with two cubic segments through the 45 degree
 * point, which tracks the true curve to well under a pixel at UI sizes.
 *
 * When [radius] exceeds half the short side the shape becomes a capsule, so this one type covers
 * both cards and pills.
 */
class SquircleShape(
    private val radius: Dp,
    private val power: Float = 3f,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(with(density) { radius.toPx() }, min(size.width, size.height) / 2f)
        if (r <= 0.5f) return Outline.Rectangle(Rect(Offset.Zero, size))

        val w = size.width
        val h = size.height
        val path = Path()

        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        corner(path, w - r, r, 90f, 0f, r)
        path.lineTo(w, h - r)
        corner(path, w - r, h - r, 0f, -90f, r)
        path.lineTo(r, h)
        corner(path, r, h - r, -90f, -180f, r)
        path.lineTo(0f, r)
        corner(path, r, r, 180f, 90f, r)
        path.close()

        return Outline.Generic(path)
    }

    /**
     * One quarter of the superellipse, sampled straight from its parametric form:
     * x = r * sgn(cos)|cos|^(2/n), y = r * sgn(sin)|sin|^(2/n).
     * Sampling cannot overshoot the outline, which hand-placed Bezier controls did.
     */
    private fun corner(path: Path, cx: Float, cy: Float, fromDeg: Float, toDeg: Float, r: Float) {
        val steps = 12
        val exponent = 2f / power
        for (i in 1..steps) {
            val deg = fromDeg + (toDeg - fromDeg) * (i / steps.toFloat())
            val rad = Math.toRadians(deg.toDouble())
            val cosA = Math.cos(rad).toFloat()
            val sinA = Math.sin(rad).toFloat()
            val x = cx + r * Math.signum(cosA) * abs(cosA).pow(exponent)
            val y = cy - r * Math.signum(sinA) * abs(sinA).pow(exponent)
            path.lineTo(x, y)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.radius == radius && other.power == power

    override fun hashCode(): Int = 31 * radius.hashCode() + power.hashCode()
}

/** Pill shape: the corner radius always wins over half the short side. */
fun capsule(power: Float = 2.4f): Shape = SquircleShape(radius = 2000.dp, power = power)

fun squircle(radius: Dp): Shape = SquircleShape(radius = radius, power = 3f)
