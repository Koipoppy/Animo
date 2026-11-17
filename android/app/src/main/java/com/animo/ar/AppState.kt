package com.animo.ar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animo.ar.data.ApiClient
import com.animo.ar.data.Job
import com.animo.ar.data.JobStatus
import com.animo.ar.data.PetRecord
import com.animo.ar.data.PetStore
import com.animo.ar.data.Settings
import kotlinx.coroutines.launch

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

    /** Starts a Lux3D job and polls it to completion, then stores the GLB locally. */
    fun hatch() {
        val api = ApiClient(settings.serverUrl, settings.secret)
        val cleanName = name.trim().ifEmpty { "小伙伴" }
        val style = if (plush) "plush" else "faithful"

        viewModelScope.launch {
            busy = true
            hatchSucceeded = false
            screen = Screen.Hatching
            status = "正在上传"
            try {
                val created = when (tab) {
                    CreateTab.Photo, CreateTab.Gallery -> {
                        val bytes = pickedPhoto ?: throw IllegalStateException("先选一张图片")
                        api.createFromPhoto(bytes, cleanName, style)
                    }
                    CreateTab.Link -> {
                        val url = link.trim()
                        if (!url.startsWith("http")) throw IllegalStateException("链接要以 http 开头")
                        api.createFromUrl(url, cleanName, style)
                    }
                    CreateTab.Text -> {
                        val text = prompt.trim()
                        if (text.length < 2) throw IllegalStateException("再多写几个字吧（至少 2 个字）")
                        api.createFromText(text, cleanName)
                    }
                }

                val done = api.awaitJob(created.id) { update ->
                    job = update
                    status = update.stage ?: "正在生成"
                }
                if (done.status == JobStatus.Failed) throw IllegalStateException(done.error ?: "生成失败")

                status = "正在下载模型"
                val glb = done.modelUrl?.let { api.downloadModel(it) }

                val record = PetRecord(
                    id = done.id,
                    name = cleanName,
                    inputKind = done.inputKind,
                    source = done.source ?: "lux3d",
                    createdAtUtc = java.time.Instant.now().toString(),
                    bond = 0,
                    triangles = done.triangles,
                )
                store.add(record, glb)
                refresh()
                activePet = record
                pickedPhoto = null
                job = null
                hatchSucceeded = true
            } catch (error: Exception) {
                status = error.message ?: "生成失败"
                screen = Screen.Create
            } finally {
                busy = false
            }
        }
    }

    fun testConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val api = ApiClient(settings.serverUrl, settings.secret)
            val ok = api.health()
            onResult(ok, if (ok) "连接成功，可以开始了" else "连不上服务器，检查地址或 adb reverse")
        }
    }
}
