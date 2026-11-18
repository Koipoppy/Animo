package com.animo.ar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

sealed interface CreateInput {
    data class Photo(val bytes: ByteArray) : CreateInput
    data class Link(val url: String) : CreateInput
    data class Text(val prompt: String) : CreateInput
}

data class GenerateResult(
    val glb: ByteArray,
    val stats: GlbStats,
    val inputKind: String,
    val views: Boolean,
    val modelUrl: String = "",
    val rigUrl: String? = null,
    val clips: List<String> = emptyList(),
)

/**
 * Humanoid-only generation pipeline: normalize -> upload -> (restyle + four views) -> image-to-3D
 * -> Lux3D rig -> retarget five preset clips -> download. No local Blender fallback: a model that
 * cannot be rigged is rejected with a T-Pose / A-Pose hint instead of shipping a static mesh.
 */
class Lux3dPipeline(private val client: Lux3dClient) {

    suspend fun generate(
        input: CreateInput,
        stylize: Boolean,
        onStage: (String, Float) -> Unit,
        /** Called as soon as the static 3D model exists, before rig/retarget. */
        onModelReady: (suspend (GenerateResult) -> Unit)? = null,
        /** Generate the four reference views first; off = faster single-image path. */
        multiView: Boolean = true,
    ): GenerateResult {
        var views = false
        val taskId: String
        val inputKind: String

        when (input) {
            is CreateInput.Photo -> {
                inputKind = "photo"
                val (id, usedViews) = imageTask(input.bytes, stylize, multiView, onStage)
                taskId = id
                views = usedViews
            }
            is CreateInput.Link -> {
                inputKind = "url"
                onStage("正在获取网络图片", 0.02f)
                val bytes = RemoteImage.download(input.url)
                val (id, usedViews) = imageTask(bytes, stylize, multiView, onStage)
                taskId = id
                views = usedViews
            }
            is CreateInput.Text -> {
                inputKind = "text"
                onStage("正在根据描述制作手办", 0.2f)
                taskId = withRetry { client.createTextTo3d(humanoidTextPrompt(input.prompt)) }
            }
        }

        onStage(
            if (views) "正在用四个视角生成 3D 模型" else "正在生成 3D 模型",
            if (views) 0.36f else 0.2f,
        )
        val outputs = waitTask(
            taskId,
            "生成失败",
            if (views) 0.36f else 0.2f,
            GENERATION_CAP,
            onStage,
            GENERATION_TIMEOUT_MS,
        )
        val modelUrl = pickGlbUrl(outputs)
            ?: throw Lux3dException("生成完成了，但没有拿到模型文件，请重试")

        landStaticModel(modelUrl, inputKind, views, onStage, onModelReady)
        return rigAnimateAndDownload(modelUrl, inputKind, views, onStage)
    }

    /**
     * In-app scan path: Lux3D accepts several photos of the same subject in `imgs`. Each frame is
     * normalized and uploaded, then one multi-image task reconstructs the model; rig and retarget
     * are shared with the single-image path.
     */
    suspend fun generateFromFrames(
        frames: List<ByteArray>,
        onStage: (String, Float) -> Unit,
        onModelReady: (suspend (GenerateResult) -> Unit)? = null,
    ): GenerateResult {
        if (frames.isEmpty()) throw Lux3dException("没有可用的扫描帧")
        // Frames are independent uploads; three at a time keeps the link busy without flooding
        // OUS. This is the single biggest wall-clock saving for a 8–12 frame scan.
        val uploads = arrayOfNulls<String>(frames.size)
        val gate = Semaphore(3)
        coroutineScope {
            frames.mapIndexed { index, frame ->
                async {
                    gate.withPermit {
                        val normalized = withContext(Dispatchers.Default) { ImageTools.normalize(frame) }
                        uploads[index] = withRetry { client.uploadImage(normalized) }
                        val done = uploads.count { it != null }
                        onStage(
                            "正在上传底片 $done/${frames.size}",
                            0.04f + 0.14f * done / frames.size,
                        )
                    }
                }
            }.forEach { it.await() }
        }
        val urls = uploads.mapNotNull { it }
        if (urls.size != frames.size) throw Lux3dException("有扫描帧上传失败，请重试")
        onStage("正在用 ${urls.size} 帧重建手办", 0.2f)
        val taskId = withRetry { client.createImageTo3dFromViews(urls) }
        val outputs = waitTask(
            taskId,
            "生成失败",
            0.2f,
            GENERATION_CAP,
            onStage,
            GENERATION_TIMEOUT_MS,
        )
        val modelUrl = pickGlbUrl(outputs)
            ?: throw Lux3dException("生成完成了，但没有拿到模型文件，请重试")
        landStaticModel(modelUrl, "scan", true, onStage, onModelReady)
        return rigAnimateAndDownload(modelUrl, inputKind = "scan", views = true, onStage = onStage)
    }

