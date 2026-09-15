# Third-Party Notices

IconPackFiller as a whole is licensed under **GPL-3.0-or-later** (see `LICENSE`).
The following components are linked from upstream projects and remain under their
original licenses, as noted in each file header.

## Apache-2.0

- **REAndroid / ARSCLib** — https://github.com/REAndroid/ARSCLib
  - Files: end-of-module Java/Kotlin code that interacts with
    `io.github.reandroid:ARSCLib:1.3.5` (Maven Central).
  - Upstream license: Apache-2.0.
  - Changes: none — used via Gradle dependency only.

- **Android tools build:apksig** — https://developer.android.com/studio
  - Files: end-of-module Java/Kotlin code that uses `com.android.tools.build:apksig`
    for v1+v2+v3 signing.
  - Upstream license: Apache-2.0.
  - Changes: none — used via Gradle dependency only.

- **Material Design Icons** — https://github.com/google/material-design-icons
  - Files: `app/src/main/kotlin/dev/artplus/iconpackfiller/ui/component/HistoryIcon.kt`
    embeds the `action/history` 24dp path data (Miuix 图标集缺少历史语义的图标）。
  - Upstream license: Apache-2.0.
  - Changes: none — path data inlined as an `ImageVector`.

- **LobeHub Icons** — https://github.com/lobehub/lobe-icons
  (`@lobehub/icons-static-svg@1.95.0`, MIT)
  - Files: `app/src/main/res/drawable/ic_vendor_*.xml` (21 个模型厂商 logo，
    由上游 SVG 经脚本转制为 VectorDrawable：纯色路径照搬 `pathData`/`fillColor`；
    线性渐变映射为 `<gradient>`；`circle`/`ellipse` 转弧线路径；`currentColor`
    烘焙为黑色并由 `VendorBadge` 按主题着色）。
  - Upstream license: MIT.
  - Changes: format conversion SVG → VectorDrawable only, no artwork edits
    （`kimi-color` 在浅色下几乎不可见，改用单色 `moonshot`；`kolors-color`
    的径向渐变 VectorDrawable 无法精确表达，改用单色 `kolors`）。
  - Note: 各 logo 本身是其厂商的商标，仅用于模型选择器中标识模型归属。

## Other Gradle dependencies (linking only)

- AndroidX / Jetpack Compose — Apache-2.0.
- Android Gradle Plugin (AGP) — Apache-2.0.