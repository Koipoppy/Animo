package com.animo.ar.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The collection on disk: one index file plus one GLB per pet, so a pet still comes out when the
 * server is unreachable.
 */
class PetStore(context: Context) {

    private val dir = File(context.filesDir, "pets").apply { mkdirs() }
    private val index = File(dir, "pets.json")

    @Synchronized
    fun list(): List<PetRecord> {
        if (!index.exists()) return emptyList()
        return runCatching { PetRecord.parseList(JSONArray(index.readText())) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(record: PetRecord, glb: ByteArray?) {
        if (glb != null && glb.isNotEmpty()) modelFile(record.id).writeBytes(glb)
        val current = list().filterNot { it.id == record.id } + record
        write(current)
    }

    @Synchronized
    fun remove(id: String) {
        modelFile(id).delete()
        write(list().filterNot { it.id == id })
    }

    @Synchronized
    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    @Synchronized
    fun addBond(id: String, amount: Int) = update(id) { it.copy(bond = it.bond + amount) }

    fun modelFile(id: String): File = File(dir, "$id.glb")

    fun hasModel(id: String): Boolean = modelFile(id).let { it.exists() && it.length() > 0 }

    private fun update(id: String, transform: (PetRecord) -> PetRecord) {
        write(list().map { if (it.id == id) transform(it) else it })
    }

    private fun write(records: List<PetRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        // Write then rename, so a crash mid-write cannot corrupt the index.
        val temp = File(dir, "pets.json.tmp")
        temp.writeText(array.toString())
        if (index.exists()) index.delete()
        temp.renameTo(index)
    }
}
