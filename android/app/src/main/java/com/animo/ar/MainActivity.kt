package com.animo.ar

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.ar.data.PetRecord
import kotlinx.coroutines.launch
import com.animo.ar.ui.CameraBackdrop
import com.animo.ar.ui.HomeScreen
import com.animo.ar.ui.ManageScreen
import com.animo.ar.ui.PlayScreen
import com.animo.ar.ui.ScanScreen
import com.animo.ar.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF12060C)) {
                AnimoApp()
            }
        }
    }
}

@Composable
private fun AnimoApp() {
    val state: AppState = viewModel()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.submitImageUri(uri, "gallery")
    }

    // Export the GLB through the system document picker (Downloads / cloud drives).
    var pendingExport by remember { mutableStateOf<PetRecord?>(null) }
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri ->
        val pet = pendingExport
        pendingExport = null
        if (uri != null && pet != null) {
            exportScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        state.store.modelFile(pet.id).inputStream().use { input ->
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        }
    }

    fun sharePet(pet: PetRecord, wechatOnly: Boolean) {
        val file = state.store.modelFile(pet.id)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, pet.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(pet.name, uri)
        }
        if (wechatOnly) {
            val direct = Intent(send).setPackage("com.tencent.mm")
            if (runCatching { context.startActivity(direct) }.isSuccess) return
        }
        runCatching { context.startActivity(Intent.createChooser(send, "分享手办")) }
    }

    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    val recordScope = androidx.compose.runtime.rememberCoroutineScope()

    fun shareVideo(file: java.io.File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("animo clip", uri)
        }
        val wechat = Intent(send).setPackage("com.tencent.mm")
        if (runCatching { context.startActivity(wechat) }.isSuccess) return
        runCatching { context.startActivity(Intent.createChooser(send, "分享动态照片")) }
    }

    fun saveVideo(file: java.io.File) {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Animo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
    }

    fun shareImage(file: java.io.File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("animo motion photo", uri)
        }
        val wechat = Intent(send).setPackage("com.tencent.mm")
        if (runCatching { context.startActivity(wechat) }.isSuccess) return
        runCatching { context.startActivity(Intent.createChooser(send, "分享动态照片")) }
    }

    fun saveMotionPhoto(file: java.io.File) {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // Camera roll is where WeChat looks for Motion Photo / Live Photo items.
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
    }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            ShareCenter.recording = false
            return@rememberLauncherForActivityResult
        }
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val output = java.io.File(dir, "animo_${System.currentTimeMillis()}.mp4")
        ShareCenter.pendingVideo = output
        ShareCenter.lastVideo = null
        ShareCenter.recording = true
        val service = Intent(context, ShareRecordService::class.java).apply {
            putExtra(ShareRecordService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ShareRecordService.EXTRA_DATA, data)
            putExtra(ShareRecordService.EXTRA_OUTPUT, output.absolutePath)
        }
        recordScope.launch {
            // Let PlayScreen hide its HUD and the system bars first; the projection starts
            // after that so the clip contains only the AR camera + figure.
            kotlinx.coroutines.delay(450)
            ContextCompat.startForegroundService(context, service)
            // 6 s capture + encoder finalisation; the service publishes the file when the
            // muxer has written the moov atom, so an early header-only file is never shared.
            var waited = 0
            while (waited < 15_000 && ShareCenter.lastVideo != output) {
                kotlinx.coroutines.delay(300)
                waited += 300
            }
            ShareCenter.recording = false
            if (ShareCenter.lastVideo == output && output.length() > 0) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    saveVideo(output)
                }
                Toast.makeText(context, "动态照片已保存到相册", Toast.LENGTH_SHORT).show()
                shareVideo(output)
            }
        }
    }

    fun startRecording() {
        recordLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ARCore / CameraX scanning need CAMERA at runtime; ask once on first launch.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // First run: there is no key yet, so open settings instead of showing an empty collection.
    LaunchedEffect(Unit) {
        if (state.settings.apiKey.isBlank()) state.go(Screen.Settings)
    }

    // Home and Manage share one ARCore camera session: navigating between them must not tear
    // the session down, otherwise returning to Home stalls while the camera re-initialises.
    // Keep the lightweight CameraX backdrop bound across Home / Manage / Settings so switching
    // pages never waits for a camera session. Scan and Play own the camera and rebind themselves.
    val keepCameraAlive = state.screen == Screen.Home ||
        state.screen == Screen.Manage ||
        state.screen == Screen.Settings

    Box(Modifier.fillMaxSize()) {
        if (keepCameraAlive) {
            CameraBackdrop(
                visible = state.screen == Screen.Home ||
                    state.screen == Screen.Manage ||
                    state.screen == Screen.Settings,
            )
        }

        when (state.screen) {
        Screen.Home -> HomeScreen(
            state = state,
            onPickGallery = {
                if (state.requireApiKey()) {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            },
            onTakePhoto = {
                if (state.requireApiKey()) state.go(Screen.Scan)
            },
        )
        Screen.Manage -> ManageScreen(state)
        Screen.Scan -> ScanScreen(state, onClose = { state.go(Screen.Home) })
        Screen.Settings -> SettingsScreen(state)
        Screen.Play -> {
            val pet = state.activePet
            if (pet == null) {
                state.go(Screen.Home)
            } else {
                PlayScreen(
                    pet = pet,
                    modelFile = state.store.modelFile(pet.id),
                    cameraLight = state.settings.cameraLight,
                    onToggleCameraLight = { state.settings.cameraLight = it },
                    onExit = { state.go(Screen.Home) },
                    onGenerateClip = { state.generateClip(pet.id, it) },
                    clipJob = state.clipJob,
                    clipStage = state.clipStage,
                    clipProgress = state.clipProgress,
                    clipError = state.clipError,
                    onExportModel = {
                        pendingExport = pet
                        exportLauncher.launch("${pet.name}.glb")
                    },
                    onShareModel = { wechatOnly -> sharePet(pet, wechatOnly) },
                    onMotionPhoto = { photo ->
                        recordScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                saveMotionPhoto(photo)
                            }
                            Toast.makeText(context, "动态照片已保存到相册", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
        }
    }
}
