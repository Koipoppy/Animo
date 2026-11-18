package com.animo.ar

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animo.ar.data.CreateInput
import com.animo.ar.data.GenerateResult
import com.animo.ar.data.GenerationTask
import com.animo.ar.data.Lux3dClient
import com.animo.ar.data.Lux3dPipeline
import com.animo.ar.data.PetRecord
import com.animo.ar.data.PetStore
import com.animo.ar.data.Settings
import com.animo.ar.data.TaskStatus
import com.animo.ar.data.TaskStore
import com.animo.ar.data.inspectGlb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

enum class Screen { Home, Manage, Scan, Play, Settings }

/** Source of a generation task, persisted so a failed task can be regenerated later. */
private sealed interface TaskPayload {
    data class Text(val prompt: String) : TaskPayload
    data class Images(val files: List<File>) : TaskPayload
}

class AppState(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    val store = PetStore(app)
    val taskStore = TaskStore(app)

    var screen by mutableStateOf(Screen.Home)
        private set

    var pets by mutableStateOf(store.list())
        private set

    /** Unfinished jobs, newest last; they live in the collection next to finished figures. */
    var tasks by mutableStateOf(emptyList<GenerationTask>())
        private set

    var activePet by mutableStateOf<PetRecord?>(null)
        private set

    var status by mutableStateOf("")
        private set

    /** On-demand clip generation from the AR screen. */
    var clipJob by mutableStateOf<String?>(null)
        private set
    var clipStage by mutableStateOf("")
        private set
    var clipProgress by mutableStateOf(0f)
        private set
    var clipError by mutableStateOf<String?>(null)
        private set

    private val jobs = mutableMapOf<String, Job>()

    init {
        // A RUNNING task in the file belongs to a process that is gone; it can never resume.
        val stored = taskStore.list()
        val restored = stored.map { task ->
            if (task.status == TaskStatus.RUNNING) {
                task.copy(
                    status = TaskStatus.FAILED,
                    stage = "已中断",
                    error = "App 已退出，任务中断；可以删除后重新制作",
                )
            } else {
                task
            }
        }
        tasks = restored
        if (restored != stored) taskStore.writeAll(restored)
        seedSample()
    }

    /** Copies the bundled "萌萌" sample into the collection once, so the app is never empty. */
    private fun seedSample() {
        if (store.list().any { it.id == SAMPLE_ID }) {
            settings.sampleSeeded = true
            return
        }
        // Replace the old bundled samples (built-in ids only, never a generated pet).
        store.list()
            .filter { it.id == LEGACY_SAMPLE_ID || it.id == LEGACY_SAMPLE_ID_2 || it.id == LEGACY_SAMPLE_ID_3 || it.id == LEGACY_SAMPLE_ID_4 || it.id == LEGACY_SAMPLE_ID_5 || it.id == LEGACY_SAMPLE_ID_6 || it.id == LEGACY_SAMPLE_ID_7 || it.id == LEGACY_SAMPLE_ID_8 || it.id == LEGACY_SAMPLE_ID_9 || it.id == LEGACY_SAMPLE_ID_10 || it.id == LEGACY_SAMPLE_ID_11 || it.id == LEGACY_SAMPLE_ID_13 || it.id == LEGACY_SAMPLE_ID_14 || it.id == LEGACY_SAMPLE_ID_12 }
            .forEach { store.remove(it.id) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().assets.open("models/sample.glb").use { it.readBytes() }
                }.getOrNull()
            } ?: return@launch
            val record = PetRecord(
                id = SAMPLE_ID,
                name = "萌萌",
                inputKind = "sample",
                source = "sample",
                createdAtUtc = Instant.now().toString(),
                bond = 0,
                triangles = 364_285,
                clips = listOf("Idle_Loop", "Walk_Loop"),
            )
            withContext(Dispatchers.IO) { store.add(record, bytes) }
            settings.sampleSeeded = true
            refresh()
        }
    }

    fun go(target: Screen) {
        screen = target
        status = ""
    }

    /**
     * Generate one extra clip for an already-landed figure. Reuses the saved rig and asks the
     * retarget endpoint for the accumulated set, so the local GLB always has every chosen clip.
     */
    fun generateClip(petId: String, clipId: String) {
        if (clipJob != null) return
        val pet = pets.firstOrNull { it.id == petId } ?: return
        if (clipId in pet.clips) return
        if (pet.rigPending) {
            clipError = "走路动作还在后台生成，请稍等一下"
            return
        }
        val key = settings.apiKey
        if (key.isBlank()) {
            clipError = "请先在设置中填写 Lux3D API Key"
            return
        }
        clipError = null
        clipJob = clipId
        clipStage = "正在准备动作"
        clipProgress = 0f
        viewModelScope.launch {
            try {
                val pipeline = Lux3dPipeline(Lux3dClient(key, faceCount = settings.faceCount))
                val onStage: (String, Float) -> Unit = { stage, progress ->
                    clipStage = stage
                    clipProgress = progress
                }
                val rigUrl = pet.rigUrl ?: pet.modelUrl?.let { pipeline.rigModel(it, onStage) }
                    ?: throw IllegalStateException("这个手办没有可用的绑骨模型，请重新生成一次")
                val existing = pet.clips.ifEmpty {
                    withContext(Dispatchers.IO) {
                        val file = store.modelFile(pet.id)
                        if (file.exists()) {
                            runCatching { inspectGlb(file.readBytes())?.animationNames }.getOrNull()
                                .orEmpty()
                        } else {
                            emptyList()
                        }
                    }
                }
                val base = if (existing.isEmpty()) Lux3dPipeline.BASE_CLIPS else existing
                val wanted = (base + clipId).distinct()
                if (wanted.size > 10) throw IllegalStateException("每个手办最多支持 10 段动作")
                val result = pipeline.retarget(rigUrl, wanted, onStage)
                val updated = pet.copy(
                    rigUrl = result.rigUrl ?: rigUrl,
                    clips = result.clips,
                    triangles = result.stats.triangles,
                    rigPending = false,
                    rigError = null,
                )
                withContext(Dispatchers.IO) { store.add(updated, result.glb) }
                refresh()
                if (activePet?.id == updated.id) activePet = updated
                status = "动作已生成"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clipError = error.message ?: "动作生成失败"
            } finally {
                clipJob = null
                clipStage = ""
                clipProgress = 0f
            }
        }
    }

    /** Creation needs a key before the camera/gallery opens, so a shot is never thrown away. */
    fun requireApiKey(): Boolean {
        if (settings.apiKey.isNotBlank()) return true
        status = "请先在设置中填写 Lux3D API Key"
        screen = Screen.Settings
        return false
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

    fun renamePet(record: PetRecord, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        store.rename(record.id, name)
        if (activePet?.id == record.id) {
            activePet = store.list().firstOrNull { it.id == record.id }
        }
        refresh()
    }

    fun addBond(record: PetRecord, amount: Int = 1) {
        store.addBond(record.id, amount)
        activePet = store.list().firstOrNull { it.id == record.id }
        refresh()
    }

    /** Text prompt from the home capsule: starts a generation task immediately. */
    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.length < 2 || clean.length > 200) {
            status = "文字描述需要 2 到 200 个字"
            return
        }
        if (!requireApiKey()) return
        startTask(
            key = settings.apiKey,
            taskName = clean.take(14),
            inputKind = "text",
            prompt = clean,
        )
    }

    /** Photo / gallery input: stylize, generate reference views, then image-to-3D and rig. */
    fun submitImage(bytes: ByteArray, inputKind: String) {
        if (bytes.isEmpty()) {
            status = "这张图片读不出来"
            return
        }
        if (!requireApiKey()) return
        startTask(
            key = settings.apiKey,
            taskName = if (inputKind == "photo") "拍照手办" else "相册手办",
            inputKind = inputKind,
            sourceBytes = listOf(bytes),
        )
    }

    /** In-app scan frames: move the JPEGs into task storage so the task can be retried. */
    fun submitScan(paths: List<String>) {
        val files = paths.map(::File).filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) {
            status = "先拍几帧再提交"
            return
        }
        if (!requireApiKey()) return
        startTask(
            key = settings.apiKey,
            taskName = "扫描手办",
            inputKind = "scan",
            sourceFiles = files,
        )
    }

    /** Reads a picked/captured URI off the main thread, then starts the same image pipeline. */
    fun submitImageUri(uri: android.net.Uri, inputKind: String) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                status = "读不了这张图片，换一张试试"
            } else {
                submitImage(bytes, inputKind)
            }
        }
    }

    /**
     * Every task owns its source files under filesDir/tasks/<id>/, so a failed run can be
     * regenerated later without asking the user to shoot/upload again.
     */
    private fun startTask(
        key: String,
        taskName: String,
        inputKind: String,
        prompt: String? = null,
        sourceBytes: List<ByteArray> = emptyList(),
        sourceFiles: List<File> = emptyList(),
    ) {
        val taskId = UUID.randomUUID().toString()
        val sourceNames = when {
            sourceBytes.isNotEmpty() -> sourceBytes.indices.map { "source_$it.jpg" }
            sourceFiles.isNotEmpty() -> sourceFiles.indices.map { "source_$it.jpg" }
            else -> emptyList()
        }
        tasks = tasks + GenerationTask(
            id = taskId,
            name = taskName,
            inputKind = inputKind,
            createdAtUtc = Instant.now().toString(),
            status = TaskStatus.RUNNING,
            stage = "正在准备",
            progress = 0.01f,
            error = null,
            prompt = prompt,
            sources = sourceNames,
            startedAtMs = System.currentTimeMillis(),
        )
        persistTasks()
        status = ""
        screen = Screen.Manage
        launchTask(taskId, key, sourceBytes, sourceFiles)
    }

    fun retryTask(task: GenerationTask) {
        if (jobs.containsKey(task.id)) return
        if (!requireApiKey()) return
        val hasSource = when {
            task.inputKind == "text" -> !task.prompt.isNullOrBlank()
            else -> task.sources.isNotEmpty()
        }
        if (!hasSource) {
            status = "没有找到原始素材，请重新制作"
            return
        }
        updateTask(task.id) {
            it.copy(
                status = TaskStatus.RUNNING,
                stage = "重新生成",
                progress = 0.01f,
                error = null,
                startedAtMs = System.currentTimeMillis(),
                durationMs = 0L,
            )
        }
        launchTask(task.id, settings.apiKey)
    }

    fun cancelTask(task: GenerationTask) {
        val job = jobs.remove(task.id)
        updateKeepAlive()
        if (job != null) {
            job.cancel()
        } else {
            updateTask(task.id) {
                if (it.status == TaskStatus.RUNNING) {
                    it.copy(
                        status = TaskStatus.CANCELLED,
                        stage = "已取消",
                        error = "任务已取消",
                        durationMs = System.currentTimeMillis() - it.startedAtMs,
                    )
                } else {
                    it
                }
            }
        }
    }

    fun removeTask(task: GenerationTask) {
        jobs.remove(task.id)?.cancel()
        updateKeepAlive()
        tasks = tasks.filterNot { it.id == task.id }
        persistTasks()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { taskDir(task.id).deleteRecursively() }
        }
    }

    /** Keeps the process alive while any Lux3D job is running, even if the UI is backgrounded. */
    private fun updateKeepAlive() {
        val app = getApplication<Application>()
        val intent = Intent(app, GenerationService::class.java)
        if (jobs.isEmpty()) {
            app.stopService(intent)
        } else {
            runCatching { ContextCompat.startForegroundService(app, intent) }
        }
    }

    private fun taskDir(taskId: String): File =
        File(getApplication<Application>().filesDir, "tasks/$taskId")

    private fun launchTask(
        taskId: String,
        key: String,
        newSourceBytes: List<ByteArray> = emptyList(),
        newSourceFiles: List<File> = emptyList(),
    ) {
        if (jobs.containsKey(taskId)) return
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        jobs[taskId] = viewModelScope.launch {
            // The static model is a usable figure: persist it the moment image-to-3D finishes,
            // then keep the rig/walk pass running in the background and upgrade the same record.
            var landed: PetRecord? = null
            val onModelReady: suspend (GenerateResult) -> Unit = { static ->
                val record = PetRecord(
                    id = UUID.randomUUID().toString(),
                    name = task.name,
                    inputKind = static.inputKind,
                    source = "lux3d",
                    createdAtUtc = Instant.now().toString(),
                    bond = 0,
                    triangles = static.stats.triangles,
                    modelUrl = static.modelUrl,
                    rigPending = true,
                )
                withContext(Dispatchers.IO) { store.add(record, static.glb) }
                landed = record
                refresh()
                activePet = record
                updateTask(taskId) {
                    it.copy(stage = "模型已保存，正在后台生成走路动作", progress = 0.64f)
                }
            }
            try {
                val payload = withContext(Dispatchers.IO) {
                    val dir = taskDir(taskId).apply { mkdirs() }
                    when {
                        newSourceBytes.isNotEmpty() -> newSourceBytes.forEachIndexed { index, bytes ->
                            File(dir, "source_$index.jpg").writeBytes(bytes)
                        }
                        newSourceFiles.isNotEmpty() -> newSourceFiles.forEachIndexed { index, source ->
                            val target = File(dir, "source_$index.jpg")
                            if (source.absolutePath != target.absolutePath) {
                                if (!source.renameTo(target)) {
                                    source.copyTo(target, overwrite = true)
                                    source.delete()
                                }
                            }
                        }
                    }
                    buildPayload(task, dir)
                } ?: throw IllegalStateException("没有找到原始素材，请重新制作")

                val pipeline = Lux3dPipeline(Lux3dClient(key, faceCount = settings.faceCount))
                val onStage: (String, Float) -> Unit = { stage, progress ->
                    updateTask(taskId) { it.copy(stage = stage, progress = progress) }
                }
                val result = when (payload) {
                    is TaskPayload.Text -> pipeline.generate(
                        CreateInput.Text(payload.prompt),
                        stylize = false,
                        onStage = onStage,
                        onModelReady = onModelReady,
                    )
                    is TaskPayload.Images -> {
                        val frames = withContext(Dispatchers.IO) {
                            payload.files.map { it.readBytes() }
                        }
                        if (frames.size == 1) {
                            pipeline.generate(
                                CreateInput.Photo(frames.first()),
                                stylize = true,
                                onStage = onStage,
                                onModelReady = onModelReady,
                                multiView = settings.autoMultiView,
                            )
                        } else {
                            pipeline.generateFromFrames(frames, onStage, onModelReady)
                        }
                    }
                }

                val generationMs = System.currentTimeMillis() - task.startedAtMs
                val base = landed
                val record = if (base != null) {
                    base.copy(
                        triangles = result.stats.triangles,
                        rigUrl = result.rigUrl,
                        clips = result.clips,
                        rigPending = false,
                        rigError = null,
                        generationMs = generationMs,
                    )
                } else {
                    PetRecord(
                        id = UUID.randomUUID().toString(),
                        name = task.name,
                        inputKind = result.inputKind,
                        source = "lux3d",
                        createdAtUtc = Instant.now().toString(),
                        bond = 0,
                        triangles = result.stats.triangles,
                        modelUrl = result.modelUrl.ifBlank { null },
                        rigUrl = result.rigUrl,
                        clips = result.clips,
                        generationMs = generationMs,
                    )
                }
                // The GLB is 15–20 MB; keep the frame thread free while it lands on disk.
                withContext(Dispatchers.IO) { store.add(record, result.glb) }
                tasks = tasks.filterNot { it.id == taskId }
                persistTasks()
                withContext(Dispatchers.IO) { runCatching { taskDir(taskId).deleteRecursively() } }
                refresh()
                activePet = record
            } catch (error: CancellationException) {
                landed?.let { pet ->
                    val updated = pet.copy(rigPending = false, rigError = "动作生成已取消")
                    withContext(NonCancellable + Dispatchers.IO) { store.add(updated, null) }
                    refresh()
                }
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.CANCELLED,
                        stage = "已取消",
                        error = "任务已取消",
                        durationMs = System.currentTimeMillis() - it.startedAtMs,
                    )
                }
                throw error
            } catch (error: Exception) {
                // The figure is already in the collection; keep it as a static model and surface
                // the animation failure instead of losing the generation.
                landed?.let { pet ->
                    val updated = pet.copy(
                        rigPending = false,
                        rigError = error.message ?: "动作生成失败",
                    )
                    withContext(Dispatchers.IO) { store.add(updated, null) }
                    refresh()
                }
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.FAILED,
                        stage = "生成失败",
                        error = error.message ?: "生成失败，请稍后重试",
                        durationMs = System.currentTimeMillis() - it.startedAtMs,
                    )
                }
            } finally {
                jobs.remove(taskId)
                updateKeepAlive()
            }
        }
        updateKeepAlive()
    }

    private fun buildPayload(task: GenerationTask, dir: File): TaskPayload? = when {
        task.inputKind == "text" -> task.prompt?.let(TaskPayload::Text)
        task.sources.isNotEmpty() -> {
            val files = task.sources
                .map { File(dir, it) }
                .filter { it.exists() && it.length() > 0 }
            if (files.isEmpty()) null else TaskPayload.Images(files)
        }
        else -> null
    }

    private fun updateTask(id: String, transform: (GenerationTask) -> GenerationTask) {
        tasks = tasks.map { if (it.id == id) transform(it) else it }
        persistTasks()
    }

    private fun persistTasks() {
        taskStore.writeAll(tasks)
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
        const val SAMPLE_ID = "built-in-mengmeng-v14"
        private const val LEGACY_SAMPLE_ID = "built-in-sample"
        private const val LEGACY_SAMPLE_ID_2 = "built-in-mengmeng"
        private const val LEGACY_SAMPLE_ID_3 = "built-in-mengmeng-v2"
        private const val LEGACY_SAMPLE_ID_4 = "built-in-mengmeng-v3"
        private const val LEGACY_SAMPLE_ID_5 = "built-in-mengmeng-v4"
        private const val LEGACY_SAMPLE_ID_6 = "built-in-mengmeng-v5"
        private const val LEGACY_SAMPLE_ID_7 = "built-in-mengmeng-v6"
        private const val LEGACY_SAMPLE_ID_8 = "built-in-mengmeng-v7"
        private const val LEGACY_SAMPLE_ID_9 = "built-in-mengmeng-v8"
        private const val LEGACY_SAMPLE_ID_10 = "built-in-mengmeng-v9"
        private const val LEGACY_SAMPLE_ID_11 = "built-in-mengmeng-v10"
        private const val LEGACY_SAMPLE_ID_13 = "built-in-mengmeng-v11"
        private const val LEGACY_SAMPLE_ID_14 = "built-in-mengmeng-v12"
        private const val LEGACY_SAMPLE_ID_12 = "built-in-mengmeng-v11"
    }
}