    /** Download the unrigged model and hand it to the caller before rigging starts. */
    private suspend fun landStaticModel(
        modelUrl: String,
        inputKind: String,
        views: Boolean,
        onStage: (String, Float) -> Unit,
        onModelReady: (suspend (GenerateResult) -> Unit)?,
    ) {
        if (onModelReady == null) return
        onStage("正在保存手办", 0.63f)
        val bytes = sanitizeGlb(withRetry { client.downloadModel(modelUrl) })
        val stats = inspectGlb(bytes) ?: throw Lux3dException("下载到的文件不是有效 GLB")
        onModelReady(
            GenerateResult(
                glb = bytes,
                stats = stats,
                inputKind = inputKind,
                views = views,
                modelUrl = modelUrl,
            )
        )
    }

    private suspend fun rigAnimateAndDownload(
        modelUrl: String,
        inputKind: String,
        views: Boolean,
        onStage: (String, Float) -> Unit,
    ): GenerateResult {
        val rigUrl = rigModel(modelUrl, onStage)
        return retargetAndDownload(rigUrl, BASE_CLIPS, inputKind, views, modelUrl, onStage)
    }

    /** Rig only; public so a model whose background rig failed can be retried on demand. */
    suspend fun rigModel(modelUrl: String, onStage: (String, Float) -> Unit): String {
        onStage("正在让手办动起来", RIG_START)
        val rigTask = withRetry { client.createRig(modelUrl) }
        return pickGlbUrl(
            waitTask(rigTask, "动作准备失败", RIG_START, RIG_CAP, onStage, RIG_TIMEOUT_MS),
        ) ?: throw Lux3dException("动作准备没有拿到模型，请重试")
    }

    /** On-demand retarget against an existing rig; clips are the accumulated ids. */
    suspend fun retarget(
        rigUrl: String,
        clips: List<String>,
        onStage: (String, Float) -> Unit,
    ): GenerateResult = retargetAndDownload(
        rigUrl = rigUrl,
        clips = clips,
        inputKind = "clip",
        views = false,
        modelUrl = "",
        onStage = onStage,
    )

    private suspend fun retargetAndDownload(
        rigUrl: String,
        clips: List<String>,
        inputKind: String,
        views: Boolean,
        modelUrl: String,
        onStage: (String, Float) -> Unit,
    ): GenerateResult {
        onStage("正在准备动作", RETARGET_START)
        val retargetTask = withRetry { client.createRetarget(rigUrl, clips) }
        val actions = parseRetargetActions(
            waitTask(retargetTask, "动作套用失败", RETARGET_START, RETARGET_CAP, onStage, RIG_TIMEOUT_MS),
        ) ?: throw Lux3dException("没有拿到可用的动作，请重试")
        val successful = actions.filter { it.ok }
        if (successful.isEmpty()) throw Lux3dException("动作没有生成成功，换一张正面站立、双手张开的全身照再试一次")

        onStage("正在下载手办", 0.94f)
        val raw = sanitizeGlb(withRetry { client.downloadModel(successful.first().url!!) })
        val names = successful.mapNotNull { it.animationId }
        var glb = renameGlbAnimations(raw, names)
        var stats = inspectGlb(glb) ?: throw Lux3dException("下载到的文件不是有效 GLB")

        // Some retarget responses include fewer clips than requested; if the JSON rename did not
        // apply, fall back to request order so Idle/Walk are still playable by name.
        if (stats.animations > 0 && stats.animationNames.none { it.contains("idle", true) }) {
            val fallback = clips.take(stats.animations)
            if (fallback.size == stats.animations) {
                val alt = renameGlbAnimations(raw, fallback)
                if (alt !== raw) {
                    glb = alt
                    stats = inspectGlb(alt) ?: stats
                }
            }
        }

        if (stats.skins < 1 || stats.animations < 1) {
            throw Lux3dException("这次没有生成出能动的模型，换一张正面站立、双手张开的全身照再试一次")
        }

        return GenerateResult(
            glb = glb,
            stats = stats,
            inputKind = inputKind,
            views = views,
            modelUrl = modelUrl,
            rigUrl = rigUrl,
            clips = names.ifEmpty { clips },
        )
    }

