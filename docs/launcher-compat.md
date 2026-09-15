# Launcher 兼容性笔记

> 实测设备：OnePlus 8 Pro（192.168.31.216:5555，Android 16，已 root，Magisk + KernelSU）。
> 测试 APK：`dev.artplus.iconpack.spike6`（由 `Spike6.java` 桌面生成；apksig v1+v2+v3 签名）。

## 自动发现机制（声明了的标准 theme intent-filter）

M0 spike 显式声明了所有「标准」第三方 launcher theme action：
`org.adw.launcher.THEMES` / `com.novalauncher.THEME` / `com.teslacoilsw.launcher.THEME` /
`ch.deletescape.lawnchair.ICONPACK` / `com.fede.launcher.THEME_ICONPACK` /
`com.dlto.atom.launcher.THEME` / `com.gau.go.launcherex.theme` /
`org.adw.launcher.icons.ACTION_PICK_ICON` 以及 `MAIN + com.anddoes.launcher.THEME` category、
`com.novalauncher.THEME + com.novalauncher.category.CUSTOM_ICON_PICKER` category。

`cmd package query-activities -a <action>` 在以下 action 上都命中 spike6。

## 各启动器实际行为

### Nova Launcher（com.teslacoilsw.launcher 8.9.2）
- 发现：通过 `com.teslacoilsw.launcher.THEME` action 命中 spike6。
- 应用：M0 spike 未走通，因为 Nova 首次启动要求 Google Play 计费订阅 Nova Prime，免费试用入口
  在另一台没有 Play 服务的测试机上无法打开。需要在装有 Play Services 的设备上点击「免费试用 3 days」
  进入主屏幕后再打开设置 → 外观 → 图标包才能看到。
- 备注：M1+ DoD 重测时需要在具有 Play 服务的设备上重复流程。

### Lawnchair（app.lawnchair 15.Beta 2）✅
- 发现：Lawnchair 内部用 4 个 Intent 列表查图标包（`com.novalauncher.THEME`、
  `org.adw.launcher.icons.ACTION_PICK_ICON`、`com.dlto.atom.launcher.THEME`、`MAIN + com.anddoes.launcher.THEME` category）；
  spike6 在 4 个 query 上全部命中。
- 应用：清除数据 → 启动 → 主页上滑进入设置 → 「通用」 → 「颜色、图标包与通知圆点」 →
  「图标样式」 → 「图标包」 → 选择 spike6 → 重启 launcher。`pref_iconPackPackage=dev.artplus.iconpack.spike6`
  写入 `com.android.launcher3.prefs.xml`。
- 图标覆盖：app drawer 中出现「测试图标包（补全）」条目（来源是 spike6 自身的 launcher
  activity）。Telegram 在 `databases/app_icons.db` 中保留 adaptive icon blob，14857 字节；
  spike6 内 6 条 `ComponentInfo{org.telegram.messenger/.DefaultIcon|.AquaIcon|.PremiumIcon|
  .VintageIcon|.NoxIcon|.TurboIcon}` 足以命中 launcher 选用的任一 entry（实际命中哪个取决于
  Telegram 内部的 default icon 选择）。

### Niagara Launcher（bitpit.launcher 1.16.28）
- 发现机制：Niagara 不读 standard theme action；它的 ApplyIconPackActivity 用 `bitpit.launcher.APPLY_ICONS`
  作为入口，**ThirdPartyIconPackInfo** 是它从 PackageManager 加载图标包 manifest 后构造的，
  配合 `ApplicationInfo.metaData` 资源加载（`loadXmlMetaData`）触发。spike6 在 spike 阶段没
  添加该 meta-data → Niagara 设置页「主题 → 第三方图标包」列表中目前不出现。
- 应用：未付费购买 Niagara Pro，付费墙挡住「导入第三方图标包」路径。M0 不要求端到端通过。
- 修复方向（M1）：在 spike 的 manifest application 标签下追加
  `<meta-data android:name="org.niagara.launcher.icon_pack" android:resource="@xml/icon_pack" />`，
  并把 `res/xml/icon_pack.xml` 写成像 `<icon-pack name="..." pack-version="...">` 形式。

### Smart Launcher（ginlemon.flowerfree 7.6.3）
- 发现机制：Smart Launcher 不查询 standard theme action；它通过主题 → 自适应图标 →
  「自定义」 → 「从 APK 文件导入」让用户手动选文件。spike6 在 `pm list packages` 中可见，
  但要让 Smart Launcher 识别需要经过它的「自定义 APK 导入」流程。
- 应用：M0 spike 仅验证 `pm install` 与 PackageManager 解析，没有跑完整 UI 路径。

### Microsoft Launcher（com.microsoft.launcher 6.241002.0.11160220）
- 同 Smart Launcher：通过设置 → 个性化 → 图标 → 第三方图标包手动选 APK。M0 spike 未做 UI 验证。

## 关键设计要点（M1 起继续遵循）

- `MainActivity` 的 `android:name` 在 `AndroidManifestBlock.setPackageName` 时**不会自动跟随**；
  必须在改包名后手动遍历 `getMainActivity()` 并用 `ResXmlAttribute.setValueAsString` 重写。
- 包名策略：`dev.artplus.iconpack.<hash8>`；与原包不冲突，可在 launcher 里切换多个补全包。
- drawable 命名：`ap_<pkg简写>_<序号>`（不超过 64 字符）；资源 id 由 ARSCLib 自动分配，spike
  实测在 fixture 上从 0x7f010000 开始连续分配，无空隙。
- 图标分辨率：≥192px PNG，建议 512px。spike 实测 192×192 RGBA PNG 全部被 arsc 正确注册。
- 签名：必须 apksig v1+v2+v3（apksigner 默认 v2+v3，启用 `--v1-signer-name` 显式 v1）。仅 v1
  在 Android 8- 及老旧 ROM 不可用；仅 v2+ 在 Android 11+ 可能被 Play Protect 提示。仅 v3 在
  Android 9- 不可用。