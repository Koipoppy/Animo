package com.animo.ar.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Generation jobs that have no finished model yet. Persisting them means a task killed with the
 * app is still visible in the collection (as interrupted) instead of silently disappearing.
 */
class TaskStore(context: Context) {

    private val dir = File(context.filesDir, "tasks").apply { mkdirs() }
    private val index = File(dir, "tasks.json")

    @Synchronized
    fun list(): List<GenerationTask> {
        if (!index.exists()) return emptyList()
        return runCatching { GenerationTask.parseList(JSONArray(index.readText())) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun writeAll(tasks: List<GenerationTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        val temp = File(dir, "tasks.json.tmp")
        temp.writeText(array.toString())
        if (index.exists()) index.delete()
        temp.renameTo(index)
    }
}
