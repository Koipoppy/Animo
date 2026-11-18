package com.animo.ar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Home / Manage / Settings backdrop. CameraX preview only: no ARCore session, no Filament engine.
 * A low-rate ImageAnalysis stream feeds the liquid-glass refraction shader; it never touches the
 * preview path, so page switches stay cheap.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraBackdrop(
    modifier: Modifier = Modifier,
    /** Whether the camera is actually visible behind the UI and may be sampled by glass. */
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView (COMPATIBLE) keeps the camera inside the normal view hierarchy so the
            // Compose glass buttons above it receive taps; a PERFORMANCE SurfaceView can swallow
            // them on some devices/emulators.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // Ask Camera2 for the fastest advertised AE range (prefer a true 60 fps entry). Without
    // this the preview sits at the HAL default, usually 30 fps.
    val fpsRange: Range<Int>? = remember {
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val backId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching null
            val ranges = manager.getCameraCharacteristics(backId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ranges
                ?.filter { it.upper >= 60 }
                ?.maxByOrNull { it.upper * 1000 + it.lower }
                ?: ranges?.maxByOrNull { it.upper * 1000 + it.lower }
        }.getOrNull().also { Log.i("AnimoCamera", "camera fps range=$it") }
    }
    val preview = remember(fpsRange) {
        val builder = Preview.Builder()
            // Match the analysis stream's 4:3 crop so the refraction samples the same view.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .build()
            )
        if (fpsRange != null) {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
        builder.build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Default YUV_420_888: RGBA_8888 is rejected or silently produces no frames on
            // some vivo devices, which left every glass panel on the fallback fill.
            // Match the preview's 4:3 crop: this device otherwise reports a 960x960 square
            // analysis frame, so the lens would sample a different part of the scene.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            // 30 fps needs a frame small enough for CameraX to convert on
                            // every tick; bilinear sampling keeps the lens smooth.
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .build()
    }
    val analyzerExecutor = remember {
        Executors.newSingleThreadExecutor { Thread(it, "animo-glass-frame") }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(visible) {
        CameraFrameStore.updateVisible(visible)
    }

    DisposableEffect(analysis) {
        var lastFrameAt = 0L
        // Two reusable rotation targets: the UI may still be sampling the last posted bitmap
        // while the analyzer fills the other one, so no per-frame bitmap allocation.
        var rotateA: Bitmap? = null
        var rotateB: Bitmap? = null
        var useA = true
        val rotatePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        analysis.setAnalyzer(analyzerExecutor) { proxy ->
            try {
                val now = SystemClock.elapsedRealtime()
                // 60 fps target: KEEP_ONLY_LATEST drops any frame the analyzer cannot convert.
                if (now - lastFrameAt >= 16L) {
                    lastFrameAt = now
                    val raw = proxy.toBitmap()
                    val degrees = proxy.imageInfo.rotationDegrees
                    val upright = if (degrees == 0) {
                        raw
                    } else {
                        val swapped = degrees % 180 != 0
                        val w = if (swapped) raw.height else raw.width
                        val h = if (swapped) raw.width else raw.height
                        val cached = if (useA) rotateA else rotateB
                        val target = if (cached != null && cached.width == w && cached.height == h) {
                            cached
                        } else {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                if (useA) rotateA = it else rotateB = it
                            }
                        }
                        useA = !useA
                        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                        val bounds = RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat())
                        matrix.mapRect(bounds)
                        matrix.postTranslate(-bounds.left, -bounds.top)
                        Canvas(target).drawBitmap(raw, matrix, rotatePaint)
                        raw.recycle()
                        target
                    }
                    mainHandler.post { CameraFrameStore.updateFrame(upright) }
                }
            } catch (t: Throwable) {
                // The preview stays usable even if this device cannot produce an analysis bitmap;
                // log it instead of silently leaving every glass on the fallback fill.
                Log.w("AnimoCamera", "glass frame skipped", t)
            } finally {
                proxy.close()
            }
        }
        onDispose {
            analysis.clearAnalyzer()
            analyzerExecutor.shutdownNow()
            // Do not recycle the rotation buffers here: a frame already posted to the main
            // thread may still reference one of them. Let GC take them.
            CameraFrameStore.clearFrame()
            CameraFrameStore.updateVisible(false)
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@LaunchedEffect
        }
        // Let the previous CameraX/ARCore owner release the camera before rebinding.
        delay(220)
        val cameraProvider = ProcessCameraProvider.getInstance(context).awaitProvider(context)
        provider = cameraProvider
        repeat(3) {
            val withAnalysis = runCatching {
                cameraProvider.unbind(preview, analysis)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
            if (withAnalysis.isSuccess) return@LaunchedEffect
            Log.w("AnimoCamera", "preview+analysis bind failed", withAnalysis.exceptionOrNull())

            val previewOnly = runCatching {
                cameraProvider.unbind(preview, analysis)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            }
            if (previewOnly.isSuccess) {
                Log.w("AnimoCamera", "bound preview only; glass stays on the fallback")
                return@LaunchedEffect
            }
            Log.w("AnimoCamera", "preview bind failed", previewOnly.exceptionOrNull())
            delay(400)
        }
    }

    DisposableEffect(preview) {
        onDispose {
            runCatching { provider?.unbind(preview, analysis) }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                CameraFrameStore.updateScreen(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    coordinates.size.width,
                    coordinates.size.height,
                )
            },
    )
}

/** CameraX provider future -> coroutine, so binding never blocks composition. */
internal suspend fun ListenableFuture<ProcessCameraProvider>.awaitProvider(
    context: Context,
): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    addListener(
        {
            runCatching { get() }
                .onSuccess { cont.resume(it) }
                .onFailure { cont.resumeWith(Result.failure(it)) }
        },
        ContextCompat.getMainExecutor(context),
    )
}
