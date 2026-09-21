package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenerationJsonCodecTest {

    @Test
    fun `references round trip`() {
        val values = listOf("com.a", "com.b")
        assertEquals(values, GenerationJsonCodec.decodeReferences(GenerationJsonCodec.encodeReferences(values)))
    }

    @Test
    fun `empty references encode to an empty array and decode empty`() {
        assertEquals("[]", GenerationJsonCodec.encodeReferences(emptyList()))
        assertEquals(emptyList(), GenerationJsonCodec.decodeReferences(null))
        assertEquals(emptyList(), GenerationJsonCodec.decodeReferences(""))
        assertEquals(emptyList(), GenerationJsonCodec.decodeReferences("{"))
    }

    @Test
    fun `reference details keep nullable fields`() {
        val values = listOf(
            ReferenceSnapshot(
                packageName = "ginlemon.flowerfree",
                label = "Flower Free",
                drawableName = "m_12",
                activityName = "ginlemon.flowerfree.Main",
            ),
            ReferenceSnapshot(packageName = "bitpit.launcher"),
        )
        val decoded = GenerationJsonCodec.decodeReferenceDetails(
            GenerationJsonCodec.encodeReferenceDetails(values),
        )
        assertEquals(values, decoded)
    }

    @Test
    fun `reference details tolerate garbage`() {
        assertEquals(emptyList(), GenerationJsonCodec.decodeReferenceDetails(null))
        assertEquals(emptyList(), GenerationJsonCodec.decodeReferenceDetails("not json"))
    }

    @Test
    fun `diagnostics encode null when empty and round trip otherwise`() {
        assertNull(GenerationJsonCodec.encodeDiagnostics(emptyList()))
        val values = listOf("com.x: 校验未通过", "参考池为空")
        assertEquals(values, GenerationJsonCodec.decodeDiagnostics(GenerationJsonCodec.encodeDiagnostics(values)))
        assertEquals(emptyList(), GenerationJsonCodec.decodeDiagnostics(null))
    }

    @Test
    fun `params encode scalars and collections`() {
        val json = GenerationJsonCodec.encodeParams(
            mapOf(
                "referencePairCount" to 2,
                "transparent" to true,
                "callLimit" to null,
                "selectedTargets" to listOf("a/b", "c/d"),
            ),
        )
        val parsed = org.json.JSONObject(json!!)
        assertEquals(2, parsed.getInt("referencePairCount"))
        assertEquals(true, parsed.getBoolean("transparent"))
        assertEquals(true, parsed.isNull("callLimit"))
        assertEquals(2, parsed.getJSONArray("selectedTargets").length())
    }

    @Test
    fun `params encode null for empty map`() {
        assertNull(GenerationJsonCodec.encodeParams(emptyMap()))
    }
}
