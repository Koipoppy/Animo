package com.animo.ar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.animo.ar.AppState
import com.animo.ar.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FRAMES = 12

/**
 * In-app multi-frame scanner, modelled on Shiwu: CameraX preview, tap to add a frame,
 * submit the whole set to Lux3D's multi-image reconstruction. No system camera hand-off.
 */
@Composable
fun ScanScreen(state: AppState, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val preview = remember {
        Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) state.updateStatus("没有相机权限就无法扫描")
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var frameCount by remember { mutableIntStateOf(0) }
    val frames = remember { mutableStateListOf<String>() }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        // ARCore releases the camera as the home preview leaves composition; give it a beat so
        // CameraX does not lose the bind race on the first frame.
        kotlinx.coroutines.delay(350)
        val cameraProvider = ProcessCameraProvider.getInstance(context).awaitProvider(context)
        provider = cameraProvider
        repeat(3) { attempt ->
            val bound = runCatching {
                cameraProvider.unbind(preview, imageCapture)
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            }.isSuccess
            if (bound) return@LaunchedEffect
            message = "相机正在切换，稍等…"
            kotlinx.coroutines.delay(600)
            if (attempt == 2) message = "相机启动失败，返回后重试"
        }
    }

    LaunchedEffect(torch, camera) {
        camera?.cameraControl?.enableTorch(torch)
    }

    DisposableEffect(preview) {
        onDispose {
            // Unbind only this screen's use cases: a blanket unbindAll would also tear down the
            // home/manage CameraX preview that is composed alongside the navigation transition.
            runCatching { provider?.unbind(preview, imageCapture) }
            // A submitted task still needs the JPEGs: AppState moves them into task storage.
            if (!submitted) {
                frames.forEach { runCatching { File(it).delete() } }
            }
        }
    }

    fun captureFrame() {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (capturing || frames.size >= MAX_FRAMES) return
        capturing = true
        message = ""
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(220))
        }
        val dir = File(context.cacheDir, "scan").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing = false
                    frames.add(file.absolutePath)
                    frameCount = frames.size
                    if (frames.size >= MAX_FRAMES) {
                        message = "已拍满 $MAX_FRAMES 帧，点「完成」提交"
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    file.delete()
                    message = "拍摄失败：${exception.message}"
                }
            },
        )
    }

    fun submit() {
        if (frames.isEmpty() || submitting) return
        submitting = true
        submitted = true
        state.submitScan(frames.toList())
    }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScanGuideOverlay(Modifier.fillMaxSize())

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value * 0.85f))
            )
        } else {
            PermissionPanel(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onClose,
            )
        }

        // Top bar: close, count, torch.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .liquidGlass(capsule(), base = Color(0xFF120F14), strength = 0.9f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton(Icons.Default.Close, "退出扫描", 46.dp, onClick = onClose, fill = Color(0xCC160B13))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("扫描手办", color = Palette.Cream, fontSize = 17.sp)
                Text(
                    "$frameCount/$MAX_FRAMES 帧",
                    color = if (frameCount > 0) Palette.Blossom else Palette.Mist,
                    fontSize = 11.sp
                )
            }
            RoundButton(
                Icons.Default.Star,
                if (torch) "关闭补光" else "打开补光",
                46.dp,
                onClick = { torch = !torch },
                fill = if (torch) Palette.Blossom else Color(0xCC160B13),
                tint = if (torch) Color(0xFF2B0716) else Palette.Cream,
            )
        }

        // Bottom controls.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xF212060C))
                    )
                )
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(top = 40.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                message.ifEmpty {
                    when {
                        camera == null -> "正在打开相机…"
                        frameCount == 0 -> "把角色放进取景框，轻按快门拍第一帧"
                        else -> "绕角色每 45° 拍一张 · 8–12 帧最理想"
                    }
                },
                color = if (message.isNotEmpty()) Palette.Coral else Palette.Cream.copy(alpha = 0.88f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "让角色正面站好、双手自然张开，拍出来更完整",
                color = Palette.Mist.copy(alpha = 0.8f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (frames.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(frames, key = { _, path -> path }) { index, path ->
                        FrameThumb(
                            path = path,
                            index = index,
                            onRemove = {
                                runCatching { File(path).delete() }
                                frames.remove(path)
                                frameCount = frames.size
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .liquidGlass(capsule(), base = Color(0xFF120F14), strength = 0.92f)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    if (frames.isNotEmpty()) {
                        RoundButton(
                            Icons.Default.Delete,
                            "清空帧",
                            46.dp,
                            onClick = {
                                frames.forEach { runCatching { File(it).delete() } }
                                frames.clear()
                                frameCount = 0
                                message = ""
                            },
                            fill = Color(0x99160B13),
                        )
                    }
                }

                ShutterButton(
                    enabled = hasPermission && camera != null && !capturing && !submitting &&
                        frames.size < MAX_FRAMES,
                    count = frameCount,
                    onClick = { captureFrame() },
                )

                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    CapsuleAction(
                        text = if (submitting) "提交中" else "完成",
                        enabled = frames.isNotEmpty() && !submitting,
                        onClick = { submit() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, count: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.9f)
    Box(
        Modifier
            .size(86.dp)
            .scale(scale)
            .liquidGlass(
                shape = CircleShape,
                base = Palette.Blossom,
                strength = if (enabled) 1.06f else 0.7f,
                tint = Palette.Blossom,
                tintAlpha = if (enabled) 0.62f else 0.28f,
                lens = true,
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
            if (count == 0) "拍摄" else "+1",
            color = Color(0xFF2B0716).copy(alpha = if (enabled) 1f else 0.5f),
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun CapsuleAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.93f)
    Box(
        Modifier
            .width(62.dp)
            .height(44.dp)
            .scale(scale)
            .shadow(10.dp, capsule(), clip = false)
            .clip(capsule())
            .background(
                if (enabled) {
                    Brush.verticalGradient(listOf(Color.White, Palette.Cream))
                } else {
                    Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x18FFFFFF)))
                }
            )
            .border(1.dp, Color.White.copy(alpha = 0.4f), capsule())
            .clickable(enabled = enabled, indication = null, interactionSource = interaction) {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF2B0716), fontSize = 13.sp)
    }
}

@Composable
private fun FrameThumb(path: String, index: Int, onRemove: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeThumb(path, 160) }
    }
    Box(
        Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(10.dp))
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "第 ${index + 1} 帧",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(24.dp)
                .liquidGlass(
                    shape = CircleShape,
                    base = Color(0xCC000000),
                    strength = 0.8f,
                    elevated = false,
                    tint = Color(0xCC000000),
                    tintAlpha = 0.45f,
                    lens = true,
                )
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = Palette.Cream, fontSize = 13.sp)
        }
    }
}

