package dev.artplus.iconpackfiller.provider

import dev.artplus.iconpackfiller.R

/**
 * 模型所属厂商（按模型 id 命名惯例识别，用于模型选择器的行徽标）。
 *
 * 纯逻辑，可 JVM 单测。匹配顺序即声明顺序，首个命中为准；
 * 宽泛关键字必须排在可能包含它的具体家族之后。
 *
 * @param iconRes 厂商 logo 的 VectorDrawable（`res/drawable/ic_vendor_*`，
 *   由 LobeHub Icons 的 SVG 转制，见 `THIRD-PARTY-NOTICES.md`）；null 时回退字母徽。
 * @param tintIcon 单色 logo（转制时源 SVG 全为 currentColor）是否需要按主题着色。
 */
enum class ModelVendor(
    /** 界面展示名。 */
    val label: String,
    /** 徽标内 1-2 个字符（无真图标时的回退）。 */
    val shortText: String,
    /** 徽标底色（0xAARRGGBB，回退徽标用）。 */
    val color: Long,
    /** 命中该厂商的小写子串（按此顺序优先匹配）。 */
    val hints: List<String>,
    val iconRes: Int? = null,
    val tintIcon: Boolean = false,
) {
    OPENAI(
        label = "OpenAI",
        shortText = "O",
        color = 0xFF10A37F,
        hints = listOf("openai", "chatgpt", "gpt", "dall-e", "codex", "o1", "o3", "o4"),
        iconRes = R.drawable.ic_vendor_openai,
        tintIcon = true,
    ),
    GOOGLE(
        label = "Google",
        shortText = "G",
        color = 0xFF4285F4,
        hints = listOf("gemini", "imagen", "banana", "gemma", "veo", "google", "bard", "palm"),
        iconRes = R.drawable.ic_vendor_google,
    ),
    ANTHROPIC(
        label = "Anthropic",
        shortText = "A",
        color = 0xFFD97757,
        hints = listOf("anthropic", "claude"),
        iconRes = R.drawable.ic_vendor_anthropic,
    ),
    XAI(
        label = "xAI",
        shortText = "X",
        color = 0xFF333333,
        hints = listOf("xai", "x-ai", "grok"),
        iconRes = R.drawable.ic_vendor_xai,
        tintIcon = true,
    ),
    ALIBABA(
        label = "阿里巴巴",
        shortText = "阿",
        color = 0xFFFF6A00,
        hints = listOf("alibaba", "tongyi", "qwen", "qwq", "wanx", "wan-"),
        iconRes = R.drawable.ic_vendor_alibaba,
    ),
    BYTEDANCE(
        label = "字节跳动",
        shortText = "字",
        color = 0xFF4D6BFE,
        // 注：火山 "ark"  endpoint id 不做模型关键字——它是 "spark" 的子串，会误伤讯飞星火
        hints = listOf("bytedance", "volc", "doubao", "seedream", "jimeng", "skylark"),
        iconRes = R.drawable.ic_vendor_bytedance,
    ),
    DEEPSEEK(
        label = "深度求索",
        shortText = "深",
        color = 0xFF2E6BE6,
        hints = listOf("deepseek"),
        iconRes = R.drawable.ic_vendor_deepseek,
    ),
    ZHIPU(
        label = "智谱",
        shortText = "智",
        color = 0xFF3E2FE0,
        hints = listOf("zhipu", "chatglm", "glm", "cogview", "cogvideo"),
        iconRes = R.drawable.ic_vendor_zhipu,
    ),
    MOONSHOT(
        label = "月之暗面",
        shortText = "月",
        color = 0xFFB45309,
        hints = listOf("moonshot", "kimi"),
        iconRes = R.drawable.ic_vendor_moonshot,
        tintIcon = true,
    ),
    MINIMAX(
        label = "MiniMax",
        shortText = "M",
        color = 0xFFE63E3E,
        hints = listOf("minimax", "hailuo", "abab"),
        iconRes = R.drawable.ic_vendor_minimax,
    ),
    STEPFUN(
        label = "阶跃星辰",
        shortText = "阶",
        color = 0xFF7C3AED,
        hints = listOf("stepfun", "step-"),
        iconRes = R.drawable.ic_vendor_stepfun,
    ),
    BAICHUAN(
        label = "百川智能",
        shortText = "百",
        color = 0xFF0EA5E9,
        hints = listOf("baichuan"),
        iconRes = R.drawable.ic_vendor_baichuan,
    ),
    YI(
        label = "零一万物",
        shortText = "零",
        color = 0xFF65A30D,
        hints = listOf("01.ai", "yi-"),
        iconRes = R.drawable.ic_vendor_yi,
        tintIcon = true,
    ),
    STABILITY(
        label = "Stability AI",
        shortText = "S",
        color = 0xFF8B5CF6,
        hints = listOf("stability", "stable-diffusion", "stable-", "sdxl", "sd3", "sd-"),
        iconRes = R.drawable.ic_vendor_stability,
    ),
    BFL(
        label = "Black Forest Labs",
        shortText = "B",
        color = 0xFF111827,
        hints = listOf("black-forest", "bfl", "flux"),
        iconRes = R.drawable.ic_vendor_bfl,
        tintIcon = true,
    ),
    MIDJOURNEY(
        label = "Midjourney",
        shortText = "MJ",
        color = 0xFF0F172A,
        hints = listOf("midjourney", "niji"),
        iconRes = R.drawable.ic_vendor_midjourney,
        tintIcon = true,
    ),
    IDEOGRAM(
        label = "Ideogram",
        shortText = "I",
        color = 0xFF0D9488,
        hints = listOf("ideogram"),
        iconRes = R.drawable.ic_vendor_ideogram,
        tintIcon = true,
    ),
    KUAISHOU(
        label = "快手",
        shortText = "快",
        color = 0xFFFF4906,
        hints = listOf("kuaishou", "kwai", "kolors"),
        iconRes = R.drawable.ic_vendor_kuaishou,
        tintIcon = true,
    ),
    TENCENT(
        label = "腾讯",
        shortText = "腾",
        color = 0xFF0052D9,
        hints = listOf("tencent", "hunyuan"),
        iconRes = R.drawable.ic_vendor_tencent,
    ),
    BAIDU(
        label = "百度",
        shortText = "度",
        color = 0xFF2932E1,
        hints = listOf("baidu", "qianfan", "ernie", "wenxin"),
        iconRes = R.drawable.ic_vendor_baidu,
    ),
    IFLYTEK(
        label = "讯飞",
        shortText = "飞",
        color = 0xFF0066CC,
        hints = listOf("iflytek", "xunfei", "spark"),
        iconRes = R.drawable.ic_vendor_iflytek,
    ),
    UNKNOWN(
        label = "未知",
        shortText = "?",
        color = 0xFF9E9E9E,
        hints = emptyList(),
    );

    companion object {
        /** 按模型 id 识别厂商；无命中返回 [UNKNOWN]。 */
        fun forModel(model: String): ModelVendor {
            val id = model.trim().lowercase()
            if (id.isEmpty()) return UNKNOWN
            for (vendor in entries) {
                if (vendor.hints.any { id.contains(it) }) return vendor
            }
            return UNKNOWN
        }
    }
}