    private suspend fun imageTask(
        bytes: ByteArray,
        stylize: Boolean,
        multiView: Boolean,
        onStage: (String, Float) -> Unit,
    ): Pair<String, Boolean> {
        onStage("正在处理图片", 0.04f)
        val normalized = withContext(Dispatchers.Default) { ImageTools.normalize(bytes) }
        onStage("正在上传图片", 0.08f)
        var imageUrl = withRetry { client.uploadImage(normalized) }

        if (!stylize) {
            onStage("正在生成 3D 模型", 0.2f)
            return withRetry { client.createImageTo3d(imageUrl) } to false
        }

        imageUrl = restyle(imageUrl, onStage)
        if (!multiView) {
            onStage("正在生成 3D 模型", 0.2f)
            return withRetry { client.createImageTo3d(imageUrl) } to false
        }
        onStage("正在补全侧面和背面视角", 0.2f)
        val viewUrls = fourViews(imageUrl, onStage)
        if (viewUrls != null) {
            onStage("正在用四个视角生成 3D 模型", 0.36f)
            return withRetry { client.createImageTo3dFromViews(viewUrls) } to true
        }

        onStage("正在生成 3D 模型", 0.2f)
        return withRetry { client.createImageTo3d(imageUrl) } to false
    }

    /** Best-effort: a failed restyle keeps the original image instead of failing the job. */
    private suspend fun restyle(imageUrl: String, onStage: (String, Float) -> Unit): String {
        return try {
            onStage("正在调整手办的站姿", 0.1f)
            val task = withRetry { client.createMultimodalToImage(imageUrl, STYLE_PROMPT) }
            val outputs = waitTask(task, "造型改写失败", 0.1f, 0.19f, onStage, GENERATION_TIMEOUT_MS)
            pickImageUrl(outputs) ?: imageUrl
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("AnimoPipeline", "restyle failed", error)
            imageUrl
        }
    }

    /** Best-effort: without four views the single image still produces a model. */
    private suspend fun fourViews(imageUrl: String, onStage: (String, Float) -> Unit): List<String>? {
        return try {
            val task = withRetry { client.createFourView(imageUrl, FOUR_VIEW_PROMPT) }
            val outputs = waitTask(task, "四视图生成失败", 0.2f, 0.34f, onStage, GENERATION_TIMEOUT_MS)
            pickFourViewUrls(outputs) ?: throw Lux3dException("四视图任务没有返回 4 个地址")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("AnimoPipeline", "four-view failed", error)
            onStage("四视图生成失败，已退回单图", 0.2f)
            null
        }
    }

