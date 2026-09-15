package dev.artplus.iconpackfiller.pack

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry

/**
 * 单条注入请求。
 *
 * [component] 必须是带 activity 的 `ComponentInfo{pkg/activity}`：启动器按
 * [android.content.ComponentName] 匹配，包级写法会被 `unflattenFromString`
 * 判为非法而跳过（见 [PackNaming.componentInfoFor]）。
 */
data class IconInjection(
    val component: String,
    val drawableName: String,
    /** PNG 字节；写入原包同 density 目录（见 [DrawableDirectoryDetector]）。 */
    val pngBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IconInjection) return false
        return component == other.component &&
            drawableName == other.drawableName &&
            pngBytes.contentEquals(other.pngBytes)
    }

    override fun hashCode(): Int =
        31 * (31 * component.hashCode() + drawableName.hashCode()) + pngBytes.contentHashCode()
}

/**
 * 打包结果。
 */
data class PackResult(
    val outputApk: File,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val label: String,
    val injectedCount: Int,
)

/**
 * 方案 B：ARSCLib 原包原地增量注入。
 *
 * 流程（M0 Spike6 的 Kotlin 化）：
 * 1. `ApkModule.loadApkFile` 加载原包
 * 2. 在 `drawable-xxxhdpi-v4` 上 `getOrCreateEntry` 并注册 PNG
 * 3. appfilter.xml 追加新条目（assets 文本框 + res/xml 编译版双写）
 * 4. manifest 改包名 / versionCode / versionName / label + MainActivity 类名 + launcher intent-filter
 * 5. **资源表包名同步改成新包名**（见下）
 * 6. `writeApk`
 *
 * 两条容易被忽略但致命的约束：
 * - **资源表包名必须跟 manifest 包名一致**：启动器取图标走
 *   `Resources.getIdentifier(name, "drawable", 已安装包名)`，defPackage 与资源表
 *   包名不匹配时全部返回 0——表现为整包一个图标都不生效，而不是只缺新图标。
 * - **Lawnchair 优先读 `res/xml/appfilter.xml`（编译版）**，只有资源表里没有该文件才
 *   回退 `assets/appfilter.xml`；只写 assets 时新条目不会进映射。
 *
 * 纯 ARSCLib 操作，不依赖 Android 运行时（可在桌面 JVM 单测）。
 */
class IconPackPacker {

    /**
     * @param sourceApk 原图标包 APK
     * @param outputApk 输出 APK（未签名）
     * @param injections 新图标注入列表
     * @param originalAppFilter 原 appfilter.xml 文本；null 时从 ARSCLib 读 assets
     */
    fun pack(
        sourceApk: File,
        outputApk: File,
        injections: List<IconInjection>,
        originalAppFilter: String? = null,
    ): PackResult {
        require(sourceApk.isFile) { "原包不存在：${sourceApk.absolutePath}" }

        val module = ApkModule.loadApkFile(sourceApk)
        try {
            val table: TableBlock = module.tableBlock
                ?: error("APK 不含 resources.arsc：${sourceApk.name}")
            val packageBlock: PackageBlock = table.packages.next()
                ?: error("resources.arsc 不含 package：${sourceApk.name}")

            val originalPackage = packageBlock.name
            val newPackage = PackNaming.packageNameFor(originalPackage)

            // 0) 资源表包名与 manifest 包名保持一致。
            // 启动器用 getIdentifier(name, "drawable", 已安装包名) 取图标，defPackage
            // 与资源表包名不一致时全部返回 0（整包不生效，而不只是缺新图标）。
            if (originalPackage != newPackage) {
                packageBlock.setName(newPackage)
            }

            // 1) drawable 注入（沿用原包的 density 目录，避免缩放不一致）
            val convention = DrawableDirectoryDetector.detect(sourceApk)
            val typeBlock = packageBlock.getOrCreateTypeBlock(
                convention.density ?: DrawableDirectoryDetector.FALLBACK_DENSITY,
                "drawable",
            )
            for (injection in injections) {
                val resPath = convention.resPath(injection.drawableName)
                val entry = typeBlock.getOrCreateEntry(injection.drawableName)
                entry.setValueAsString(resPath)
                val source = ByteInputSource(injection.pngBytes, resPath)
                source.setMethod(ZipEntry.STORED)
                module.add(source)
                module.uncompressedFiles.addPath(resPath)
            }

            // 2) appfilter 注入：assets 文本框 + 编译版 res/xml
            val existingText = originalAppFilter ?: readAppFilter(module)
            val existingDoc = existingText?.let {
                runCatching { AppFilterParser.parse(it.byteInputStream(StandardCharsets.UTF_8)) }.getOrNull()
            }
            val existingComponents = AppFilterInjector.existingComponents(existingDoc)
            val newItems = injections
                .filter { injection ->
                    val key = ComponentKey.parse(injection.component)?.flatten()?.lowercase()
                    key == null || key !in existingComponents
                }
                .map { AppFilterInjector.NewItem(it.component, it.drawableName) }
            if (newItems.isNotEmpty() || existingText == null) {
                val updated = AppFilterInjector.inject(existingText, newItems)
                val source = ByteInputSource(updated.toByteArray(StandardCharsets.UTF_8), "assets/appfilter.xml")
                module.add(source)
            }
            injectCompiledAppFilter(module, newItems)

            // 3) manifest 改写
            val manifest: AndroidManifestBlock = module.androidManifest
                ?: error("APK 不含 AndroidManifest.xml：${sourceApk.name}")
            val originalVersionCode = manifest.versionCode
            val originalVersionName = manifest.versionName
            val originalLabel = manifest.applicationLabelString

            manifest.setPackageName(newPackage)
            manifest.setVersionCode(PackNaming.versionCodeFor(originalVersionCode))
            manifest.setVersionName(PackNaming.versionNameFor(originalVersionName))
            manifest.setApplicationLabel(PackNaming.labelFor(originalLabel))
            stripSplitRequirements(manifest)
            // 与原包共存：缓存/自定义权限与 provider authority 必须换成新包名域
            renamePackageScopedNames(manifest, originalPackage, newPackage)

            val mainActivity = manifest.getMainActivity()
            if (mainActivity != null) {
                val oldClassName = mainActivity
                    .searchAttributeByResourceId(AndroidManifestBlock.ID_name)
                    ?.valueString
                val newClassName = PackNaming.mainActivityClassFor(newPackage)
                mainActivity.searchAttributeByResourceId(AndroidManifestBlock.ID_name)
                    ?.setValueAsString(newClassName)
                if (oldClassName != null) {
                    renameAliasTargets(manifest, oldClassName, newClassName)
                }
                addLauncherIntentFilters(mainActivity)
            }

            table.refreshFull()
            outputApk.parentFile?.mkdirs()
            module.writeApk(outputApk)

            return PackResult(
                outputApk = outputApk,
                packageName = newPackage,
                versionCode = PackNaming.versionCodeFor(originalVersionCode),
                versionName = PackNaming.versionNameFor(originalVersionName),
                label = PackNaming.labelFor(originalLabel),
                injectedCount = injections.size,
            )
        } finally {
            runCatching { module.close() }
        }
    }

