# IconPackFiller

无 root 的 Android 应用：从任意第三方启动器图标包（appfilter APK）出发，用 AI 图像模型
为包内尚未适配的本机应用生成同画风图标，重新打包成可安装的完整合并包 APK。

工作代号 IconPackFiller。所属关系：与 ArtPlus（root 版）共享图标包交换格式；
本仓库不做系统写入，不依赖 Shizuku / root。

## 状态

M0–M6 均已完成：ARSCLib 原地增量打包 + Lawnchair 真机实测通过，
appfilter 解析、覆盖差集、ImageProvider（OpenAI Images / Responses / Chat）、
参考对 + ContactSheet + 提示词、端上签名、Compose 多步骤 UI 全量可用。
JVM 单测 300+ 通过；详细决策见 `docs/adr/*.md`，启动器实测见 `docs/launcher-compat.md`。

## 构建

```bash
./gradlew :app:testDebugUnitTest    # JVM 单测
./gradlew :app:assembleDebug      # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # 需要 release.jks + KEYSTORE_PASSWORD
```

构建工具：Android Gradle Plugin 9.3.2、Kotlin 2.4.10、Compose BOM 2026.08.00、minSdk 26、
targetSdk 37、compileSdk 37。

> 安全说明：`app/src/main/assets/filler-signing.p12`
> 是补全包共用的**公开身份证书**（所有补全包同证书，便于启动器识别同一来源；
> 口令硬编码在 `ApkSigner.kt`，随应用分发，人人可提取——设计如此，不构成安全边界）。
> 它只签生成的图标补全包，不签本应用。**发布本应用请用自己的 `release.jks`**
>（`KEYSTORE_PASSWORD` / `KEY_PASSWORD` 经环境变量或 `local.properties` 传入，
> 两者均不在版本库中）。

## 关键依赖

- `io.github.reandroid:ARSCLib:1.3.5`（Apache-2.0，原包原地增量注入）
- `com.android.tools.build:apksig:9.3.2`（v1+v2+v3 签名）

完整 NOTICES 见 `THIRD-PARTY-NOTICES.md`。

## 仓库结构

```
.
├── app/                     # 端上 Compose 应用（M0..M6 主体）
│   └── src/test/resources/  # 单测 fixture（test-iconpack*.apk，经 classloader 加载）
├── docs/
│   ├── adr/                 # 架构决策记录
│   └── launcher-compat.md   # 启动器兼容性矩阵实测笔记
├── .github/workflows/       # CI：单测 + assembleDebug
├── gradle/wrapper/
├── gradlew / gradlew.bat
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── AGENTS.md                # 仓库规则 / 角色 / 工作流
├── LICENSE                  # GPL-3.0-or-later
├── README.md                # 本文件
└── THIRD-PARTY-NOTICES.md
```

> 说明：`design/`（启动图标草稿）、`fixtures/`（与 `src/test/resources` 完全重复的
> 旧 fixture 副本）、`tools/spike/`（桌面 spike 草稿）与构建无关，已在 `.gitignore`
> 中忽略，仅留本地磁盘，不进版本库。

## 测试设备约束

仅在本仓库 `AGENTS.md` 列出的测试设备上做真机验证（默认 OnePlus 8 Pro, 192.168.31.216:5555）。
禁止操作任何未在指令中显式授权的设备。

## 许可证

GPL-3.0-or-later（与 ArtPlus 一致；本仓库从 ArtPlus 移植的代码为派生作品，沿用 GPL-3.0-or-later
并保留版权头）。