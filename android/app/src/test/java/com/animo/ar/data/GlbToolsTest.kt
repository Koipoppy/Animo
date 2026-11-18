package com.animo.ar.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbToolsTest {

    @Test
    fun `inspects and renames combined retarget clips`() {
        val original = glb(listOf("animation_1", "animation_2"))
        val before = inspectGlb(original)
        assertNotNull(before)
        assertEquals(2, before!!.animations)
        assertEquals(listOf("animation_1", "animation_2"), before.animationNames)

        val renamed = renameGlbAnimations(original, listOf("Idle_Loop", "Walk_Loop"))
        val after = inspectGlb(renamed)
        assertNotNull(after)
        assertEquals(listOf("Idle_Loop", "Walk_Loop"), after!!.animationNames)
        assertEquals("GLB chunks must stay 4-byte aligned", 0, renamed.size % 4)
        assertArrayEquals(BIN, renamed.takeLast(BIN.size).toByteArray())
    }

    @Test
    fun `leaves the GLB alone when the clip count does not match`() {
        val original = glb(listOf("animation_1", "animation_2"))
        val unchanged = renameGlbAnimations(original, listOf("Idle_Loop"))
        assertArrayEquals(original, unchanged)
    }

    @Test
    fun `rejects truncated data`() {
        assertEquals(false, isGlb(ByteArray(8)))
        assertEquals(false, inspectGlb(ByteArray(8)) != null)
        assertEquals(false, inspectGlb(glb(listOf("a")).copyOf(20)) != null)
    }

    @Test
    fun `parses successful and failed retarget actions`() {
        val content = JSONObject()
            .put(
                "actions",
                JSONArray()
                    .put(JSONObject().put("animation_id", "Idle_Loop").put("status", "success").put("animationGlbUrl", "https://cdn.example/a.glb"))
                    .put(JSONObject().put("animation_id", "Walk_Loop").put("status", "failed")),
            )
            .toString()
        val outputs = JSONArray().put(JSONObject().put("content", content))
        val actions = parseRetargetActions(outputs)
        assertNotNull(actions)
        assertEquals(2, actions!!.size)
        assertTrue(actions[0].ok)
        assertEquals("Idle_Loop", actions[0].animationId)
        assertFalse(actions[1].ok)
    }

    @Test
    fun `picks GLB and four-view URLs out of task outputs`() {
        val outputs = JSONArray()
            .put(JSONObject().put("content", "https://cdn.example/model.glb"))
            .put(JSONObject().put("content", "not-a-url"))
        assertEquals("https://cdn.example/model.glb", pickGlbUrl(outputs))

        val views = JSONArray()
            .put("https://cdn.example/front.png")
            .put("https://cdn.example/back.png")
            .put("https://cdn.example/left.png")
            .put("https://cdn.example/right.png")
        assertEquals(4, pickFourViewUrls(JSONArray().put(JSONObject().put("content", views.toString())))?.size)
    }

    @Test
    fun `maps MODEL_NOT_RIGGABLE to a T-Pose hint`() {
        val content = JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("code", "MODEL_NOT_RIGGABLE")
                    .put("message", "主体为类人形角色，但双臂呈一前一后的伸展姿态，不符合可绑骨的 T-Pose 或 A-Pose 基础站姿要求。"),
            )
            .toString()
        val task = JSONObject()
            .put("status", 4)
            .put("outputs", JSONArray().put(JSONObject().put("content", content)))
        val message = taskFailureMessage(task, "骨骼绑定失败")
        assertTrue(message.contains("T-Pose"))
        assertTrue(message.contains("MODEL_NOT_RIGGABLE"))
    }

    private fun glb(animationNames: List<String>): ByteArray {
        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0"))
            .put(
                "animations",
                JSONArray().apply {
                    animationNames.forEach { name ->
                        put(JSONObject().put("name", name).put("channels", JSONArray()).put("samplers", JSONArray()))
                    }
                },
            )
        val json = gltf.toString().toByteArray(Charsets.UTF_8)
        val padding = (4 - json.size % 4) % 4
        val padded = if (padding == 0) json else json + ByteArray(padding) { 0x20 }
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67)
            .putInt(2)
            .putInt(12 + 8 + padded.size + 8 + BIN.size)
            .array()
        val jsonChunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(padded.size)
            .putInt(0x4E4F534A)
            .array()
        val binChunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(BIN.size)
            .putInt(0x004E4942)
            .array()
        return header + jsonChunk + padded + binChunk + BIN
    }

    private companion object {
        val BIN = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}
