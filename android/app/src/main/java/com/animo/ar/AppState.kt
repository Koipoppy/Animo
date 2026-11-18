package com.animo.ar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animo.ar.data.CreateInput
import com.animo.ar.data.Job
import com.animo.ar.data.Lux3dClient
import com.animo.ar.data.Lux3dPipeline
import com.animo.ar.data.PetRecord
import com.animo.ar.data.PetStore
import com.animo.ar.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

enum class Screen { Home, Create, Hatching, Play, Settings }

enum class CreateTab { Photo, Gallery, Link, Text }

class AppState(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    val store = PetStore(app)

    var screen by mutableStateOf(Screen.Home)
        private set

    var pets by mutableStateOf(store.list())
        private set

    var activePet by mutableStateOf<PetRecord?>(null)
        private set

    var job by mutableStateOf<Job?>(null)
        private set

    var status by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var hatchSucceeded by mutableStateOf(false)
        private set

    // Create form — kept here so switching tabs never loses what was typed.
    var name by mutableStateOf("")
    var link by mutableStateOf("")
    var prompt by mutableStateOf("")
    var plush by mutableStateOf(true)
    var tab by mutableStateOf(CreateTab.Photo)
    var pickedPhoto by mutableStateOf<ByteArray?>(null)

    private var runningJob: kotlinx.coroutines.Job? = null

    init {
        seedSample()
    }

    /** Copies the bundled T-Pose sample into the collection once, so the app is never empty. */
    private fun seedSample() {
        if (settings.sampleSeeded) return
        if (store.list().any { it.id == SAMPLE_ID }) {
            settings.sampleSeeded = true
            return
        }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().assets.open("models/sample.glb").use { it.readBytes() }
                }.getOrNull()
            } ?: return@launch
            val record = PetRecord(
                id = SAMPLE_ID,
                name = "示例手办",
                inputKind = "sample",
                source = "sample",
                createdAtUtc = Instant.now().toString(),
                bond = 0,
                triangles = 21_638,
            )
            withContext(Dispatchers.IO) { store.add(record, bytes) }
            settings.sampleSeeded = true
            refresh()
        }
    }

    fun go(target: Screen) {
        screen = target
        if (target != Screen.Hatching) status = ""
    }

    fun openCreate(initial: CreateTab) {
        if (screen != Screen.Create) plush = initial == CreateTab.Link || initial == CreateTab.Gallery
        tab = initial
        screen = Screen.Create
        status = ""
    }

    /** Named updateStatus: a plain setStatus would clash with the generated setter. */
    fun updateStatus(message: String) {
        status = message
    }

    fun refresh() {
        pets = store.list()
    }

    fun takeOut(record: PetRecord) {
        activePet = record
        screen = Screen.Play
    }

    fun removePet(record: PetRecord) {
        store.remove(record.id)
        refresh()
    }

    fun addBond(record: PetRecord, amount: Int = 1) {
        store.addBond(record.id, amount)
        activePet = store.list().firstOrNull { it.id == record.id }
        refresh()
    }

    /** Generates a humanoid figure directly against Lux3D, then stores the GLB locally. */
    fun hatch() {
        val key = settings.apiKey
        if (key.isBlank()) {
            status = "请先在设置中填写 Lux3D API Key"
            screen = Screen.Settings
            return
        }

        val cleanName = name.trim().ifEmpty { "无名手办" }
        val input: CreateInput = when (tab) {
            CreateTab.Photo, CreateTab.Gallery -> {
                val bytes = pickedPhoto ?: run {
                    status = "先选一张全身人形角色的图片"
                    return
                }
                CreateInput.Photo(bytes)
            }
            CreateTab.Link -> {
                val url = link.trim()
                if (!url.startsWith("https://")) {
                    status = "链接要以 https:// 开头"
                    return
                }
                CreateInput.Link(url)
            }
            CreateTab.Text -> {
                val text = prompt.trim()
                if (text.length < 2 || text.length > 200) {
                    status = "文字描述需要 2 到 200 个字"
                    return
                }
                CreateInput.Text(text)
            }
        }

        viewModelScope.launch {
            busy = true
            hatchSucceeded = false
            screen = Screen.Hatching
            job = Job("正在准备", 0.01f)
            status = "正在准备"
            try {
                val pipeline = Lux3dPipeline(Lux3dClient(key))
                val result = pipeline.generate(input, plush) { stage, progress ->
                    job = Job(stage, progress)
                    status = stage
                }

                val record = PetRecord(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    inputKind = result.inputKind,
                    source = "lux3d",
                    createdAtUtc = Instant.now().toString(),
                    bond = 0,
                    triangles = result.stats.triangles,
                )
                store.add(record, result.glb)
                refresh()
                activePet = record
                pickedPhoto = null
                job = Job(
                    stage = "手办完成",
                    progress = 1f,
                    triangles = result.stats.triangles,
                    animations = result.stats.animations,
                    bones = result.stats.bones,
                    engine = "lux3d",
                )
                hatchSucceeded = true
            } catch (error: CancellationException) {
                status = "已取消"
                job = null
                screen = Screen.Create
                throw error
            } catch (error: Exception) {
                status = error.message ?: "生成失败，请稍后重试"
                job = null
                screen = Screen.Create
            } finally {
                busy = false
                runningJob = null
            }
        }.also { runningJob = it }
    }

    fun cancelHatch() {
        runningJob?.cancel()
        runningJob = null
        busy = false
        job = null
        status = "已取消"
        screen = Screen.Create
    }

    fun testApiKey(onResult: (Boolean, String) -> Unit) {
        val key = settings.apiKey
        if (key.isBlank()) {
            onResult(false, "先填写 Lux3D API Key")
            return
        }
        viewModelScope.launch {
            val result = runCatching { Lux3dClient(key).checkKey() }
            onResult(
                result.isSuccess,
                result.fold(
                    onSuccess = { "Key 可用，可以开始制作手办了" },
                    onFailure = { it.message ?: "Key 校验失败" },
                ),
            )
        }
    }

    private companion object {
        const val SAMPLE_ID = "built-in-sample"
    }
}
