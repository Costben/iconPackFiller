# ADR 0002: Miuix 导航结构、页面转场与状态归属

## Context

M6 阶段的 Compose UI 最初是单文件 `Home.kt` 内的 `when (state.step)` 静态切换：
五步流程（选择/确认/生成/打包/完成）共享一个外层 Scaffold，页面切换没有任何转场动画，
设置页是 `if (showSettings) { SettingsScreen(); return }` 的内联切换（不在返回栈内）。
问题：
1. 页面切换零动画，与 Miuix 设计语言完全不符；
2. 顶栏设置入口是文字按钮而非标准齿轮图标（miuix 标准做法是 `IconButton` + `MiuixIcons.Settings`）；
3. `UiState.step` 同时承担「导航位置」与「业务阶段」两个职责，接导航库后会产生双事实源冲突。

同期调研了 KernelSU-Style-UI-Kit（GPL-3.0，与本项目同许可证）与 compose-miuix-ui/miuix
（Apache-2.0，`miuix-navigation3-ui` 提供 MIUI 风格的 Navigation3 封装）。
KSU kit 的架构：ViewModel 持有 `Navigator`（`SnapshotStateList<NavKey>`），页面通过
`NavDisplay(backStack, entryDecorators, onBack, entryProvider)` 渲染，每个页面自带
Scaffold + TopAppBar（转场时顶栏随页面一起滑动）。

## Decision

**引入 `miuix-navigation3-ui-android:0.9.3` 做页面级导航与转场，全用其默认参数。**

具体决策（与用户逐项确认）：

1. **导航库**：用 miuix 官方 `NavDisplay`，不自写 `AnimatedContent`。默认转场 = 新页右侧滑入
   （500ms）+ 旧页左移 1/4，弹簧 easing（`NavTransitionEasing(0.8, 0.95)`）；附带 MIUI 特有的
   圆角裁剪、背景遮罩 0.5、转场中阻断输入。显式声明 `navigation3-runtime:1.1.4` 与
   `navigationevent-compose:1.1.2`（对齐 miuix POM 的传递版本，避免隐式升级破坏编译期 API）。

2. **GENERATE 与 PACK 合并为单个 Progress 页**。这两步由编排器自动推进（非用户操作），
   若各占一个 NavKey，生成完成时 backstack 会自动 push，用户会看到「运行中回退」的语义歧义。
   合并后 5 个 Step 映射为 4 个页面级 NavKey + 1 个 Settings：Pick / Review / Progress / Done。
   页面标题由新增的 `UiState.phase: Phase { IDLE, ANALYZING, GENERATING, PACKING }` 驱动
   （"生成中" / "打包中"）。

3. **backstack 归属 ViewModel**（`MainViewModel.navigator`）。`UiState.step` 被移除，
   导航位置以 backstack 为唯一事实源，业务阶段以 `UiState.phase` 表达——两个关注点解耦。
   导航栈流转：
   - 启动 `[Pick]`；扫描完成 push `Review`；点开始生成 push `Progress`
   - 生成成功：`resetTo(Pick, Done)`（不保留 Review/Progress 的已完成态）
   - 失败：`popTo(Review)`（可改参数重试）
   - Done 返回：pop 回 `Pick`，session 保留（Pick 页出现「上次结果」卡片可重新导出）
   - 「再做一个」：清 session + `resetTo(Pick)`

4. **每页各自持有 Scaffold + TopAppBar**（KSU kit 同构）。转场时顶栏随页面一起滑动，
   而不是外层固定顶栏只换标题。

5. **设置 = 独立 NavKey**（`Route.Settings`），从右上角 `IconButton` + `MiuixIcons.Settings` push 进入，
   系统返回手势 / 左上角 `IconButton` + `MiuixIcons.Back` pop。Pick 页底部的重复「设置」文字按钮删除。

6. **运行中返回 = 确认框**（`WindowDialog`，标题「取消生成？」，按钮「继续生成/取消生成」）。
   `NavDisplay.onBack` 拦截：`state.running` 时不 pop、改为弹框（转场自动回弹到 Progress 页），
   非运行中正常 pop。进度页的「取消」按钮是显式操作，直接取消不二次确认。
   对话框在任务结束（`running == false`）时自动关闭。

7. **不做进程死亡恢复**：`Route` 是纯 `data object : NavKey`，不加 kotlinx-serialization /
   `@Parcelize`。生成协程本身无法跨进程恢复，恢复导航栈到 Progress 页反而错误。

8. **局部动画**：列表条目 `Modifier.animateItem()`；错误卡 `AnimatedVisibility(expandVertically+fadeIn)`；
   进度条数值 `animateFloatAsState` 包裹；步骤指示器（"选择·确认·…"）删除——每页 TopAppBar
   大字标题已表达当前位置，向导式步骤条与 MIUI 惯例不符。

## Consequences

- **顺带修复了一个隐藏 ANR**：`FillerOrchestrator.run()`（位图合成 / ARSCLib 打包 / apksig 签名）
  原先在 `viewModelScope.launch`（主线程）执行，完整跑一次生成必然 ANR。现改为
  `withContext(Dispatchers.IO)` 包裹全部编排，导航与状态更新留在主线程。
- 文件结构重组为 `ui/App.kt`（NavDisplay 装配）+ `ui/navigation/{Route,Navigator}.kt` +
  `ui/screen/*.kt`（五个页面）+ `ui/component/{MiuixScreen,ErrorCard}.kt`；删除单文件 `Home.kt`。
- `miuix-blur` 无法接入：其 `minSdk 33` 与本项目 `minSdk 26` 冲突（本 UI 未使用模糊 API，
  故不引入）。
- backstack 不进 `rememberSaveable`：旋转屏幕时由 ViewModel 存活；进程被杀则回到 Pick 页，
  这是正确语义（没有任务在运行，恢复到 Progress 页无意义）。
- 验证方式：126 个 JVM 单测回归 + 真机 192.168.31.216 逐帧抓取转场中间帧
  （0.2s 滑动中 / 0.5s 完成），确认 Pick→Review→Progress→Settings 各转场、取消确认框、
  任务完成自动关闭对话框、Done→Pick 返回栈语义全部符合预期。
