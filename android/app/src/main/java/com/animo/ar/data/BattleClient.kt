package com.animo.ar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A paired battle room; `startAt` is an epoch-ms instant both terminals must begin at. */
data class BattleRoom(
    val code: String,
    val side: String,
    val status: String,
    val startAt: Long?,
    val selfName: String?,
    val peerName: String?,
    val peerHasModel: Boolean,
    val cloudAnchorId: String?,
)

/**
 * Battle rooms over the same backend the app already uses (USB reverse or LAN).
 * Rooms only exchange pet models and one shared start timestamp; the battle itself
 * is deterministic and runs locally on both terminals.
 */
class BattleClient(baseUrl: String, private val secret: String) {

    private val root = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String): Request.Builder =
        Request.Builder().url(root + path).apply {
            if (secret.isNotEmpty()) header("X-Animo-Secret", secret)
        }

    private fun parseRoom(response: okhttp3.Response): BattleRoom {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(readError(text, response.code))
        val json = JSONObject(text)
        val peer = json.optJSONObject("peer")
        return BattleRoom(
            code = json.optString("code"),
            side = json.optString("side", "host"),
            status = json.optString("status", "waiting"),
            startAt = json.optLong("startAt", 0L).takeIf { it > 0L },
            selfName = json.optJSONObject("self")?.optString("name")?.takeIf { it.isNotEmpty() },
            peerName = peer?.optString("name")?.takeIf { it.isNotEmpty() },
            peerHasModel = peer?.optBoolean("hasModel", false) ?: false,
            cloudAnchorId = json.optString("cloudAnchorId").takeIf { it.isNotEmpty() },
        )
    }

    private fun readError(text: String, code: Int): String =
        runCatching { JSONObject(text).optString("message").takeIf { it.isNotEmpty() } }
            .getOrNull() ?: "请求失败（$code）"

    private fun roomBody(name: String, model: ByteArray): MultipartBody =
        MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart(
                "model",
                "pet.glb",
                model.toRequestBody("model/gltf-binary".toMediaType()),
            )
            .build()

    suspend fun create(name: String, model: ByteArray): BattleRoom = withContext(Dispatchers.IO) {
        val request = builder("/api/v1/battles").post(roomBody(name, model)).build()
        client.newCall(request).execute().use { parseRoom(it) }
    }

    suspend fun join(code: String, name: String, model: ByteArray): BattleRoom = withContext(Dispatchers.IO) {
        val request = builder("/api/v1/battles/$code/join").post(roomBody(name, model)).build()
        client.newCall(request).execute().use { parseRoom(it) }
    }

    suspend fun poll(code: String, side: String): BattleRoom = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/v1/battles/$code?side=$side").get().build()).execute().use {
            parseRoom(it)
        }
    }

    suspend fun start(code: String, side: String, leadMs: Long = 2000): BattleRoom = withContext(Dispatchers.IO) {
        val body = JSONObject().put("side", side).put("leadMs", leadMs).toString()
            .toRequestBody("application/json".toMediaType())
        val request = builder("/api/v1/battles/$code/start").post(body).build()
        client.newCall(request).execute().use { parseRoom(it) }
    }

    /** Host-only: publish the Cloud Anchor id so the guest resolves the same physical pose. */
    suspend fun setAnchor(code: String, side: String, cloudAnchorId: String): BattleRoom =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("side", side).put("cloudAnchorId", cloudAnchorId).toString()
                .toRequestBody("application/json".toMediaType())
            val request = builder("/api/v1/battles/$code/anchor").post(body).build()
            client.newCall(request).execute().use { parseRoom(it) }
        }

    /** Downloads the other terminal's pet GLB so both sides can render both pets. */
    suspend fun downloadPeerModel(code: String, peerSide: String): ByteArray = withContext(Dispatchers.IO) {
        val request = builder("/api/v1/battles/$code/model/$peerSide").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("对手精灵下载失败（${response.code}）")
            }
            response.body?.bytes() ?: throw ApiException("对手精灵内容为空")
        }
    }

    suspend fun close(code: String) = withContext(Dispatchers.IO) {
        runCatching { client.newCall(builder("/api/v1/battles/$code").delete().build()).execute().close() }
    }
}
