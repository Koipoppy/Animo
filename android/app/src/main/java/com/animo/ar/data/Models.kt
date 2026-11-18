package com.animo.ar.data

import org.json.JSONArray
import org.json.JSONObject

/** Local generation progress shown on the hatching screen. */
data class Job(
    val stage: String,
    val progress: Float,
    val triangles: Int = 0,
    val animations: Int = 0,
    val bones: Int = 0,
    val engine: String = "lux3d",
)

enum class TaskStatus { RUNNING, FAILED, CANCELLED }

/** A generation job that lives in the collection even before (or without) a finished model. */
data class GenerationTask(
    val id: String,
    val name: String,
    val inputKind: String,
    val createdAtUtc: String,
    val status: TaskStatus,
    val stage: String,
    val progress: Float,
    val error: String?,
    /** Text prompt, kept so a failed text task can be regenerated without retyping. */
    val prompt: String? = null,
    /** JPEG file names inside filesDir/tasks/<id>/; kept for image/scan retries. */
    val sources: List<String> = emptyList(),
    val startedAtMs: Long = 0L,
    /** 0 while running; set when the task succeeds, fails or is cancelled. */
    val durationMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("inputKind", inputKind)
        .put("createdAtUtc", createdAtUtc)
        .put("status", status.name)
        .put("stage", stage)
        .put("progress", progress.toDouble())
        .put("error", error.orEmpty())
        .put("prompt", prompt.orEmpty())
        .put("sources", JSONArray().apply { sources.forEach { put(it) } })
        .put("startedAtMs", startedAtMs)
        .put("durationMs", durationMs)

    companion object {
        fun parse(json: JSONObject): GenerationTask = GenerationTask(
            id = json.optString("id"),
            name = json.optString("name"),
            inputKind = json.optString("inputKind", "photo"),
            createdAtUtc = json.optString("createdAtUtc"),
            status = runCatching { TaskStatus.valueOf(json.optString("status", "FAILED")) }
                .getOrDefault(TaskStatus.FAILED),
            stage = json.optString("stage"),
            progress = json.optDouble("progress", 0.0).toFloat(),
            error = json.optString("error").ifBlank { null },
            prompt = json.optString("prompt").ifBlank { null },
            sources = json.optJSONArray("sources")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).ifBlank { null }
                }
            }.orEmpty(),
            startedAtMs = json.optLong("startedAtMs", 0L),
            durationMs = json.optLong("durationMs", 0L),
        )

        fun parseList(array: JSONArray): List<GenerationTask> =
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::parse) }
    }
}

data class PetRecord(
    val id: String,
    val name: String,
    val inputKind: String,
    val source: String,
    val createdAtUtc: String,
    val bond: Int,
    val triangles: Int,
    /** The raw generated model (no rig); kept so rig/animation can be retried later. */
    val modelUrl: String? = null,
    /** The rigged base model URL used for on-demand retargeting. */
    val rigUrl: String? = null,
    /** Clip ids currently baked into the local GLB. */
    val clips: List<String> = emptyList(),
    val rigPending: Boolean = false,
    val rigError: String? = null,
    /** Wall-clock time of the whole generation task, shown in the collection. */
    val generationMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("inputKind", inputKind)
        .put("source", source)
        .put("createdAtUtc", createdAtUtc)
        .put("bond", bond)
        .put("triangles", triangles)
        .put("modelUrl", modelUrl.orEmpty())
        .put("rigUrl", rigUrl.orEmpty())
        .put("clips", JSONArray().apply { clips.forEach { put(it) } })
        .put("rigPending", rigPending)
        .put("rigError", rigError.orEmpty())
        .put("generationMs", generationMs)

    companion object {
        fun parse(json: JSONObject): PetRecord = PetRecord(
            id = json.optString("id"),
            name = json.optString("name"),
            inputKind = json.optString("inputKind", "photo"),
            source = json.optString("source", "lux3d"),
            createdAtUtc = json.optString("createdAtUtc"),
            bond = json.optInt("bond", 0),
            triangles = json.optInt("triangles", 0),
            modelUrl = json.optString("modelUrl").ifBlank { null },
            rigUrl = json.optString("rigUrl").ifBlank { null },
            clips = json.optJSONArray("clips")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).ifBlank { null }
                }
            }.orEmpty(),
            rigPending = json.optBoolean("rigPending", false),
            rigError = json.optString("rigError").ifBlank { null },
            generationMs = json.optLong("generationMs", 0L),
        )

        fun parseList(array: JSONArray): List<PetRecord> =
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::parse) }
    }
}
