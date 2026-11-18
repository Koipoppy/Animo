package com.animo.ar.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animo.ar.Palette

/** Dark rose sky with two soft lights, the same identity as the Unity build. */
@Composable
fun DuskBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Palette.BgTop, Palette.BgBottom))
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Palette.Blossom.copy(alpha = 0.10f), Color.Transparent),
                    )
                )
        )
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Palette.Blossom,
        fontSize = 12.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 40) {
    Text(
        text = text,
        color = Palette.Cream,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Palette.Mist, size: Int = 14) {
    Text(text = text, color = color, fontSize = size.sp, lineHeight = (size + 6).sp, modifier = modifier)
}

/**
 * The CodePen MYwrMNy liquid glass, translated to Android:
 *  - the camera behind the element is displaced by the original 200x200 noise map
 *    (`feDisplacementMap scale=1`, R/G channels) after a 2% gaussian pre-blur
 *  - background rgba(255,255,255,.08)
 *  - a 2px rgba(255,255,255,.6) ring and the 0 16px 32px rgba(0,0,0,.12) shadow
 *
 * The filter samples the shared CameraX frame through AGSL; displacement uses the element's
 * short side on both axes so a wide capsule is not stretched sideways. Scan/Play have no shared
 * camera frame (and API < 33 has no RuntimeShader), so those fall back to a tinted surface.
 */
fun Modifier.liquidGlass(
    shape: Shape = capsule(),
    base: Color = Color(0xFF141016),
    strength: Float = 1f,
    elevated: Boolean = true,
    tint: Color = Color.Transparent,
    tintAlpha: Float = 0.18f,
    /** The displacement lens only works on round shapes; capsules/cards stay plain glass. */
    lens: Boolean = false,
): Modifier = composed {
    val s = strength.coerceIn(0f, 1.4f)
    val rendererSlot = remember { GlassRendererSlot() }
    val context = LocalContext.current
    val noise = remember(context) { GlassNoise.load(context) }
    var bounds by remember { mutableStateOf(Rect.Zero) }

    this
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .then(
            if (elevated) {
                Modifier.shadow(
                    elevation = 16.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .drawWithContent {
            val frameShader = CameraFrameStore.shader
            val frameW = CameraFrameStore.frameWidth
            val frameH = CameraFrameStore.frameHeight
            val screenW = CameraFrameStore.screenWidth
            val screenH = CameraFrameStore.screenHeight
            val frameReady = noise != null && CameraFrameStore.visible && frameShader != null &&
                frameW > 0 && frameH > 0 && screenW > 0 && screenH > 0 &&
                bounds.width > 0f && bounds.height > 0f
            if (lens && !frameReady) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastGlassLogAt > 2000L) {
                    lastGlassLogAt = now
                    android.util.Log.w(
                        "AnimoGlass",
                        "lens skipped: noise=${noise != null} visible=${CameraFrameStore.visible} " +
                            "shader=${frameShader != null} frame=${frameW}x${frameH} " +
                            "screen=${screenW}x${screenH} bounds=${bounds.width}x${bounds.height}",
                    )
                }
            }
            var drewGlass = false
            if (lens && frameReady) {
                val renderer = rendererSlot.get()
                if (renderer != null) {
                    drawIntoCanvas { canvas ->
                        renderer.draw(
                            canvas = canvas.nativeCanvas,
                            content = frameShader!!,
                            noise = noise!!,
                            left = bounds.left,
                            top = bounds.top,
                            width = size.width,
                            height = size.height,
                            screenLeft = CameraFrameStore.screenLeft,
                            screenTop = CameraFrameStore.screenTop,
                            screenWidth = screenW,
                            screenHeight = screenH,
                            frameWidth = frameW,
                            frameHeight = frameH,
                        )
                    }
                    // Light matte frost instead of a gaussian: a soft white veil over the
                    // displaced backdrop, at roughly the same visual weight as the reference.
                    drawRect(Color.White.copy(alpha = 0.12f * s))
                    if (tint != Color.Transparent) {
                        drawRect(tint.copy(alpha = (tintAlpha * s).coerceIn(0f, 0.5f)))
                    }
                    drewGlass = true
                }
            } else if (frameReady) {
                // Cards, rows and capsules are solid surfaces, not see-through panels: sample
                // figures and list rows need legibility, not the camera showing through them.
                drawRect(base.copy(alpha = (0.94f * s).coerceIn(0f, 1f)))
                drawRect(Color.White.copy(alpha = 0.03f * s))
                if (tint != Color.Transparent) {
                    drawRect(tint.copy(alpha = (tintAlpha * s).coerceIn(0f, 0.5f)))
                }
                drewGlass = true
            }
            if (!drewGlass) {
                if (lens) {
                    // No shared camera on AR Play/Scan: keep the circle translucent so the
                    // live AR scene shows through, with the same white frost as the lenses.
                    drawRect(base.copy(alpha = (0.38f * s).coerceIn(0f, 1f)))
                    drawRect(Color.White.copy(alpha = 0.12f * s))
                } else {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                base.copy(alpha = (0.95f * s).coerceIn(0f, 1f)),
                                base.copy(alpha = (0.90f * s).coerceIn(0f, 1f)),
                            )
                        )
                    )
                    drawRect(Color.White.copy(alpha = 0.03f * s))
                }
                if (tint != Color.Transparent) {
                    drawRect(tint.copy(alpha = (tintAlpha * s).coerceIn(0f, 0.5f)))
                }
            }

            drawContent()
        }
}

