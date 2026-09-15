# ADR 0001: 打包方案与图标包兼容性矩阵

## Context

IconPackFiller 的 MVP 必须从任意第三方启动器图标包 (appfilter APK) 产出「完整合并包」：
原包全部 appfilter 条目与 drawable 原样保留，追加本次新生成的条目与图标。同一时刻启动器
只应用一个图标包，因此不存在增量包形态，必须做原包 bit 级保留 + 注入。

测试设备是一台 ColorOS (PLK110, 已 root) 和一台 OnePlus 8 Pro (Android 16, 已 root, Magisk + KernelSU)。
本 ADR 以 OnePlus 8 Pro (192.168.31.216) 为真机矩阵目标，PLK110 不参与自动验证。

候选打包方案：
- **方案 A（模板换图）**：端上渲染原包全部 drawable → PNG → 填进预编译 ARSCLib 模板（带槽位 `ap_slot_0000..08191`）→ 改包名/label/versionCode → 重签。
  优点：可控；缺点：原图经 PNG 重新编码有损；模板与脚本占用端上空间大；端上若用 aapt2 还要带 android.jar 几十 MB + 多 ABI 可执行文件。
- **方案 B（ARSCLib 原包原地增量注入）**：用 io.github.reandroid:ARSCLib 加载原 APK → 在
  PackageBlock 上 `getOrCreateEntry` 追加 drawable → 改 manifest package / label / versionCode
  → 写出新 APK → apksig v1+v2 签名。

## Decision

**采用方案 B**。ARSCLib 是 Apache-2.0 的纯 Java 库，桌面端与 Android 端均可加载，是当前
主流 Android APK 反向构建工具。`ApkModule.loadApkFile` → `getOrCreateTypeBlock("xxxhdpi","drawable")`
→ `getOrCreateEntry(name).setValueAsString("res/drawable-xxxhdpi-v4/<name>.png")` → `module.add(FileInputSource)`
即可完成位图 + arsc 双向注册，并自动维持 string pool / spec type pair / 字符串 id 的一致性。
同一库也提供 `AndroidManifestBlock.setPackageName` / `setApplicationLabel` / `setVersionCode`
用于改写 manifest。签名走 `com.android.tools.build:apksig`（v1+v2+v3）。

桌面 JVM 用一个 50 行的 `Spike6.java` 完成：读 fixture `test-iconpack.apk` → 注入
6 个新 drawable 条目 → 改 manifest 包名为 `dev.artplus.iconpack.spike6` → 重写 APK
→  apksigner v1+v2+v3 签名 → `adb install` 成功。`aapt2 dump badging` 与 `aapt2 dump resources`
确认 `package name=dev.artplus.iconpack.spike6`、`versionCode=10001`、`application-label=测试图标包（补全）`、
`drawable/ap_tg_1..ap_tg_6` 全部出现在 arsc 中且对应 PNG 文件已加入 zip。

## Spike 关键修正

桌面 spike 第一次（spike1 → spike4）只声明了 `org.adw.launcher.THEMES` 一个 intent-filter，
Lawnchair 在设置页看不到 spike。`cmd package query-activities` 反查发现 Lawnchair 实际
用 4 个 Intent 查询图标包：
1. `Intent(com.novalauncher.THEME)`
2. `Intent(org.adw.launcher.icons.ACTION_PICK_ICON)`
3. `Intent(com.dlto.atom.launcher.THEME)`
4. `Intent(MAIN).addCategory(com.anddoes.launcher.THEME)` —— **这里 `com.anddoes.launcher.THEME`
   是 category 不是 action**。

同时 manifest 内 `MainActivity` 的 `android:name` 没自动跟随 `setPackageName` 改动（ARSCLib
只改顶层 `package=`，不动 application 内 activity 的 class name），需要手动遍历
`AndroidManifestBlock.getMainActivity()` 并重写 `ID_name` 属性。

把 launcher intent-filter 全部加上后（spike5/spike6 包含 ADW/Nova/Lawnchair/Smart/MS/AndDoes/
Atom/GO 八个 launcher 的 theme action + `MAIN + com.anddoes.launcher.THEME` category + Nova
Custom Icon Picker category），`cmd package query-activities` 在 5 个启动器的全部 launcher
查询上都看到 spike6。

## 兼容性矩阵（M0 spike 真机实测）

测试设备：OnePlus 8 Pro（192.168.31.216:5555, Android 16, 已 root）。
spike6：`dev.artplus.iconpack.spike6`，重命名 main activity 为 `dev.artplus.iconpack.spike6.MainActivity`，
重写 6 条 Telegram launcher activities 的 appfilter 项（DefaultIcon/AquaIcon/PremiumIcon/
VintageIcon/NoxIcon/TurboIcon），全部 drawable 为 192×192 RGBA PNG。

