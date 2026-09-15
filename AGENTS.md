# IconPackFiller 仓库规则（agent 必读）

## 工作约束

- **测试设备**：OnePlus 8 Pro（`192.168.31.216:5555`，Android 16，已 root，Magisk + KernelSU）。
  任何真机验证只能在 `192.168.31.216` 上进行。
- **设备 165 / 192.168.31.179 等其他地址**：禁止操作。即便 `adb devices` 列表里出现，
  也不允许 `install` / `shell` / `push` / `pull`，没有用户指令触碰。
- **构建工具**：`/Users/rinshibuya/Library/Android/sdk`（已就绪），`./gradlew :app:assembleDebug`
  通过即视为 M0/M1 基本可用。
- **不引入端上 aapt2 / android.jar**：打包走 ARSCLib + apksig 路线。
- **commit 风格**：除非用户明确要求 commit，否则不要 `git add` / `git commit`。
  commit 信息不加任何署名 trailer（无 Co-Authored-By / Generated with）。

## 路由与流程

按 `intake → inspect → design → execute → verify → deliver` 推进。

每项任务先输出路由回执，再按阶段走：

- intake: 解析任务目标，确认范围；M0 阶段固定为「方案 B spike + 5 启动器矩阵」。
- inspect: 列环境（SDK、ARSCLib 是否可缓存、apksigner 版本、启动器 APK 是否已下载、测试设备是否在线）。
- design: 落到 ADR；M0 已写 `docs/adr/0001-packaging-and-compat-matrix.md`。
- execute: 先桌面 JVM spike 验证 API 可用性，再用真机验证 APK 行为。
- verify: 真机 ADB 验证 + aapt2 dump + apksigner verify 三件套。
- deliver: 产物列表 + 变更 + 未决项。

## 关键决策速查

- 打包方案：**方案 B（ARSCLib 原地增量注入）**。方案 A 仅作 B spike 失败时回退。
- 包名策略：`dev.artplus.iconpack.<hash8>`；与原包不冲突，可切换多个补全包。
- intent-filter：manifest 必须声明 ADW / Nova / Lawnchair / Smart / MS / AndDoes / Atom /
  GO 八个 launcher 的 theme action + `MAIN + com.anddoes.launcher.THEME` category +
  `com.novalauncher.THEME + com.novalauncher.category.CUSTOM_ICON_PICKER` category。
- `MainActivity` 的 class name 必须跟随 `setPackageName` 改写（ARSCLib 不会自动改）。

## 许可证

GPL-3.0-or-later（与 ArtPlus 一致）；移植 ArtPlus 代码即派生。第三方组件 NOTICE 见
`THIRD-PARTY-NOTICES.md`。

## 控制命令

用户可用：

- `[[CB:STATUS]]`：查看当前路由阶段与里程碑完成度。
- `[[CB:RESET]]`：重置临时覆盖。
- 显式指令切换任务预设（builder / research / creative / ishii）。

## 里程碑概览

| M | 标题 | 关键产物 |
|---|---|---|
| M0 | 打包方案 spike + 兼容性矩阵 | spike APK + `docs/adr/0001-*` + `docs/launcher-compat.md` |
| M1 | appfilter 解析器 + drawable 双通道解析 | Kotlin module、unit test |
| M2 | 覆盖差集 | `CoverageReport` |
| M3 | ImageProvider 抽象 | `ImageProvider` interface + OpenAI/Gemini 实现 |
| M4 | 参考对 + ContactSheet + 提示词 | golden snapshot test |
| M5 | 打包器产品化 | 端上 ARSCLib 注入 + 签名 |
| M6 | Compose UI | 单 Activity 多步骤页 |