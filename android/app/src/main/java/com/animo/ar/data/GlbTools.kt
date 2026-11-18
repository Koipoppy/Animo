package com.animo.ar.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Summary of the delivered GLB, shown on the hatching screen. */
data class GlbStats(
    val bytes: Int,
    val triangles: Int,
    val skins: Int,
    val animations: Int,
    val bones: Int,
    val animationNames: List<String>,
)

private const val GLB_MAGIC = 0x46546C67 // "glTF"
private const val JSON_CHUNK = 0x4E4F534A // "JSON"

private data class GlbChunk(val type: Int, val data: ByteArray)

private fun parseChunks(bytes: ByteArray): List<GlbChunk>? {
    if (bytes.size < 20) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.getInt(0) != GLB_MAGIC) return null
    val total = buf.getInt(8)
    if (total < 20 || total > bytes.size) return null
    val chunks = mutableListOf<GlbChunk>()
    var offset = 12
    while (offset + 8 <= total) {
        val length = buf.getInt(offset)
        if (length < 0 || offset + 8 + length > total) return null
        val type = buf.getInt(offset + 4)
        chunks += GlbChunk(type, bytes.copyOfRange(offset + 8, offset + 8 + length))
        offset += 8 + length
    }
    return chunks.ifEmpty { null }
}

private fun jsonObject(bytes: ByteArray): JSONObject? = runCatching {
    val chunk = parseChunks(bytes)?.firstOrNull { it.type == JSON_CHUNK } ?: return null
    JSONObject(String(chunk.data, Charsets.UTF_8).trimEnd())
}.getOrNull()

fun isGlb(bytes: ByteArray): Boolean =
    bytes.size >= 12 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == GLB_MAGIC

fun inspectGlb(bytes: ByteArray): GlbStats? {
    val gltf = jsonObject(bytes) ?: return null
    val accessors = gltf.optJSONArray("accessors") ?: JSONArray()
    var triangles = 0

    val meshes = gltf.optJSONArray("meshes") ?: JSONArray()
    for (meshIndex in 0 until meshes.length()) {
        val primitives = meshes.optJSONObject(meshIndex)?.optJSONArray("primitives") ?: continue
        for (primitiveIndex in 0 until primitives.length()) {
            val primitive = primitives.optJSONObject(primitiveIndex) ?: continue
            val positionIndex = primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
            val position = accessors.optJSONObject(positionIndex)
            val indexCount = if (primitive.has("indices")) {
                accessors.optJSONObject(primitive.optInt("indices"))?.optInt("count", 0) ?: 0
            } else {
                position?.optInt("count", 0) ?: 0
            }
            if ((primitive.optInt("mode", 4)) == 4) triangles += indexCount / 3
        }
    }

    val skins = gltf.optJSONArray("skins") ?: JSONArray()
    var bones = 0
    for (index in 0 until skins.length()) {
        bones += skins.optJSONObject(index)?.optJSONArray("joints")?.length() ?: 0
    }

    val animations = gltf.optJSONArray("animations") ?: JSONArray()
    val names = (0 until animations.length()).map { index ->
        animations.optJSONObject(index)?.optString("name").orEmpty()
    }

    return GlbStats(
        bytes = bytes.size,
        triangles = triangles,
        skins = skins.length(),
        animations = animations.length(),
        bones = bones,
        animationNames = names,
    )
}

/**
 * Lux3D's combined retarget GLB names its clips animation_1..N; the requested preset ids only
 * exist in the task JSON. The Android client plays by clip name, so rewrite the JSON chunk and
 * leave the BIN chunk untouched.
 */
fun renameGlbAnimations(bytes: ByteArray, names: List<String>): ByteArray {
    if (names.isEmpty()) return bytes
    val chunks = parseChunks(bytes) ?: return bytes
    val jsonIndex = chunks.indexOfFirst { it.type == JSON_CHUNK }
    if (jsonIndex < 0) return bytes
    val gltf = runCatching {
        JSONObject(String(chunks[jsonIndex].data, Charsets.UTF_8).trimEnd())
    }.getOrNull() ?: return bytes
    val animations = gltf.optJSONArray("animations") ?: return bytes
    if (animations.length() != names.size) return bytes

    names.forEachIndexed { index, name ->
        animations.optJSONObject(index)?.put("name", name)
    }

    val json = gltf.toString().toByteArray(Charsets.UTF_8)
    val padding = (4 - (json.size % 4)) % 4
    val padded = if (padding == 0) json else json + ByteArray(padding) { 0x20 }

    val out = ByteArrayOutputStream()
    out.write(ByteArray(12))
    chunks.forEachIndexed { index, chunk ->
        val body = if (index == jsonIndex) padded else chunk.data
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(body.size)
            .putInt(chunk.type)
            .array()
        out.write(header)
        out.write(body)
    }
    val result = out.toByteArray()
    val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(GLB_MAGIC)
        .putInt(2)
        .putInt(result.size)
        .array()
    System.arraycopy(header, 0, result, 0, 12)
    return result
}