/** A static framing guide; the Shiwu build replaces this with MediaPipe subject tracking. */
@Composable
private fun ScanGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = size.width * 0.10f
        val right = size.width * 0.90f
        val top = size.height * 0.20f
        val bottom = size.height * 0.66f
        val arm = 26.dp.toPx()
        val stroke = 2.5.dp.toPx()
        val color = Palette.Cream.copy(alpha = 0.9f)

        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + arm * dx, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + arm * dy), stroke, StrokeCap.Round)
        }
        bracket(left, top, 1f, 1f)
        bracket(right, top, -1f, 1f)
        bracket(left, bottom, 1f, -1f)
        bracket(right, bottom, -1f, -1f)
        drawRect(
            color = Palette.Blossom.copy(alpha = 0.12f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(1.dp.toPx()),
        )
    }
}

@Composable
private fun PermissionPanel(onGrant: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(28.dp)
                .liquidGlass(squircle(30.dp), base = Color(0xFF120F14), strength = 0.95f)
                .padding(horizontal = 22.dp, vertical = 26.dp),
        ) {
            Text("需要相机权限", color = Palette.Cream, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "扫描手办要一直看着取景画面，请允许相机权限。",
                color = Palette.Mist,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("允许相机", Modifier.width(220.dp)) { onGrant() }
            Spacer(Modifier.height(10.dp))
            GhostButton("返回首页", Modifier.width(180.dp)) { onBack() }
        }
    }
}

private fun decodeThumb(path: String, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

