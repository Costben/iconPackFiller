package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成历史里若干「列表型」字段的 JSON 编解码。纯逻辑，可 JVM 单测。
 *
 * 取代旧批次 JSON 编解码：不再序列化整条记录，
 * 只负责 Room 列里的 references / referenceDetails / diagnostics / params。
 */
object GenerationJsonCodec {

    // ---- references: List<String> ----

    fun encodeReferences(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    fun decodeReferences(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    // ---- referenceDetails: List<ReferenceSnapshot> ----

    fun encodeReferenceDetails(values: List<ReferenceSnapshot>): String {
        val array = JSONArray()
        for (ref in values) {
            array.put(
                JSONObject()
                    .put("pkg", ref.packageName)
                    .putOpt("label", ref.label)
                    .putOpt("draw", ref.drawableName)
                    .putOpt("act", ref.activityName),
            )
        }
        return array.toString()
    }

    fun decodeReferenceDetails(text: String?): List<ReferenceSnapshot> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ReferenceSnapshot>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val pkg = item.optString("pkg").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                ReferenceSnapshot(
                    packageName = pkg,
                    label = item.optString("label").takeIf { it.isNotEmpty() },
                    drawableName = item.optString("draw").takeIf { it.isNotEmpty() },
                    activityName = item.optString("act").takeIf { it.isNotEmpty() },
                ),
            )
        }
        return out
    }

    // ---- diagnostics: List<String> ----

    fun encodeDiagnostics(values: List<String>): String? {
        if (values.isEmpty()) return null
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    fun decodeDiagnostics(text: String?): List<String> = decodeReferences(text)

    // ---- params: Map<String, Any?> ----

    /**
     * 参数快照。值支持 String / Number / Boolean / Collection / null；
     * 其余类型 toString 兜底，避免因一个值毁掉整条记录。
     */
    fun encodeParams(values: Map<String, Any?>): String? {
        if (values.isEmpty()) return null
        val json = JSONObject()
        for ((key, value) in values) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is String, is Number, is Boolean -> json.put(key, value)
                is Collection<*> -> json.put(key, JSONArray(value.toList()))
                else -> json.put(key, value.toString())
            }
        }
        return json.toString()
    }
}
