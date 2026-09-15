package dev.artplus.iconpackfiller.provider

/**
 * 透明直出声明：模型是否能直接生成透明背景。
 *
 * 三态：[AUTO] 按 [TransparencyPresets] 预设自动判断；[YES]/[NO] 用户手动声明，
 * 手动声明永远优先于预设。
 */
enum class TransparencyPreference(val value: String, val label: String) {
    AUTO("auto", "自动判断"),
    YES("yes", "强制透明直出"),
    NO("no", "强制键色底");

    companion object {
        fun fromValue(value: String?): TransparencyPreference =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/**
 * 透明直出能力的内置预设映射（按模型 id 子串匹配，大小写不敏感）。
 *
 * 收录原则偏保守：只收录**确定**能出透明的模型。误判为"不支持"无非多走
 * 一次键色底抠图（可用链路）；误判为"支持"则提示词不再要求背景，
 * 模型画出实底时抠图可能整体失败，浪费一次付费调用。
 *
 * - gpt-image 家族（含 gpt-image-1 / 1-mini / 2 / 2.5 及后续版本）：官方
 *   `background: transparent` 参数（Images / Responses image_generation
 *   工具均支持，需 PNG 输出；本 App 已默认发送该参数）。
 * - dall-e-2：原生输出带透明通道的 PNG。
 * - dall-e-3：无透明支持，固定 false。
 * - Gemini 系（含网关 Chat 协议承载的 Banana）：网关普遍回 JPEG
 *   （本 App [OpenAIImagesProvider.chatEdit] 实测），JPEG 无 alpha，
 *   传输层就断了透明可能，一律 false；若网关回 PNG 且模型确实能出透明，
 *   用户可在槽位里手动声明为"是"。
 */
object TransparencyPresets {

    private val SUPPORTED_IDS = listOf(
        "gpt-image-",
        "dall-e-2",
    )

    /** 预设是否认为该模型可透明直出。 */
    fun supports(model: String): Boolean {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return false
        return SUPPORTED_IDS.any { id.contains(it) }
    }

    /** 生效判定：手动声明优先，否则走预设。 */
    fun effective(model: String, preference: TransparencyPreference): Boolean = when (preference) {
        TransparencyPreference.YES -> true
        TransparencyPreference.NO -> false
        TransparencyPreference.AUTO -> supports(model)
    }

    /**
     * 设置页「透明直出」下拉的说明行：直接给出当前模型的预设结果 + 生效行为，
     * 不再放长串通用说明。文案必须短到能塞进紧凑行的说明位（模型名就在下方输入框里，不重复）。
     */
    fun summary(model: String, preference: TransparencyPreference): String {
        val id = model.trim()
        if (id.isEmpty()) return "填写模型后显示预设结果"
        return when (preference) {
            TransparencyPreference.AUTO -> if (supports(id)) "预设支持 → 透明直出" else "预设不支持 → 键色底"
            TransparencyPreference.YES -> "手动透明直出"
            TransparencyPreference.NO -> "手动键色底"
        }
    }
}
