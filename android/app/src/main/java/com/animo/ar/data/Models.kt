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
