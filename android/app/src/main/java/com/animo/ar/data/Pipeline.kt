package com.animo.ar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    ): GenerateResult {
        var views = false
        val taskId: String
        val inputKind: String

        when (input) {
            is CreateInput.Photo -> {
                inputKind = "photo"
                val (id, usedViews) = imageTask(input.bytes, stylize, onStage)
                taskId = id
                views = usedViews
            }
            is CreateInput.Link -> {
                inputKind = "url"
                onStage("正在获取网络图片", 0.02f)
                val bytes = RemoteImage.download(input.url)
                val (id, usedViews) = imageTask(bytes, stylize, onStage)
                taskId = id
                views = usedViews
            }
            is CreateInput.Text -> {
                inputKind = "text"
                onStage("Lux3D 正在根据描述生成人形角色", 0.2f)
                taskId = withRetry { client.createTextTo3d(humanoidTextPrompt(input.prompt)) }
            }
        }

        onStage(
            if (views) "Lux3D 正在按四个视角生成 3D 模型" else "Lux3D 正在生成人形 3D 模型",
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
            ?: throw Lux3dException("Lux3D 已完成，但没有返回 GLB 模型")

        onStage("Lux3D 正在绑定骨骼", RIG_START)
        val rigTask = withRetry { client.createRig(modelUrl) }
        val rigUrl = pickGlbUrl(
            waitTask(rigTask, "骨骼绑定失败", RIG_START, RIG_CAP, onStage, RIG_TIMEOUT_MS),
        ) ?: throw Lux3dException("绑骨任务没有返回模型地址")

        onStage("Lux3D 正在套用动作", RETARGET_START)
        val retargetTask = withRetry { client.createRetarget(rigUrl, ANIMATION_PRESETS) }
        val actions = parseRetargetActions(
            waitTask(retargetTask, "动作套用失败", RETARGET_START, RETARGET_CAP, onStage, RIG_TIMEOUT_MS),
        ) ?: throw Lux3dException("动作任务没有返回可用结果")
        val successful = actions.filter { it.ok }
        if (successful.isEmpty()) throw Lux3dException("动作任务全部失败，请换一张 T-Pose / A-Pose 人形角色图重试")

        onStage("正在下载模型", 0.94f)
        val raw = withRetry { client.downloadModel(successful.first().url!!) }
        val names = successful.mapNotNull { it.animationId }
        var glb = renameGlbAnimations(raw, names)
        var stats = inspectGlb(glb) ?: throw Lux3dException("下载到的文件不是有效 GLB")

        // Some retarget responses include fewer clips than requested; if the JSON rename did not
        // apply, fall back to request order so Idle/Walk are still playable by name.
        if (stats.animations > 0 && stats.animationNames.none { it.contains("idle", true) }) {
            val fallback = ANIMATION_PRESETS.take(stats.animations)
            if (fallback.size == stats.animations) {
                val alt = renameGlbAnimations(raw, fallback)
                if (alt !== raw) {
                    glb = alt
                    stats = inspectGlb(alt) ?: stats
                }
            }
        }

        if (stats.skins < 1 || stats.animations < 1) {
            throw Lux3dException("Lux3D 没有产出带骨骼动画的模型，请换一张 T-Pose / A-Pose 人形角色图重试")
        }

        return GenerateResult(glb = glb, stats = stats, inputKind = inputKind, views = views)
    }

    private suspend fun imageTask(
        bytes: ByteArray,
        stylize: Boolean,
        onStage: (String, Float) -> Unit,
    ): Pair<String, Boolean> {
        onStage("正在处理图片", 0.04f)
        val normalized = withContext(Dispatchers.Default) { ImageTools.normalize(bytes) }
        onStage("正在上传图片", 0.08f)
        var imageUrl = withRetry { client.uploadImage(normalized) }

        if (!stylize) {
            onStage("Lux3D 正在生成人形 3D 模型", 0.2f)
            return withRetry { client.createImageTo3d(imageUrl) } to false
        }

        imageUrl = restyle(imageUrl, onStage)
        onStage("正在补全侧面和背面视角", 0.2f)
        val viewUrls = fourViews(imageUrl, onStage)
        if (viewUrls != null) {
            onStage("Lux3D 正在按四个视角生成 3D 模型", 0.36f)
            return withRetry { client.createImageTo3dFromViews(viewUrls) } to true
        }

        onStage("Lux3D 正在生成人形 3D 模型", 0.2f)
        return withRetry { client.createImageTo3d(imageUrl) } to false
    }

    /** Best-effort: a failed restyle keeps the original image instead of failing the job. */
    private suspend fun restyle(imageUrl: String, onStage: (String, Float) -> Unit): String {
        return try {
            onStage("正在把它做成 T-Pose 手办造型", 0.1f)
            val task = withRetry { client.createMultimodalToImage(imageUrl, STYLE_PROMPT) }
            val outputs = waitTask(task, "造型改写失败", 0.1f, 0.19f, onStage, GENERATION_TIMEOUT_MS)
            pickImageUrl(outputs) ?: imageUrl
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
                3 -> return taskOutputs(task) ?: throw Lux3dException("Lux3D 任务完成但没有返回结果")
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
        from >= RETARGET_START -> "Lux3D 正在套用动作"
        from >= RIG_START -> "Lux3D 正在绑定骨骼"
        failure.contains("视角") -> "正在补全侧面和背面视角"
        failure.contains("造型") -> "正在把它做成 T-Pose 手办造型"
        else -> "Lux3D 正在生成人形 3D 模型"
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
        val ANIMATION_PRESETS = listOf("Idle_Loop", "Walk_Loop", "Punch_Jab", "Hit_Chest", "Death01")

        private const val POLL_INTERVAL_MS = 2_500L
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
        return "这个角色无法自动绑骨。Lux3D 需要正面全身、双臂水平或自然张开的 A-Pose / T-Pose 人形角色图。" +
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
