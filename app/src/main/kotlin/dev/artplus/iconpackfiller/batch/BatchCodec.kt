package dev.artplus.iconpackfiller.batch

import org.json.JSONArray
import org.json.JSONObject

/**
 * [BatchRecord] 的 JSON 编解码。纯逻辑，可 JVM 单测。
 *
 * 存储格式（version 2；v1 记录照常解析，缺的字段为 null/空）：
 * ```json
 * {"version":2,"id":"...","packLabel":"Aura","packPackage":"...","createdAt":123,
 *  "status":"RUNNING","planned":4,"generated":2,"failed":1,
 *  "outputApk":"out.apk","outputApkName":"x_filler_1.apk","diagnostics":["..."],
 *  "attempts":[{"pkg":"a","label":"A","attempt":1,"accepted":true,"reason":null,
 *               "png":"a-1.png","src":"a-src.png","refs":["b"],
 *               "model":"gpt-image-2","slotId":"slot-1","slotName":"gpt image 2",
 *               "prompt":"...","refsD":[{"pkg":"b","draw":"m_1","act":"..."}]}]}
 * ```
 */
object BatchCodec {

    const val VERSION = 2

    fun encode(record: BatchRecord): String {
        val attempts = JSONArray()
        for (attempt in record.attempts) {
            val refDetails = JSONArray()
            for (ref in attempt.referenceDetails) {
                refDetails.put(
                    JSONObject()
                        .put("pkg", ref.packageName)
                        .putOpt("label", ref.label)
                        .putOpt("draw", ref.drawableName)
                        .putOpt("act", ref.activityName),
                )
            }
            attempts.put(
                JSONObject()
                    .put("pkg", attempt.packageName)
                    .putOpt("label", attempt.label)
                    .put("attempt", attempt.attempt)
                    .put("accepted", attempt.accepted)
                    .putOpt("reason", attempt.reason)
                    .putOpt("png", attempt.pngFile)
                    .putOpt("src", attempt.sourceFile)
                    .put("refs", JSONArray(attempt.references))
                    .putOpt("model", attempt.model)
                    .putOpt("slotId", attempt.slotId)
                    .putOpt("slotName", attempt.slotName)
                    .putOpt("prompt", attempt.prompt)
                    .put("refsD", refDetails),
            )
        }
        val diagnostics = JSONArray(record.diagnostics)
        return JSONObject()
            .put("version", VERSION)
            .put("id", record.id)
            .put("packLabel", record.packLabel)
            .put("packPackage", record.packPackage)
            .put("createdAt", record.createdAt)
            .put("status", record.status.name)
            .put("planned", record.plannedCount)
            .put("generated", record.generatedCount)
            .put("failed", record.failedCount)
            .putOpt("outputApk", record.outputApk)
            .putOpt("outputApkName", record.outputApkName)
            .put("diagnostics", diagnostics)
            .put("attempts", attempts)
            .toString()
    }

    /** 解析；失败返回 null（调用方跳过该条，不影响其他批次）。 */
    fun decode(text: String?): BatchRecord? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = root.optString("id").takeIf { it.isNotEmpty() } ?: return null

        val attemptArray = root.optJSONArray("attempts")
        val attempts = ArrayList<AttemptRecord>(attemptArray?.length() ?: 0)
        if (attemptArray != null) {
            for (i in 0 until attemptArray.length()) {
                val item = attemptArray.optJSONObject(i) ?: continue
                val pkg = item.optString("pkg").takeIf { it.isNotEmpty() } ?: continue
                val refs = ArrayList<String>()
                item.optJSONArray("refs")?.let { array ->
                    for (j in 0 until array.length()) {
                        array.optString(j).takeIf { it.isNotEmpty() }?.let(refs::add)
                    }
                }
                val refDetails = ArrayList<dev.artplus.iconpackfiller.generate.ReferenceSnapshot>()
                item.optJSONArray("refsD")?.let { array ->
                    for (j in 0 until array.length()) {
                        val ref = array.optJSONObject(j) ?: continue
                        val refPkg = ref.optString("pkg").takeIf { it.isNotEmpty() } ?: continue
                        refDetails.add(
                            dev.artplus.iconpackfiller.generate.ReferenceSnapshot(
                                packageName = refPkg,
                                label = ref.optString("label").takeIf { it.isNotEmpty() },
                                drawableName = ref.optString("draw").takeIf { it.isNotEmpty() },
                                activityName = ref.optString("act").takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
                attempts.add(
                    AttemptRecord(
                        packageName = pkg,
                        label = item.optString("label").takeIf { it.isNotEmpty() },
                        attempt = item.optInt("attempt", 1),
                        accepted = item.optBoolean("accepted", false),
                        reason = item.optString("reason").takeIf { it.isNotEmpty() },
                        pngFile = item.optString("png").takeIf { it.isNotEmpty() },
                        sourceFile = item.optString("src").takeIf { it.isNotEmpty() },
                        references = refs,
                        model = item.optString("model").takeIf { it.isNotEmpty() },
                        slotId = item.optString("slotId").takeIf { it.isNotEmpty() },
                        slotName = item.optString("slotName").takeIf { it.isNotEmpty() },
                        prompt = item.optString("prompt").takeIf { it.isNotEmpty() },
                        referenceDetails = refDetails,
                    ),
                )
            }
        }

        val diagnostics = ArrayList<String>()
        root.optJSONArray("diagnostics")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotEmpty() }?.let(diagnostics::add)
            }
        }

        return BatchRecord(
            id = id,
            packLabel = root.optString("packLabel", "图标包"),
            packPackage = root.optString("packPackage"),
            createdAt = root.optLong("createdAt", 0L),
            status = statusOf(root.optString("status")),
            plannedCount = root.optInt("planned", 0),
            generatedCount = root.optInt("generated", 0),
            failedCount = root.optInt("failed", 0),
            outputApk = root.optString("outputApk").takeIf { it.isNotEmpty() },
            outputApkName = root.optString("outputApkName").takeIf { it.isNotEmpty() },
            diagnostics = diagnostics,
            attempts = attempts,
        )
    }

    private fun statusOf(name: String): BatchStatus =
        runCatching { BatchStatus.valueOf(name) }.getOrDefault(BatchStatus.INTERRUPTED)
}
