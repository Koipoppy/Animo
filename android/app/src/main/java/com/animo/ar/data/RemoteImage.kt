package com.animo.ar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads a direct image link, or the preview image of a post/page. */
object RemoteImage {

    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Animo/0.5"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        fetch(url, depth = 0)
    }

    private fun fetch(rawUrl: String, depth: Int): ByteArray {
        if (depth > 2) throw IllegalArgumentException("链接跳转太多次，请改用图片直链")
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("链接要以 https:// 开头")
        if (url.scheme != "https") throw IllegalArgumentException("链接要以 https:// 开头")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*,text/html;q=0.9,*/*;q=0.8")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw IllegalArgumentException("获取图片失败：${error.message ?: "网络错误"}")
        }
        response.use {
            if (!it.isSuccessful) throw IllegalArgumentException("链接打不开（HTTP ${it.code}）")
            val bytes = readLimited(it)
            val type = it.header("Content-Type").orEmpty().lowercase()
            val html = type.contains("text/html") || looksLikeHtml(bytes)
            if (!html) return bytes
            val imageUrl = extractImageUrl(bytes, url.toString())
                ?: throw IllegalArgumentException("这个链接里没有找到配图，请改用图片直链")
            return fetch(imageUrl, depth + 1)
        }
    }

    private fun readLimited(response: okhttp3.Response): ByteArray {
        val declared = response.header("Content-Length")?.toLongOrNull() ?: -1L
        if (declared > MAX_BYTES) throw IllegalArgumentException("图片太大了（上限 12MB）")
        val stream = response.body?.byteStream() ?: throw IllegalArgumentException("链接内容为空")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IllegalArgumentException("图片太大了（上限 12MB）")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<head")
    }

    private fun extractImageUrl(htmlBytes: ByteArray, baseUrl: String): String? {
        val html = String(htmlBytes, Charsets.UTF_8)
        val meta = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        val content = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (tag in meta.findAll(html)) {
            val text = tag.value
            if (!text.contains("og:image", true) && !text.contains("twitter:image", true)) continue
            val href = content.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (href.isEmpty()) continue
            return runCatching {
                java.net.URI(baseUrl).resolve(href).toString()
            }.getOrNull()
        }
        return null
    }
}
