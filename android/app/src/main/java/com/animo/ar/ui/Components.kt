package com.animo.ar.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

/** Frosted panel used for every grouped block. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    fill: Color = Palette.Glass,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(squircle(radius))
            .background(fill)
            .border(1.dp, Palette.Stroke, squircle(radius))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) { content() }
}

/** Primary action: solid blossom pill with dark ink label. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(if (pressed) 0.97f else 1f)
            .clip(capsule())
            .background(if (enabled) Palette.Blossom else Palette.Blossom.copy(alpha = 0.30f))
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
            color = Color(0xFF2B0716),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Quiet action: glass capsule with a rim. */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(48.dp)
            .scale(if (pressed) 0.97f else 1f)
            .clip(capsule())
            .background(Palette.Glass)
            .border(1.dp, Palette.Stroke, capsule())
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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .scale(if (pressed) 0.92f else 1f)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, Palette.Stroke, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
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
    Box(
        modifier
            .height(44.dp)
            .clip(capsule())
            .background(if (active) Palette.Blossom.copy(alpha = 0.18f) else Palette.Glass)
            .border(
                1.dp,
                if (active) Palette.Blossom.copy(alpha = 0.6f) else Palette.Stroke,
                capsule(),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Palette.Blossom else Palette.Cream.copy(alpha = 0.9f),
            fontSize = 14.sp,
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
            .border(
                1.dp,
                if (active) Palette.Blossom.copy(alpha = 0.65f) else Palette.Stroke,
                squircle(22.dp),
            )
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
) {
    Box(
        modifier
            .height(52.dp)
            .clip(capsule())
            .background(Color(0x33000000))
            .border(1.dp, Palette.Stroke, capsule())
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(color = Palette.Cream, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth(),
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
            .clip(capsule())
            .background(Palette.BgTop.copy(alpha = 0.72f))
            .border(1.dp, Palette.Stroke, capsule())
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
            .clip(CircleShape)
            .background(Palette.Blossom.copy(alpha = 0.18f)),
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