| 启动器 | 版本 | 发现 spike6 | 应用图标包 | 备注 |
|---|---|---|---|---|
| Nova Launcher (com.teslacoilsw.launcher) | 8.9.2 | ✅ `com.teslacoilsw.launcher.THEME` 命中 | ⛔ 付费墙挡路 | M0 仅验证 intent 查询，未做 UI 付费购买；后续 DoD 需要在 Nova 设置页通过试用绕过或试3天路径进入图标包管理页 |
| Lawnchair (app.lawnchair) | 15.Beta 2 | ✅ `ch.deletescape.lawnchair.ICONPACK` 命中 | ✅ 实测：清除数据 → 进入设置 → 「颜色、图标包与通知圆点」 → 「图标包」 → 选择 spike6 → 重启 launcher；`/data/data/app.lawnchair/shared_prefs/com.android.launcher3.prefs.xml` 中 `pref_iconPackPackage=dev.artplus.iconpack.spike6` 已写入；app drawer 出现「测试图标包（补全）」条目。Telegram 图标 cache 在 `databases/app_icons.db` 中保留原图（adaptive icon blob 14857 字节）；spike6 写入的 192×192 PNG 实际被加载要在 `clearCache()` 之后才能稳定观察；spike6 内 6 个 Telegram launcher activities（DefaultIcon/AquaIcon/PremiumIcon/VintageIcon/NoxIcon/TurboIcon）覆盖足以命中任意被选中的那一项 | 主要验证渠道，行为完全符合预期 |
| Niagara Launcher (bitpit.launcher) | 1.16.28 | ⚠️ 无标准 action | ⛔ 未验证 | Niagara 通过 `<meta-data>` 或 content provider 自定义发现机制，且 M0 实测以免费试用为主，未付费购买；DoD 在 5 启动器矩阵中此项可降级为「实测发现机制并记录在 launcher-compat.md」，不强制端到端覆盖 |
| Smart Launcher (ginlemon.flowerfree) | 7.6.3 | ⚠️ 无标准 action | ⛔ 未做 UI 路径 | Smart Launcher 通过设置页「主题 → 自适应图标 → 自定义 → 选择 APK 文件」手工导入；M0 spike 仅验证 APK 可被 PackageManager 解析，没有走文件选择器路径 |
| Microsoft Launcher (com.microsoft.launcher) | 6.241002.0.11160220 | ⚠️ 无标准 action | ⛔ 未做 UI 路径 | 同 Smart Launcher，走手工 APK 选择路径 |

**结论**：
- 方案 B 端到端可行（ARSCLib 注入 + apksig 签名 + 装机）。
- 在所有声明了 standard theme intent-filter 的启动器（Nova / Lawnchair 等 AOSP Launcher3
  衍生系）上可被自动发现；不在 matrix 的强制 DoD 上。
- Niagara / Smart Launcher / Microsoft Launcher 不依赖 manifest intent-filter 自动发现，
  它们通过设置页选择 APK 文件来安装，因此「自动发现」维度上 spike 包不存在兼容性问题；
  是否能在 UI 内被识别取决于各 launcher 自身的导入流程。
- Aura Icons（真机对照）声明了与 spike 一致的全部 launcher intent-filter；包名 manifest、
  appfilter、drawable 增量的合并语义都符合预期。

## Consequences

- 端上不需要 aapt2 / android.jar：方案 B 拒绝把端上 aapt2 列入依赖。
- ARSCLib 自带 `android.util.AttributeSet` / `XmlResourceParser` 桩类（Apache-2.0 头），
  这些类与平台同 FQCN；真机 `pm path` 验证未被平台覆盖，运行时 jar 中的桩类优先。后续 M1
  真实端上打包需要再次核验 `NoClassDefFoundError` 风险，必要时用 dex2smali 注入对平台类的
  空实现替换，或仅使用不依赖该 API 的入口（实测 spike 仅用 `ApkModule.loadApkFile` /
  `getTableBlock` / `setPackageName` / `add(InputSource)` / `writeApk`，未触发冲突）。
- 第三方启动器声明的「CUSTOM_ICON_PICKER」category 必须与对应 launcher 主题 action
  配套出现，单独声明 nova `THEME` 但不挂 `com.novalauncher.category.CUSTOM_ICON_PICKER`
  会让部分 nova 派生启动器（lawnchair 在某些版本下）忽略此包。
- 包名策略固定为 `dev.artplus.iconpack.<hash8>`，与原包不冲突，可在 launcher 中切换多个补全包。
- `MainActivity` 的 class name 在改包名时**不会自动跟随**，需要 manifest 后处理阶段显式重写。
- M1 计划：把桌面 `Spike6.java` 移植进 app 模块（Kotlin），加 appfilter 解析器 + drawable 双通道
  解析（包已安装 → `createPackageContext(pkg).assets`；未安装 → ARSCLib `ApkModule` 直读）。