/** Lazily creates the per-component runtime shader; a failed compile falls back to tinted glass. */
private class GlassRendererSlot {
    private var renderer: GlassRenderer? = null
    private var failed = false

    fun get(): GlassRenderer? {
        renderer?.let { return it }
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching { GlassRenderer() }
            .onFailure {
                failed = true
                android.util.Log.w("AnimoGlass", "RuntimeShader init failed", it)
            }
            .getOrNull()
            ?.also { renderer = it }
    }
}

private var lastGlassLogAt = 0L

/** Pressed state -> spring scale, shared by every tappable glass surface. */
@Composable
fun rememberPressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "press-scale",
    )
    return scale
}

/** Frosted panel used for every grouped block. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    fill: Color = Color(0xFF141016),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .liquidGlass(squircle(radius), base = fill, strength = 0.95f)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) { content() }
}

/** Primary action: blossom glass pill with a specular top edge and dark ink label. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.965f)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .liquidGlass(
                shape = capsule(),
                base = Color(0xFF141016),
                strength = if (enabled) 1.05f else 0.6f,
                tint = Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Palette.Cream else Palette.Mist,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.62f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 4f,
                )
            ),
        )
    }
}

/** Quiet action: liquid-glass capsule with a gradient rim. */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.965f)
    Box(
        modifier
            .height(48.dp)
            .scale(scale)
            .liquidGlass(capsule(), strength = 0.9f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Cream, fontSize = 15.sp)
    }
}

/** Circular icon button, the main navigation affordance. */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    fill: Color = Palette.Glass,
    tint: Color = Palette.Cream,
    iconSize: Dp = size * 0.42f,
    label: String? = null,
    tintAlpha: Float = 0.55f,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.9f)
    val accent = fill == Palette.Blossom
    // Every round button is the same lens; a custom fill survives as a tint instead of turning
    // the button back into a solid disc.
    val tinted = fill != Palette.Glass
    val surface = Modifier.liquidGlass(
        shape = CircleShape,
        base = if (tinted) fill else Color(0xFF141016),
        strength = 1f,
        tint = if (tinted) fill else Color.Transparent,
        tintAlpha = tintAlpha,
        lens = true,
    )
    val iconTint = if (accent) Color(0xFF2B0716) else tint
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .then(surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(iconSize * 0.82f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = iconTint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            offset = Offset(0f, 1f),
                            blurRadius = 3f,
                        )
                    ),
                )
            }
        }
    }
}

/** Selectable chip used for the style toggle. */
@Composable
fun Chip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.97f)
    Box(
        modifier
            .height(44.dp)
            .scale(scale)
            .liquidGlass(
                shape = capsule(),
                base = Color(0xFF171319),
                strength = if (active) 1.05f else 0.8f,
                elevated = false,
                tint = if (active) Palette.Blossom else Color.Transparent,
                tintAlpha = 0.18f,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Palette.Cream else Palette.Cream.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/** Source option card: icon over label over hint. */
@Composable
fun SourceTile(
    icon: ImageVector,
    title: String,
    hint: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(squircle(22.dp))
            .background(if (active) Palette.Blossom.copy(alpha = 0.16f) else Palette.Glass)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (active) Palette.Blossom else Palette.Mist,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(title, color = Palette.Cream, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(hint, color = Palette.Mist, fontSize = 11.sp)
        }
    }
}

/** Text entry that matches the capsule language; the system keyboard does the editing. */
@Composable
fun CapsuleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(52.dp)
            .liquidGlass(capsule(), base = Color(0xFF100D12), strength = 0.85f, elevated = false)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            textStyle = TextStyle(color = Palette.Cream, fontSize = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = Palette.Mist.copy(alpha = 0.75f), fontSize = 15.sp)
                }
                inner()
            },
        )
    }
}

/** Speech line on the play screen. */
@Composable
fun Bubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .liquidGlass(capsule(), base = Color(0xFF100D12), strength = 0.9f, elevated = false)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text, color = Palette.Cream, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun PetAvatar(name: String, size: Dp = 52.dp) {
    Box(
        Modifier
            .size(size)
            .liquidGlass(
                shape = CircleShape,
                base = Color(0xFF171319),
                strength = 0.95f,
                tint = Palette.Blossom,
                tintAlpha = 0.20f,
                lens = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).ifEmpty { "灵" },
            color = Palette.Blossom,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
