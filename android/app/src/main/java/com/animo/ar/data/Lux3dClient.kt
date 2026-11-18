package com.animo.ar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Lux3dException(
    message: String,
    val status: Int = 0,
    val code: String = "",
    val retryable: Boolean = false,
) : IOException(message)

data class RetargetAction(
    val animationId: String?,
    val status: String,
    val url: String?,
    val ok: Boolean,
)

/**
 * Direct client for the Lux3D OpenAPI. The user's own key is sent raw in the Authorization
 * header; there is no Animo backend in between.
 */
class Lux3dClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.aholo3d.cn",
) {
    init {
        if (apiKey.isBlank()) throw Lux3dException("请先在设置中填写 Lux3D API Key")
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val imageMedia = "image/jpeg".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val downloadClient = client.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** A cheap authenticated call used by the settings screen to validate the key. */
    suspend fun checkKey() = withContext(Dispatchers.IO) {
        api("/asset/v1/token")
        Unit
    }

    suspend fun uploadImage(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val grant = api("/asset/v1/token")
        val data = grant.optJSONObject("d") ?: grant
        val token = data.optString("ousToken")
        val domain = data.optString("globalDomain").trimEnd('/')
        val blockSize = data.optLong("blockSize", 0L)
        if (token.isBlank() || !domain.startsWith("https://") || blockSize <= 0L) {
            throw Lux3dException("Lux3D 没有返回有效的上传凭证")
        }

        val md5 = md5(bytes)
        val blockBytes = blockSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (bytes.size <= blockBytes) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("md5", md5)
                .addFormDataPart("file", "animo.jpg", bytes.toRequestBody(imageMedia))
                .build()
            ous("$domain/ous/api/v2/single/upload", token, "POST", body)
        } else {
            val blocks = (bytes.size + blockBytes - 1) / blockBytes
            val initUrl = (domain + "/ous/api/v2/block/upload/init").toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("md5", md5)
                ?.addQueryParameter("blocks", blocks.toString())
                ?.addQueryParameter("size", bytes.size.toString())
                ?.addQueryParameter("name", "animo.jpg")
                ?.build()
                ?: throw Lux3dException("Lux3D 上传地址无效")
            val init = ous(initUrl.toString(), token, "POST")
            if (init?.optBoolean("deduplicated") != true) {
                for (index in 0 until blocks) {
                    val start = index * blockBytes
                    val end = minOf(bytes.size, start + blockBytes)
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("block", (index + 1).toString())
                        .addFormDataPart("file", "animo.jpg", bytes.copyOfRange(start, end).toRequestBody(imageMedia))
                        .build()
                    ous("$domain/ous/api/v2/block/upload/part", token, "POST", body)
                }
            }
        }
        waitForUpload(domain, token)
    }

    suspend fun createImageTo3d(imageUrl: String): String = withContext(Dispatchers.IO) {
        create(
            "/lux3d/v1/generate/img-to-3d/task/create",
            baseTaskBody().put("img", imageUrl),
        )
    }

    suspend fun createImageTo3dFromViews(imageUrls: List<String>): String = withContext(Dispatchers.IO) {
        val images = JSONArray().apply { imageUrls.forEach { put(it) } }
        create(
            "/lux3d/v1/generate/img-to-3d/task/create",
            baseTaskBody().put("imgs", images),
        )
    }

    suspend fun createFourView(imageUrl: String, prompt: String): String = withContext(Dispatchers.IO) {
        create(
            "/lux3d/v1/generate/image-to-four-view/task/create",
            JSONObject().put("img", imageUrl).put("prompt", prompt),
        )
    }

    suspend fun createMultimodalToImage(imageUrl: String, prompt: String): String = withContext(Dispatchers.IO) {
        create(
            "/lux3d/v1/generate/multimodal-to-image/task/create",
            JSONObject().put("img", imageUrl).put("prompt", prompt),
        )
    }

    suspend fun createTextTo3d(prompt: String): String = withContext(Dispatchers.IO) {
        create(
            "/lux3d/v1/generate/text-to-3d/task/create",
            baseTaskBody().put("prompt", prompt).put("style", "cartoon"),
        )
    }

    suspend fun createRig(modelUrl: String): String = withContext(Dispatchers.IO) {
        create("/lux3d/v1/animations/rig/task/create", JSONObject().put("modelUrl", modelUrl))
    }

    suspend fun createRetarget(rigModelUrl: String, animationIds: List<String>): String = withContext(Dispatchers.IO) {
        create(
            "/lux3d/v1/animations/retarget/task/create",
            JSONObject()
                .put("rigModelUrl", rigModelUrl)
                .put("animationIds", JSONArray().apply { animationIds.forEach { put(it) } })
                .put("outFormat", "glb")
                .put("animationOutputMode", "combined")
                .put("animateInPlace", true),
        )
    }

    suspend fun getTask(taskId: String): JSONObject = withContext(Dispatchers.IO) {
        val payload = api("/lux3d/v1/generate/task/get?taskid=${URLEncoder.encode(taskId, "UTF-8")}")
        payload.optJSONObject("d") ?: throw Lux3dException("Lux3D 任务状态为空")
    }

    suspend fun downloadModel(url: String): ByteArray = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw Lux3dException("模型地址无效")
        val request = Request.Builder().url(httpUrl).build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (error: IOException) {
            throw Lux3dException("模型下载失败，请检查网络", retryable = true)
        }
        response.use {
            if (!it.isSuccessful) {
                throw Lux3dException("模型下载失败（HTTP ${it.code}）", it.code, retryable = it.code >= 500)
            }
            val length = it.header("Content-Length")?.toLongOrNull() ?: -1L
            if (length > MAX_MODEL_BYTES) throw Lux3dException("模型文件过大，已放弃下载")
            val bytes = it.body?.bytes() ?: throw Lux3dException("模型内容为空")
            if (bytes.size > MAX_MODEL_BYTES) throw Lux3dException("模型文件过大，已放弃下载")
            bytes
        }
    }

    private fun baseTaskBody(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("faceCount", FACE_COUNT)
        .put("outputFormat", JSONArray().put("glb"))
        .put("enablePbr", true)

    private fun create(path: String, body: JSONObject): String = taskId(api(path, "POST", body))

    private fun api(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", apiKey)
        val request = when {
            body != null -> builder.method(method, body.toString().toRequestBody(jsonMedia)).build()
            method == "POST" -> builder.post(ByteArray(0).toRequestBody(null)).build()
            else -> builder.get().build()
        }
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw Lux3dException("连接 Lux3D 失败，请检查网络后重试", code = "NETWORK", retryable = true)
        }
        response.use { http ->
            val text = http.body?.string().orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse {
                throw Lux3dException(
                    "Lux3D 返回了无法识别的内容（HTTP ${http.code}）",
                    status = http.code,
                    retryable = http.code >= 500,
                )
            }
            val c = payload.opt("c")
            if (!http.isSuccessful || (c != null && c.toString() != "0")) {
                throw failure(payload, http.code)
            }
            return payload
        }
    }

    private fun ous(
        url: String,
        token: String,
        method: String = "GET",
        body: RequestBody? = null,
    ): JSONObject? {
        val builder = Request.Builder()
            .url(url)
            .header("ous-token-v2", token)
        val request = when {
            body != null -> builder.method(method, body).build()
            method == "POST" -> builder.post(ByteArray(0).toRequestBody(null)).build()
            else -> builder.get().build()
        }
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw Lux3dException("上传图片失败，请检查网络", code = "NETWORK", retryable = true)
        }
        response.use { http ->
            val text = http.body?.string().orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse {
                throw Lux3dException(
                    "上传服务返回了无法识别的内容（HTTP ${http.code}）",
                    status = http.code,
                    retryable = http.code >= 500,
                )
            }
            if (!http.isSuccessful || payload.opt("c")?.toString() != "0") {
                throw failure(payload, http.code)
            }
            return payload.optJSONObject("d")
        }
    }

    private suspend fun waitForUpload(domain: String, token: String): String {
        repeat(100) {
            val detail = ous("$domain/ous/api/v2/upload/status", token)
                ?: throw Lux3dException("上传服务没有返回状态")
            when (detail.optInt("status", -1)) {
                5 -> {
                    val url = detail.optString("url")
                    if (url.isBlank()) throw Lux3dException("图片上传完成，但没有拿到图片地址")
                    return url
                }
                6, 8 -> throw uploadFailure(detail)
            }
            delay(300)
        }
        throw Lux3dException("图片上传超时，请重试", retryable = true)
    }

    private fun uploadFailure(detail: JSONObject): Lux3dException {
        val code = detail.optInt("errorCode", -1)
        val message = when (code) {
            2 -> "图片文件大小不合适，请换一张再试"
            3 -> "图片格式不支持，请使用 JPG 或 PNG"
            4 -> "图片内容无法识别，请换一张再试"
            5 -> "这张图片没有通过内容安全检测，请换一张再试"
            6, 9, 10 -> "上传服务繁忙，请稍后再试"
            else -> "图片上传失败，请重试"
        }
        return Lux3dException(message, code = "UPLOAD_$code", retryable = code in setOf(6, 9, 10))
    }

    private fun taskId(payload: JSONObject): String {
        val data = payload.opt("d") ?: throw Lux3dException("Lux3D 没有返回任务编号")
        val id = when (data) {
            is String -> data
            is JSONObject -> data.optString("taskid").ifBlank { data.optString("taskId") }
            is Number -> data.toString()
            else -> ""
        }
        if (id.isBlank()) throw Lux3dException("Lux3D 没有返回任务编号")
        return id
    }

    private fun failure(payload: JSONObject, status: Int): Lux3dException {
        val code = payload.optString("c").ifBlank { payload.optString("code") }.ifBlank { status.toString() }
        var message = payload.optString("m").ifBlank { payload.optString("message") }
        if (message.isBlank()) message = "Lux3D 请求失败（HTTP $status）"
        if (status == 401 || status == 403) {
            return Lux3dException("Lux3D API Key 无效或没有权限，请在设置中检查", status, code)
        }
        val lower = "$code $message".lowercase()
        if (lower.contains("riggable") || lower.contains("not_a_humanoid")) {
            return Lux3dException("这个角色无法自动绑骨。请上传正面全身、双臂水平张开的 A-Pose / T-Pose 人形角色图。", status, code)
        }
        return Lux3dException(
            message,
            status,
            code,
            retryable = status == 429 || status >= 500 || lower.contains("concurrency"),
        )
    }

    companion object {
        const val VERSION = "G1-Turbo"
        const val FACE_COUNT = 30_000
        internal val SUCCESS_STATUS = setOf("success", "succeeded", "completed", "done")
        private const val MAX_MODEL_BYTES = 100L * 1024 * 1024

        private fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

fun taskOutputs(task: JSONObject): JSONArray? = task.optJSONArray("outputs")

fun pickGlbUrl(outputs: JSONArray?): String? {
    val urls = outputs.asContentStrings().filter { it.startsWith("http", true) }
    return urls.firstOrNull { it.substringBefore('?').endsWith(".glb", true) }
        ?: urls.singleOrNull()
}

fun pickImageUrl(outputs: JSONArray?): String? =
    outputs.asContentStrings().firstOrNull { it.startsWith("http", true) }

fun pickFourViewUrls(outputs: JSONArray?): List<String>? {
    val raw = outputs.asContentStrings().firstOrNull { it.trimStart().startsWith("[") } ?: return null
    return runCatching {
        val array = JSONArray(raw)
        val urls = (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { value -> value.startsWith("http", true) } }
        urls.takeIf { it.size == 4 }
    }.getOrNull()
}

fun parseRetargetActions(outputs: JSONArray?): List<RetargetAction>? {
    val raw = outputs.asContentStrings().firstOrNull { it.trimStart().startsWith("{") } ?: return null
    return runCatching {
        val actions = JSONObject(raw).optJSONArray("actions") ?: return null
        (0 until actions.length()).mapNotNull { index ->
            val action = actions.optJSONObject(index) ?: return@mapNotNull null
            val animationId = action.optString("animation_id").ifBlank {
                action.optString("animationId").ifBlank { action.optString("id") }
            }.ifBlank { null }
            val status = action.optString("status")
            val url = listOf(
                "animationGlbUrl", "animation_glb_url", "glbUrl", "glb_url",
                "url", "fileUrl", "file_url",
            ).firstNotNullOfOrNull { key ->
                action.optString(key).takeIf { it.startsWith("http", true) }
            }
            RetargetAction(
                animationId = animationId,
                status = status,
                url = url,
                ok = status.lowercase() in Lux3dClient.SUCCESS_STATUS && url != null,
            )
        }
    }.getOrNull()
}

private fun JSONArray?.asContentStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.optString("content")?.takeIf { it.isNotBlank() }
    }
}
