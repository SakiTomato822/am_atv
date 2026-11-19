# G1 播放页液态玻璃实施报告

## 1. 依赖调研与版本确认

- **选用依赖**：`io.github.kyant0:backdrop:1.0.6`（在 [`app/build.gradle.kts`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/build.gradle.kts#L221) 仅新增此一行依赖，传递依赖 `io.github.kyant0:shapes:1.2.0`）。
- **版本与兼容性核对**：
  - `1.0.6` 于 **2026-02-14** 在 Maven Central 发布（已超过 2 周）。其 `AndroidManifest.xml` 声明 **`minSdk = 21`**（低于本项目 `minSdk = 28`），依赖 Kotlin `2.3.10` 与原生 Jetpack Compose (`androidx.compose.ui/foundation 1.10.3`)，无需升级本项目的 Kotlin `2.3.21`、Compose BOM `2026.06.01`、AGP `8.13.2` 或 Gradle `8.13` 即可编译打包。
  - 注：`2.0.1`（2026-08-26）依赖 Kotlin `2.4.10` 与 JetBrains Compose `1.12.0`，`2.0.0`（2026-05-28）迁移到了 `org.jetbrains.compose.*` 产物体系，故选择原生 Android Jetpack Compose 的最后一个稳定版 `1.0.6`。
- **源码核实的各效果 API 等级与低版本退化行为**（出自 `backdrop-1.0.6-sources.jar`）：
  - **模糊 `blur()`**（`effects/Blur.kt`）：要求 **API 31 (`Build.VERSION_CODES.S`)**。低于 API 31 直接 `return`，不叠加模糊 `RenderEffect`。
  - **`vibrancy()` / `colorFilter()`**（`effects/ColorFilter.kt`）：要求 **API 31 (`Build.VERSION_CODES.S`)**（`RenderEffect.createColorFilterEffect`）。低于 API 31 直接 `return`，不做鲜明度滤波。
  - **折射 `lens()`**（`effects/Lens.kt`）：要求 **API 33 (`Build.VERSION_CODES.TIRAMISU`)**（`RenderEffect.createRuntimeShaderEffect` + AGSL）。低于 API 33 直接 `return`，不做折射/色散。
  - **高光 `highlight`**（`highlight/HighlightStyle.kt`、`HighlightModifier.kt`）：
    - `HighlightStyle.Default` / `Ambient` 的方向渐变 AGSL 着色器要求 **API 33 (`TIRAMISU`)**；
    - 在 **API 31–32** 时 `createShader` 返回 `null`，仅保留均匀颜色描边与 `BlurMaskFilter`；
    - 在 **API 21–30** 时跳过 `createShader`，同样仅渲染均匀描边 + `BlurMaskFilter`；`HighlightStyle.Plain` 全版本（API 21+）可用。
  - **底层开关**：`DrawBackdropNode.updateEffects()` 仅在 API 31+ 应用 `RenderEffect`，低于 31 只剩普通背景裁剪。此外该 AAR 字节码为 Java 21（class version 65.0），在 `FolioGlass.kt` 中已将引用 `com.kyant.backdrop.*` 的预设常量收口到惰性嵌套对象 `FolioBackdropPresets`，使本地 JDK 17 运行的 JVM 单元测试不触发其类加载，Android 端经 D8 正常编入 DEX。

## 2. 三档分档实现与全部用处

分档纯函数 `resolveFolioGlassTier(sdkInt, overrideCode)` 位于 [`FolioGlass.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioGlass.kt#L68-L90)，支持读取 `am_atv_flyme_playback` 中 int 键 `glass_tier_override`（`0` = 自动，`1 / 2 / 3` = 强制 A / B / C；强制高于系统能力时自动退回系统最高支持档）：

1. **A 档（`FullA`，API 33+）**
   - 仅在 `FolioPlayerBackdrop` 外挂 `Modifier.layerBackdrop(layerBackdrop)` 捕获背景图层（不含歌词与顶栏文案）。
   - 控件通过 `drawBackdrop` 应用 `vibrancy()` + `blur(16.dp)` + 随尺寸自适应的轻微 `lens(rim <= 14.dp, refraction <= 20.dp)`，叠加 `HighlightStyle.Default` / `Ambient`、统一 `1.dp` 描边、顶部高光 sheen 与底色（深色 `0xFF1A1621 @ 0.35f`、浅色 `White @ 0.45f`）。
2. **B 档（`BlurB`，API 31–32）**
   - 复用同一 `layerBackdrop` 仅捕获背景层，`drawBackdrop` 应用 `vibrancy()` + `blur(16.dp)` + `HighlightStyle.Plain`、同等透明度的底色（深色 `0.35f` / 浅色 `0.45f`）、`1.dp` 描边与顶部高光，不执行 `lens()`。
3. **C 档（`FallbackC`，API 28–30 车机）**
   - 不创建也不录制 `LayerBackdrop`，不做实时 `RenderEffect` 模糊。
   - **背景为 Soak（封面）时**：切歌后由 `rememberFolioBlurredCoverBitmap` 在 `Dispatchers.IO` 拉取 `64px` 尺寸封面，在 `Dispatchers.Default` 执行纯 Kotlin 双遍可分离盒式模糊 `stackBlurArgb(..., radius = 4)` 缓存为 `ImageBitmap`；绘制时在各玻璃组件 `drawWithCache` 内通过实时窗口坐标差对齐背景封面的裁切位置与边缘渐隐，再叠底色（深色 `0.45f`、浅色 `0.55f`）、顶部高光与 `1.dp` 描边。
   - **背景为 Flow（流光）时**：直接绘制半透明底色（深色 `0.45f` / 浅色 `0.55f`）、顶部高光与 `1.dp` 描边，不绘制位图。
4. **接入的全部播放页玻璃组件**：
   - 控件卡（宽档 `e` 左栏紧凑卡与其余档位控件卡，`FolioPlayerExpandedControls`）
   - 折叠胶囊（`FolioPlayerCollapsedCapsule`）
   - 圆形播放键（`FolioCircleGlassPlayButton`）
   - 顶栏圆按钮底（`FolioPlayerHeader` 的 `Tt` 激活态与 PiP 小窗按钮，根据 `headerColors.fg` 自动适配明暗玻璃保证对比度）
   - 队列面板（`FolioQueuePanel` 横向/纵向队列容器，圆角 `22.dp`）及播放页提示气泡。

## 3. 修改与新增文件清单

- [`app/build.gradle.kts`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/build.gradle.kts)：新增 `io.github.kyant0:backdrop:1.0.6`。
- [`FolioGlass.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioGlass.kt)：新增三档分档逻辑、`glass_tier_override` 实时监听、统一材质配色、`ProvideFolioGlassEnvironment`、`Modifier.folioPlayerGlass`、64px 后台封面模糊与坐标对齐绘制、`stackBlurArgb`、`computeCenteredCropRect`。
- [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)：挂载 `ProvideFolioGlassEnvironment`、A/B 档背景专属 `layerBackdrop` 与 C 档根节点坐标引用。
- [`FolioPlayerBackdrop.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerBackdrop.kt)：为 Soak 封面铺底区域挂接 `bleedCoords`（仅 C 档使用），供各玻璃组件裁剪对齐。
- [`FolioPlayerControls.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)：移除旧版半透明 `folioPlayerGlass`，接入统一玻璃修饰器，将 `Tt` 激活底与 PiP 小窗圆按钮切换为液态玻璃材质。
- [`FolioPlayerContent.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt)：为 `FolioQueuePanel` 横/纵向队列列表加上 `22.dp` 液态玻璃底板。
- [`FolioGlassTest.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioGlassTest.kt)：新增 4 组纯函数单测。

## 4. 性能优化手段

- **限制取景层数**：`layerBackdrop` 仅挂在 `FolioPlayerBackdrop` 上，歌词、进度条、顶栏等高频变化层完全不进入玻璃取景图层。
- **零重组、引用稳定**：A/B 档的 `effects`、`highlight` 使用 `FolioBackdropPresets` 稳定单例并记忆 `shape` / `onDrawSurface`；C 档位置对齐使用普通可变对象 `FolioGlassCoordHolder` 在绘制期按需读取坐标，展开/平移动画不触发 Compose 重组。
- **后台单次预计算**：C 档 `64px` 封面缩放与模糊每首歌仅在 `Dispatchers.IO`/`Default` 计算一次并按 `artworkUrl` 缓存。
- **不画看不见的玻璃**：`FolioPlayerAnimatedControlsSlot` 在 `ctlAlpha`/`pmetaAlpha <= 0.01f` 时直接移出组合；`Tt` 未激活时传 `enabled = false` 不挂载任何玻璃修饰器；C 档不分配 `LayerBackdrop`。

## 5. 单测与构建验证结果

- 执行命令（`BypassSandbox = true`，`--no-daemon`）：
  `.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=..." "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- **单测结果**：
  - `FolioGlassTest`：**4 / 4** 通过（`resolveGlassTierMatchesOsCapabilitiesAndCapsUnsafeOverrides`、`glassColorsShareBordersAndHighlightsAcrossTiersWithExpectedTintAlphas`、`centeredCropRectFillsDestinationProportionally`、`stackBlurSoftensStepEdgeWhilePreservingUniformColor`）。
  - 全量 `:app:testFlymeGeckoUnitTest`（85 个测试套件）：**581 / 581** 全部通过（`FAILURES=0`、`SKIPPED=0`）。
- **编译与打包结果**：`:app:assembleFlymeGecko` **`BUILD SUCCESSFUL`**（1m 46s，无报错及新增编译警告）。
