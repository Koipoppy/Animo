package com.animo.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.animo.ar.ui.CreateScreen
import com.animo.ar.ui.HatchScreen
import com.animo.ar.ui.HomeScreen
import com.animo.ar.ui.PlayScreen
import com.animo.ar.ui.SettingsScreen
import java.io.File

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
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                state.pickedPhoto = stream.readBytes()
            }
        }.onFailure { state.updateStatus("读不了这张图片：${it.message}") }
    }

    var pendingShot by remember { mutableStateOf<android.net.Uri?>(null) }

    // ARCore and ACTION_IMAGE_CAPTURE both need CAMERA at runtime; ask once on first launch.
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

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingShot
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                state.pickedPhoto = stream.readBytes()
            }
        }.onFailure { state.updateStatus("读不了这张照片：${it.message}") }
    }

    when (state.screen) {
        Screen.Home -> HomeScreen(state)
        Screen.Create -> CreateScreen(
            state = state,
            onPickGallery = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                    state.updateStatus("先允许相机权限，再点一次")
                } else {
                    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
                    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    pendingShot = uri
                    camera.launch(uri)
                }
            },
        )
        Screen.Hatching -> HatchScreen(state)
        Screen.Settings -> SettingsScreen(state)
        Screen.Play -> {
            val pet = state.activePet
            if (pet == null) {
                state.go(Screen.Home)
            } else {
                PlayScreen(
                    pet = pet,
                    modelFile = state.store.modelFile(pet.id),
                    onBond = { state.addBond(pet) },
                    onExit = { state.go(Screen.Home) },
                )
            }
        }
    }
}
