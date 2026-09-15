package dev.artplus.iconpackfiller.settings

import android.content.Context
import android.content.SharedPreferences
import dev.artplus.iconpackfiller.provider.ApiProtocol
import dev.artplus.iconpackfiller.provider.OpenAIMode
import dev.artplus.iconpackfiller.provider.ProviderConfig
import dev.artplus.iconpackfiller.provider.ProviderKind
import dev.artplus.iconpackfiller.provider.TransparencyPreference

/**
 * 设置持久化。Key 走 [KeyCrypto] 加密；其余字段明文。
 * 不提供任何包含 Key 的导出。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 供应商槽位配置。首次访问时从旧单供应商字段迁移，并迁移旧 Key 到首槽位。
     */
    var providerSlots: ProviderSlotsCodec.Config
        get() {
            val stored = ProviderSlotsCodec.decode(prefs.getString(KEY_SLOTS, null))
            if (stored != null) return stored
            val migrated = ProviderSlotsCodec.migrate(
                baseUrl = prefs.getString(KEY_BASE_URL, null),
                model = prefs.getString(KEY_MODEL, null),
                kind = prefs.getString(KEY_PROVIDER, null),
                mode = prefs.getString(KEY_OPENAI_MODE, null),
            )
            prefs.edit().putString(KEY_SLOTS, ProviderSlotsCodec.encode(migrated)).apply()
            KeyCrypto.migrateLegacyToSlot(prefs, migrated.slots.first().id)
            return migrated
        }
        set(value) = prefs.edit().putString(KEY_SLOTS, ProviderSlotsCodec.encode(value)).apply()

    /** 当前激活槽位。 */
    val activeSlot: ProviderSlot
        get() = providerSlots.active

    /** 更新激活槽位字段（自动保存）。 */
    fun updateActiveSlot(transform: (ProviderSlot) -> ProviderSlot) {
        val config = providerSlots
        val updated = config.slots.map { if (it.id == config.activeId) transform(it) else it }
        providerSlots = config.copy(slots = updated)
    }

    fun setActiveSlot(slotId: String) {
        val config = providerSlots
        if (config.slots.none { it.id == slotId }) return
        providerSlots = config.copy(activeId = slotId)
    }

    /**
     * 新增槽位并激活（按所选协议预填 Base URL / 模型）；达到上限返回 null。
     */
    fun addSlot(protocol: ApiProtocol): ProviderSlot? {
        val config = providerSlots
        if (config.slots.size >= ProviderSlot.MAX_SLOTS) return null
        val id = ProviderSlotsCodec.nextId(config.slots.map { it.id })
        val slot = ProviderSlot.fresh(
            id = id,
            index = config.slots.size + 1,
            protocol = protocol,
            fallbackBaseUrl = config.active.baseUrl,
        )
        providerSlots = config.copy(slots = config.slots + slot, activeId = id)
        return slot
    }

    /**
     * 「另存为」：完整复制当前激活槽位为新槽位并激活。
     *
     * 复制全部字段（名称自动加 " (2)" 后缀、Base URL、模型、协议、透明声明等），
     * API Key 一并复制——免得每加一个供应商都要重输 Key 和 URL。
     * 达到上限返回 null。
     */
    fun duplicateActiveSlot(): ProviderSlot? {
        val config = providerSlots
        if (config.slots.size >= ProviderSlot.MAX_SLOTS) return null
        val active = config.active
        val id = ProviderSlotsCodec.nextId(config.slots.map { it.id })
        val slot = active.copy(
            id = id,
            name = ProviderSlotsCodec.nextDuplicateName(config.slots.map { it.name }, active.name),
        )
        providerSlots = config.copy(slots = config.slots + slot, activeId = id)
        KeyCrypto.saveSlot(prefs, id, KeyCrypto.loadSlot(prefs, active.id))
        return slot
    }

    /** 删除槽位（至少保留一个）；被删的是激活槽位时切到首槽位。 */
    fun removeSlot(slotId: String): Boolean {
        val config = providerSlots
        if (config.slots.size <= ProviderSlot.MIN_SLOTS) return false
        if (config.slots.none { it.id == slotId }) return false
        val remaining = config.slots.filterNot { it.id == slotId }
        val active = if (config.activeId == slotId) remaining.first().id else config.activeId
        providerSlots = ProviderSlotsCodec.Config(slots = remaining, activeId = active)
        KeyCrypto.saveSlot(prefs, slotId, "")
        return true
    }

    /** 读取激活槽位的 Key。 */
    fun loadActiveApiKey(): String = KeyCrypto.loadSlot(prefs, activeSlot.id)

    /**
     * 保存激活槽位的 Key（自动保存调用）。
     *
     * @return 实际保存的净化后 Key（输入被过滤/为空时可能不同）。
     */
    fun saveActiveApiKey(value: String): String {
        val sanitized = sanitizeKey(value)
        if (sanitized.isEmpty()) return ""
        KeyCrypto.saveSlot(prefs, activeSlot.id, sanitized)
        return sanitized
    }



    /** 指定槽位的 Key。 */
    fun loadApiKey(slotId: String): String = KeyCrypto.loadSlot(prefs, slotId)

    /**
     * 当前槽位的接口协议。读写走既有 kind + mode 字段，不新增存储（旧配置自动归一化呈现）。
     */
    var apiProtocol: ApiProtocol
        get() = ApiProtocol.of(
            kind = ProviderKind.fromValue(activeSlot.kind),
            mode = OpenAIMode.fromValue(activeSlot.mode),
        )
        set(value) = updateActiveSlot {
            it.copy(kind = value.kind.name, mode = value.mode.value)
        }

    val providerKind: ProviderKind
        get() = ProviderKind.fromValue(activeSlot.kind)

    val openAiMode: OpenAIMode
        get() = OpenAIMode.fromValue(activeSlot.mode)

    var baseUrl: String
        get() = activeSlot.baseUrl
        set(value) = updateActiveSlot { it.copy(baseUrl = value.trim()) }

    var model: String
        get() = activeSlot.model
        set(value) = updateActiveSlot { it.copy(model = value.trim()) }

    /** 当前槽位的透明直出声明（auto 跟随 [TransparencyPresets] 预设）。 */
    var transparencyPreference: TransparencyPreference
        get() = TransparencyPreference.fromValue(activeSlot.transparency)
        set(value) = updateActiveSlot { it.copy(transparency = value.value) }

    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, 1).coerceIn(1, 4)
        set(value) = prefs.edit().putInt(KEY_CONCURRENCY, value.coerceIn(1, 4)).apply()

    var maxRetries: Int
        get() = prefs.getInt(KEY_RETRIES, 2).coerceIn(0, 5)
        set(value) = prefs.edit().putInt(KEY_RETRIES, value.coerceIn(0, 5)).apply()

    var callLimit: Int
        get() = prefs.getInt(KEY_CALL_LIMIT, 0)
        set(value) = prefs.edit().putInt(KEY_CALL_LIMIT, value.coerceAtLeast(0)).apply()

    var referencePairCount: Int
        get() = prefs.getInt(KEY_REF_PAIRS, 2).coerceIn(1, 6)
        set(value) = prefs.edit().putInt(KEY_REF_PAIRS, value.coerceIn(1, 6)).apply()

    var excludeSystemApps: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_SYSTEM, true)
        set(value) = prefs.edit().putBoolean(KEY_EXCLUDE_SYSTEM, value).apply()

    var blacklist: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLACKLIST, value).apply()

    /** 颜色模式（0=跟随系统 1=浅色 2=深色 3=莫奈跟随 4=莫奈浅色 5=莫奈深色）。 */
    var colorMode: Int
        get() = prefs.getInt(KEY_COLOR_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_COLOR_MODE, value).apply()

    /** 莫奈自定义取色种子；0 表示从壁纸取色。 */
    var keyColor: Int
        get() = prefs.getInt(KEY_KEY_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_KEY_COLOR, value).apply()

    /** 构造 ProviderConfig（Key 解密后注入）。 */
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        kind = providerKind,
        baseUrl = baseUrl,
        model = model,
        apiKey = loadActiveApiKey(),
        mode = openAiMode,
        transparency = transparencyPreference,
        concurrency = concurrency,
        maxRetries = maxRetries,
        callLimit = callLimit.takeIf { it > 0 },
    )

    /** 指定槽位构造 ProviderConfig；槽位不存在返回 null（历史批次回放用）。 */
    fun toProviderConfig(slotId: String): ProviderConfig? {
        val slot = providerSlots.slots.firstOrNull { it.id == slotId } ?: return null
        return ProviderConfig(
            kind = ProviderKind.fromValue(slot.kind),
            baseUrl = slot.baseUrl,
            model = slot.model,
            apiKey = loadApiKey(slot.id),
            mode = OpenAIMode.fromValue(slot.mode),
            transparency = TransparencyPreference.fromValue(slot.transparency),
            concurrency = concurrency,
            maxRetries = maxRetries,
            callLimit = callLimit.takeIf { it > 0 },
        )
    }

    companion object {
        const val PREFS_NAME = "iconpackfiller_settings"
        private const val KEY_PROVIDER = "provider_kind"
        private const val KEY_OPENAI_MODE = "openai_mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_SLOTS = "provider_slots"
        private const val KEY_CONCURRENCY = "concurrency"
        private const val KEY_RETRIES = "max_retries"
        private const val KEY_CALL_LIMIT = "call_limit"
        private const val KEY_REF_PAIRS = "reference_pair_count"
        private const val KEY_EXCLUDE_SYSTEM = "exclude_system_apps"
        private const val KEY_BLACKLIST = "blacklist"
        private const val KEY_COLOR_MODE = "color_mode"
        private const val KEY_KEY_COLOR = "key_color"

        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val DEFAULT_MODEL = "gpt-image-1"

        /**
         * 净化 API Key：去掉所有空白与不可见字符，仅保留可见 ASCII。
         *
         * 中文输入法联想会把汉字插进密钥；非 ASCII 字符一律丢弃。
         */
        fun sanitizeKey(value: String): String = value
            .trim()
            .filter { it.code in 33..126 }
            .trim()
    }
}