    private fun readAppFilter(module: ApkModule): String? {
        val source = module.getInputSource("assets/appfilter.xml") ?: return null
        return source.openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    /**
     * 就地更新编译版 appfilter（`res/xml` 优先，`res/raw` 兜底）。
     *
     * Lawnchair 等启动器优先通过资源表读 `@xml/appfilter`，只有该资源不存在时才回退
     * `assets/appfilter.xml`；只写 assets 的话新条目不会进映射。
     *
     * 解析/写回失败时跳过（保留原文）：启动器仍能用原条目，只是新增图标缺失，
     * 比整包打包失败更可接受。
     */
    private fun injectCompiledAppFilter(module: ApkModule, items: List<AppFilterInjector.NewItem>) {
        if (items.isEmpty()) return
        val path = COMPILED_APPFILTER_PATHS.firstOrNull { module.getInputSource(it) != null } ?: return
        runCatching {
            val document = module.loadResXmlDocument(path)
            val root = document.documentElement ?: return
            val existing = HashSet<String>()
            for (element in root.listElements("item")) {
                val component = element.searchAttributeByName("component")?.valueAsString ?: continue
                ComponentKey.parse(component)?.let { existing.add(it.flatten().lowercase()) }
            }
            val missing = items.filter { item ->
                val key = ComponentKey.parse(item.component)?.flatten()?.lowercase()
                key == null || key !in existing
            }
            if (missing.isEmpty()) return
            for (item in missing) {
                val element = root.createChildElement("item")
                element.createAttribute("component", 0).setValueAsString(item.component)
                element.createAttribute("drawable", 0).setValueAsString(item.drawableName)
            }
            document.refreshFull()
            module.add(ByteInputSource(document.bytes, path))
        }
    }

    /**
     * 去掉「必须与 split 一起安装」的 manifest 约束。
     *
     * 从 Play 拆包安装的图标包（base + arm64/en 等 split），base 上带
     * `android:requiredSplitTypes` / `android:splitTypes`；只装 base（补全包是
     * 独立 APK）会报 `INSTALL_FAILED_MISSING_SPLIT`。
     * 补全包只复用原包的资源与 intent-filter，不依赖这些 split，直接剥掉。
     */
    private fun stripSplitRequirements(manifest: AndroidManifestBlock) {
        val root = manifest.documentElement ?: return
        root.removeAttributesWithId(ID_REQUIRED_SPLIT_TYPES)
        root.removeAttributesWithId(ID_SPLIT_TYPES)
    }

    /**
     * 同步改写 `<activity-alias android:targetActivity>`。
     *
     * 主 Activity 改名后 alias 仍指向旧类名，安装器校验「alias 目标必须在
     * manifest activities 里」会失败（INSTALL_PARSE_FAILED_MANIFEST_MALFORMED）。
     */
    private fun renameAliasTargets(
        manifest: AndroidManifestBlock,
        oldClassName: String,
        newClassName: String,
    ) {
        for (alias in manifest.listApplicationElementsByTag("activity-alias")) {
            val target = alias.searchAttributeByResourceId(ID_TARGET_ACTIVITY) ?: continue
            if (target.valueString == oldClassName) {
                target.setValueAsString(newClassName)
            }
        }
    }

    /**
     * 把「原包名域」的 manifest 名称迁到新包名，保证与原包共存：
     * - 自定义权限（含 androidx 自动生成的 `*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）
     * - content provider 的 `android:authorities`
     * - 对上述名字的引用（如 receiver 的 `android:permission`）
     *
     * 只处理权限与 authority：组件类名（如 `com.one4studio...Activity`）指向 dex
     * 里的真实类，不能被改成不存在的类名。
     */
    private fun renamePackageScopedNames(
        manifest: AndroidManifestBlock,
        oldPackage: String,
        newPackage: String,
    ) {
        val root = manifest.documentElement ?: return
        val prefix = "$oldPackage."
        val renamedNames = HashMap<String, String>()
        val updates = ArrayList<Pair<ResXmlAttribute, String>>()

        fun rename(value: String): String = newPackage + value.removePrefix(oldPackage)

        for (element in root.recursiveElements()) {
            if (element.name in PERMISSION_TAGS) {
                val nameAttr = element.searchAttributeByResourceId(AndroidManifestBlock.ID_name)
                val value = nameAttr?.valueString
                if (nameAttr != null && value != null && value.startsWith(prefix)) {
                    val next = rename(value)
                    renamedNames[value] = next
                    updates.add(nameAttr to next)
                }
            }
            for (attr in element.attributes) {
                if (attr.nameId != ID_AUTHORITIES) continue
                val value = attr.valueString ?: continue
                val parts = value.split(';')
                if (parts.none { it.startsWith(prefix) }) continue
                updates.add(attr to parts.joinToString(";") { if (it.startsWith(prefix)) rename(it) else it })
                parts.filter { it.startsWith(prefix) }.forEach { renamedNames[it] = rename(it) }
            }
        }
        updates.forEach { (attr, value) -> attr.setValueAsString(value) }
        if (renamedNames.isEmpty()) return

        // 引用侧（uses-permission / receiver android:permission 等）按精确值替换
        val references = ArrayList<Pair<ResXmlAttribute, String>>()
        for (element in root.recursiveElements()) {
            for (attr in element.attributes) {
                val value = attr.valueString ?: continue
                renamedNames[value]?.let { references.add(attr to it) }
            }
        }
        references.forEach { (attr, value) -> attr.setValueAsString(value) }
    }

    /**
     * 八个 launcher 的 theme intent-filter（M0 ADR 实测结论）。
     */
    private fun addLauncherIntentFilters(activity: ResXmlElement) {
        for (action in THEME_ACTIONS) {
            addIntentFilter(activity, action, "android.intent.category.DEFAULT")
        }
        addIntentFilter(activity, "android.intent.action.MAIN", "com.anddoes.launcher.THEME")
        addIntentFilter(
            activity,
            "com.novalauncher.THEME",
            "com.novalauncher.category.CUSTOM_ICON_PICKER",
        )
    }

    private fun addIntentFilter(activity: ResXmlElement, action: String, category: String) {
        val intentFilter = activity.createChildElement("intent-filter")
        val actionElement = intentFilter.createChildElement("action")
        actionElement.createAndroidAttribute("name", ANDROID_NAME_ATTR_ID)
            .setValueAsString(action)
        val categoryElement = intentFilter.createChildElement("category")
        categoryElement.createAndroidAttribute("name", ANDROID_NAME_ATTR_ID)
            .setValueAsString(category)
    }

    companion object {
        private const val ANDROID_NAME_ATTR_ID = 0x01010003

        /** 编译版 appfilter 的候选路径（Lawnchair 优先读 xml，其次 raw）。 */
        private val COMPILED_APPFILTER_PATHS = listOf(
            "res/xml/appfilter.xml",
            "res/raw/appfilter.xml",
        )

        /** `android:requiredSplitTypes`（bundle base 上的必需 split 类型）。 */
        private const val ID_REQUIRED_SPLIT_TYPES = 0x0101064e

        /** `android:splitTypes`。 */
        private const val ID_SPLIT_TYPES = 0x0101064f

        /** `android:targetActivity`。 */
        private const val ID_TARGET_ACTIVITY = 0x01010202

        /** `android:authorities`。 */
        private const val ID_AUTHORITIES = 0x01010018

        /** 声明/引用自定义权限的元素。 */
        private val PERMISSION_TAGS = setOf(
            "permission",
            "permission-group",
            "permission-tree",
            "uses-permission",
            "uses-permission-sdk-23",
        )

        /** M0 ADR：ADW / Nova / Lawnchair / Smart / MS / AndDoes / Atom / GO。 */
        val THEME_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "com.fede.launcher.THEME_ICONPACK",
            "com.dlto.atom.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
        )
    }
}