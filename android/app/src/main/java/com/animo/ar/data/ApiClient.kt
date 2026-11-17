package com.animo.ar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/**
 * Talks to the Node backend. The Lux3D key never reaches the phone: the app only ever sees job
 * state, a progress number and a model URL.
 */
class ApiClient(baseUrl: String, private val secret: String) {

    private val root = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String): Request.Builder =
        Request.Builder().url(root + path).apply {
            if (secret.isNotEmpty()) header("X-Animo-Secret", secret)
        }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(builder("/health").get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun createFromPhoto(jpeg: ByteArray, name: String, style: String): Job =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("style", style)
                .addFormDataPart(
                    "image", "photo.jpg",
                    jpeg.toRequestBody("image/jpeg".toMediaType())
                )
                .build()
            post("/api/v1/pets", body)
        }

    suspend fun createFromUrl(imageUrl: String, name: String, style: String): Job =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("style", style)
                .addFormDataPart("imageUrl", imageUrl)
                .build()
            post("/api/v1/pets", body)
        }

    suspend fun createFromText(prompt: String, name: String): Job =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("prompt", prompt)
                .build()
            post("/api/v1/pets", body)
        }

    private fun post(path: String, body: MultipartBody): Job {
        val request = builder(path).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(readError(text, response.code))
            return Job.parse(JSONObject(text))
        }
    }

    suspend fun job(id: String): Job = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/v1/pets/$id").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(readError(text, response.code))
            Job.parse(JSONObject(text))
        }
    }

    /** Polls until the job settles; [onUpdate] is called on the caller's dispatcher. */
    suspend fun awaitJob(id: String, onUpdate: suspend (Job) -> Unit): Job {
        val deadline = System.currentTimeMillis() + 20 * 60 * 1000
        var last: Job? = null
        while (System.currentTimeMillis() < deadline) {
            val current = job(id)
            if (current != last) {
                onUpdate(current)
                last = current
            }
            if (current.status.isTerminal) return current
            delay(1200)
        }
        throw ApiException("生成超时，请稍后重试")
    }

    suspend fun downloadModel(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else root + path
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("模型下载失败（${response.code}）")
            response.body?.bytes() ?: throw ApiException("模型内容为空")
        }
    }

    private fun readError(text: String, code: Int): String = runCatching {
        JSONObject(text).optString("error").takeIf { it.isNotEmpty() }
    }.getOrNull() ?: "请求失败（$code）"
}
