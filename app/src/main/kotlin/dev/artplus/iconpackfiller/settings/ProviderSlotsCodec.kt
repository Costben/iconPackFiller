package dev.artplus.iconpackfiller.settings

import dev.artplus.iconpackfiller.provider.ApiProtocol
import dev.artplus.iconpackfiller.provider.TransparencyPreference
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 AI 供应商配置槽位。
 *
 * API Key 不进本对象（单独经 [KeyCrypto] 按槽位加密存储），因此本对象可安全序列化/日志。
 */
data class ProviderSlot(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val kind: String = "OPENAI",
    val mode: String = "images",
    /**
     * 透明直出声明（[TransparencyPreference.value]；"auto" 跟随预设）。
     *
     * 后加字段：旧配置解码缺失时回落 "auto"，[VERSION] 不升级。
     */
    val transparency: String = TransparencyPreference.AUTO.value,
) {
    companion object {
        const val MIN_SLOTS = 1
        const val MAX_SLOTS = 8

        fun default(id: String = "slot-1", index: Int = 1): ProviderSlot = ProviderSlot(
            id = id,
            name = "供应商 $index",
            baseUrl = "https://api.openai.com",
            model = "gpt-image-1",
        )

        /**
         * 按所选协议构造新槽位（id / 序号由调用方给定）。
         *
         * Base URL 预填规则：协议有官方默认地址就用官方地址（Images / Gemini 原生），
         * 否则沿用 [fallbackBaseUrl]——聚合网关场景下同一网关开多个槽位是常态，
         * 免得每加一个都要手填地址。
         */
        fun fresh(
            id: String,
            index: Int,
            protocol: ApiProtocol,
            fallbackBaseUrl: String,
        ): ProviderSlot = ProviderSlot(
            id = id,
            name = "供应商 $index",
            baseUrl = protocol.defaultBaseUrl.ifBlank { fallbackBaseUrl },
            model = protocol.defaultModel,
            kind = protocol.kind.name,
            mode = protocol.mode.value,
        )
    }
}

/**
 * 槽位列表的 JSON 编解码与旧数据迁移。纯逻辑，可 JVM 单测。
 *
 * 存储格式：
 * ```json
 * {"version":1,"active":"slot-1","slots":[{"id":"slot-1","name":"...","baseUrl":"...","model":"...","kind":"OPENAI","mode":"images","transparency":"auto"}]}
 * ```
 * `transparency` 缺失时按 "auto" 解码（旧配置兼容）。
 */
object ProviderSlotsCodec {

    const val VERSION = 1

    data class Config(
        val slots: List<ProviderSlot>,
        val activeId: String,
    ) {
        val active: ProviderSlot
            get() = slots.firstOrNull { it.id == activeId } ?: slots.first()
    }

    fun encode(config: Config): String {
        val array = JSONArray()
        for (slot in config.slots) {
            array.put(
                JSONObject()
                    .put("id", slot.id)
                    .put("name", slot.name)
                    .put("baseUrl", slot.baseUrl)
                    .put("model", slot.model)
                    .put("kind", slot.kind)
                    .put("mode", slot.mode)
                    .put("transparency", slot.transparency),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("active", config.activeId)
            .put("slots", array)
            .toString()
    }

    /**
     * 解析；失败或为空返回 null（调用方走迁移/默认）。
     */
    fun decode(text: String?): Config? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val array = root.optJSONArray("slots") ?: return null
        val slots = ArrayList<ProviderSlot>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            slots.add(
                ProviderSlot(
                    id = id,
                    name = obj.optString("name").ifBlank { "供应商 ${i + 1}" },
                    baseUrl = obj.optString("baseUrl").ifBlank { "https://api.openai.com" },
                    model = obj.optString("model").ifBlank { "gpt-image-1" },
                    kind = obj.optString("kind").ifBlank { "OPENAI" },
                    mode = obj.optString("mode").ifBlank { "images" },
                    transparency = TransparencyPreference
                        .fromValue(obj.optString("transparency").ifBlank { "auto" })
                        .value,
                ),
            )
        }
        if (slots.isEmpty()) return null
        val active = root.optString("active").takeIf { candidate -> slots.any { it.id == candidate } }
            ?: slots.first().id
        return Config(slots = slots, activeId = active)
    }

    /**
     * 旧版单供应商字段迁移（base_url / model / provider_kind / openai_mode）。
     */
    fun migrate(
        baseUrl: String?,
        model: String?,
        kind: String?,
        mode: String?,
    ): Config {
        val slot = ProviderSlot(
            id = "slot-1",
            name = "默认供应商",
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: "https://api.openai.com",
            model = model?.takeIf { it.isNotBlank() } ?: "gpt-image-1",
            kind = kind?.takeIf { it.isNotBlank() } ?: "OPENAI",
            mode = mode?.takeIf { it.isNotBlank() } ?: "images",
        )
        return Config(slots = listOf(slot), activeId = slot.id)
    }

    /** 生成未占用的槽位 id。 */
    fun nextId(existing: Collection<String>): String {
        var i = 1
        while ("slot-$i" in existing) i++
        return "slot-$i"
    }

    /**
     * 「另存为」的新槽位名：原名 + " (2)"，已占用则递增。
     *
     * 原名自带 " (n)" 后缀时直接递增数字（"X (2)" → "X (3)"），
     * 而不是套娃成 "X (2) (2)"。
     */
    fun nextDuplicateName(existing: Collection<String>, base: String): String {
        val taken = existing.toSet()
        val trimmed = base.trim()
        val match = Regex("""^(.*) \((\d+)\)$""").matchEntire(trimmed)
        val stem = match?.groupValues?.get(1)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: trimmed.ifBlank { "供应商" }
        var n = (match?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
        var candidate = "$stem ($n)"
        while (candidate in taken) {
            n++
            candidate = "$stem ($n)"
        }
        return candidate
    }
}
