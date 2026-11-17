package com.animo.ar.data

import org.json.JSONArray
import org.json.JSONObject

enum class JobStatus {
    Queued, Fetching, Uploading, Styling, Views, Generating, Downloading, Succeeded, Failed;

    val isTerminal get() = this == Succeeded || this == Failed

    companion object {
        fun parse(raw: String?): JobStatus = when (raw?.lowercase()) {
            "queued" -> Queued
            "fetching" -> Fetching
            "uploading" -> Uploading
            "styling" -> Styling
            "views" -> Views
            "generating" -> Generating
            "downloading" -> Downloading
            "succeeded" -> Succeeded
            "failed" -> Failed
            else -> Queued
        }
    }
}

/** Mirrors the public shape of a backend job. */
data class Job(
    val id: String,
    val name: String,
    val inputKind: String,
    val status: JobStatus,
    val stage: String?,
    val progress: Float,
    val source: String?,
    val demo: Boolean,
    val modelUrl: String?,
    val error: String?,
    val triangles: Int,
    val animations: Int,
    val bones: Int,
    val engine: String?,
) {
    companion object {
        fun parse(json: JSONObject): Job = Job(
            id = json.optString("id"),
            name = json.optString("name"),
            inputKind = json.optString("inputKind", "photo"),
            status = JobStatus.parse(json.optString("status")),
            stage = json.optString("stage").takeIf { it.isNotEmpty() },
            progress = json.optDouble("progress", 0.0).toFloat(),
            source = json.optString("source").takeIf { it.isNotEmpty() },
            demo = json.optBoolean("demo", false),
            modelUrl = json.optString("modelUrl").takeIf { it.isNotEmpty() },
            error = json.optString("error").takeIf { it.isNotEmpty() },
            triangles = json.optJSONObject("stats")?.optInt("triangles", 0) ?: 0,
            animations = json.optJSONObject("stats")?.optInt("animations", 0) ?: 0,
            bones = json.optJSONObject("stats")?.optInt("bones", 0) ?: 0,
            engine = json.optJSONObject("stats")?.optString("engine")?.takeIf { it.isNotEmpty() },
        )
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("inputKind", inputKind)
        .put("source", source)
        .put("createdAtUtc", createdAtUtc)
        .put("bond", bond)
        .put("triangles", triangles)

    companion object {
        fun parse(json: JSONObject): PetRecord = PetRecord(
            id = json.optString("id"),
            name = json.optString("name"),
            inputKind = json.optString("inputKind", "photo"),
            source = json.optString("source", "lux3d"),
            createdAtUtc = json.optString("createdAtUtc"),
            bond = json.optInt("bond", 0),
            triangles = json.optInt("triangles", 0),
        )

        fun parseList(array: JSONArray): List<PetRecord> =
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::parse) }
    }
}