    private suspend fun waitTask(
        taskId: String,
        failure: String,
        from: Float,
        to: Float,
        onStage: (String, Float) -> Unit,
        timeoutMs: Long,
    ): JSONArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            polls += 1
            val task = client.getTask(taskId)
            when (task.optInt("status", -1)) {
                3 -> return taskOutputs(task) ?: throw Lux3dException("任务完成了，但没有返回结果，请重试")
                4 -> throw taskFailure(task, failure)
                6 -> throw Lux3dException("任务被取消")
            }
            val progress = from + (to - from) * (1 - exp(-polls / 40.0)).toFloat()
            onStage(currentStage(failure, from), progress.coerceIn(from, to))
        }
        throw Lux3dException("生成时间过长，请稍后重试")
    }

    private fun taskFailure(task: JSONObject, fallback: String): Lux3dException =
        Lux3dException(taskFailureMessage(task, fallback))

    private fun currentStage(failure: String, from: Float): String = when {
        from >= RETARGET_START -> "正在准备动作"
        from >= RIG_START -> "正在让手办动起来"
        failure.contains("视角") -> "正在补全侧面和背面视角"
        failure.contains("造型") -> "正在调整手办的站姿"
        else -> "正在生成 3D 模型"
    }

    private suspend fun <T> withRetry(onWait: (() -> Unit)? = null, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Lux3dException) {
                val wait = RETRY_DELAYS_MS.getOrNull(attempt)
                if (!error.retryable || wait == null) throw error
                onWait?.invoke()
                attempt += 1
                delay(wait)
            }
        }
    }

    companion object {
        /** Generated automatically as soon as the model lands. */
        val BASE_CLIPS = listOf("Idle_Loop", "Walk_Loop")

        // Full catalogue exposed in the action menu; the endpoint accepts up to 10 ids per
        // retarget task, and clips can be generated later on demand.
        val ANIMATION_PRESETS = listOf(
            "Idle_Loop",
            "Walk_Loop",
            "Jog_Fwd_Loop",
            "Dance_Loop",
            "Yes",
            "Jump_Start",
            "Sitting_Idle_Loop",
            "Punch_Jab",
            "Sword_Attack",
            "Hit_Chest",
        )

        private const val POLL_INTERVAL_MS = 1_500L
        private const val GENERATION_TIMEOUT_MS = 15 * 60 * 1000L
        private const val RIG_TIMEOUT_MS = 8 * 60 * 1000L
        private const val GENERATION_CAP = 0.62f
        private const val RIG_START = 0.64f
        private const val RIG_CAP = 0.76f
        private const val RETARGET_START = 0.78f
        private const val RETARGET_CAP = 0.9f
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)

        private const val STYLE_PROMPT =
            "把图中的角色变成精致的 3D 手办模型，保持角色原有的比例、发型、服装和配色，完整展示全身，正面略微俯视，纯白背景，单个主体居中；全身人形角色站立，T-Pose，双臂水平向两侧完全伸直、双手张开，双腿与肩同宽分开站稳，不要抱臂或屈肘"

        private const val FOUR_VIEW_PROMPT =
            "保持同一角色、同一套配色和同一姿势；全身人形角色保持 T-Pose：双臂水平向两侧完全伸直、双手张开，双腿与肩同宽分开站稳，不要抱臂或屈肘"

        private fun humanoidTextPrompt(prompt: String): String =
            "$prompt。全身正面站立的人形角色，T-Pose，双臂水平向两侧完全伸直、双手张开，双腿与肩同宽分开站稳，完整展示全身，纯白背景，精致 3D 手办风格。"
    }
}

/** Human-readable message for a failed Lux3D task, including MODEL_NOT_RIGGABLE. */
internal fun taskFailureMessage(task: JSONObject, fallback: String): String {
    val detail = extractTaskError(task)
    val text = if (detail == null) fallback else "$fallback：$detail"
    val lower = text.lowercase()
    if (lower.contains("riggable") || lower.contains("humanoid") ||
        lower.contains("a-pose") || lower.contains("t-pose") || lower.contains("站姿")
    ) {
        return "这个姿势不太适合做成会动的手办。换一张正面站立、双手自然张开的全身照再试一次。" +
            (detail?.let { "（$it）" } ?: "")
    }
    return text
}

/** Task errors arrive either as fields on the task or inside outputs[0].content. */
internal fun extractTaskError(task: JSONObject): String? {
    listOf("errorMessage", "message", "m", "errorCode", "code")
        .asSequence()
        .map { task.optString(it) }
        .firstOrNull { it.isNotBlank() }
        ?.let { return it }

    val outputs = task.optJSONArray("outputs") ?: return null
    for (index in 0 until outputs.length()) {
        val content = outputs.optJSONObject(index)?.optString("content") ?: continue
        val parsed = runCatching { JSONObject(content) }.getOrNull() ?: continue
        val error = parsed.optJSONObject("error") ?: parsed
        val detail = listOf(error.optString("code"), error.optString("message"))
            .filter { it.isNotBlank() }
            .joinToString("：")
        if (detail.isNotBlank()) return detail
    }
    return null
}
