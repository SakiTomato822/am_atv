# M1-PLAYER 实施报告（第 1、2、3、5 条）

- 分支：`c2-player`（基于 `c3-merge` 演进后的 `3cf0860`）
- 任务范围调整说明：根据中途协作分工调整，本轮仅保留 **第 1、2、3、5 条**改动；**第 4 条（海报歌词 4a/4b/4c）已完整还原至 `c3-merge`（`3cf0860`）状态**，交由另一模型处理。

---

## 一、海报代码还原清单（第 4 条回退）

已完成以下海报歌词相关改动回退，确保 `3cf0860` 行为原样保留：
- `app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt`
  - `POSTER_ROLL_DURATION_MS` 恢复为 `550`；
  - 移除 `buildPosterDiscreteBoundaries`、`findPosterBoundaryIndex`、`PosterWordLayoutMetric`、`PosterRowSweepState`、`computePosterRowSweepState` 及多余的 `LyricTimeline` 导入；
  - `projectPosterLyrics` 未作改动（保持 `3cf0860` 原逻辑）。
- `app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt`
  - 从 `@Composable internal fun FolioNoLyricsStateBox(` 之前整段（涵盖 `FolioPosterLyricsPanel`、`PosterLeadLineText`、`PosterRollKey`、`PosterStageKey`、`PosterRollStage`、`PosterLeadHeightCacheKey`、`CharTimingEntry` 等）完整还原为 `3cf0860` 实现，参数签名恢复原状；
  - 仅保留该文件内与第 1、3 条相关的 `FolioNoLyricsStateBox` / `RetryLyricsPill` / `FolioQueuePanel` / `FolioQueueControlBar` 改动。
- `app/src/main/java/dev/amatv/flyme/FolioPlayer.kt`
  - 4 处 `FolioPosterLyricsPanel` 调用维持 `3cf0860` 原参数。
- 单测中未加入第 4 条相关用例。

---

## 二、逐条实现说明（第 1、2、3、5 条）

### 第 1 条：公共按压反馈（`FolioPress.kt`）与默认水波纹替换
- **新建 `app/src/main/java/dev/amatv/flyme/FolioPress.kt`**（不依赖播放器私有状态，后续浏览页可直接复用）：
  - 提供 `FolioPressDefaults`（卡片/列表 `0.97f`、紧凑圆形按钮 `0.92f`、spring `dampingRatio = 0.8f, stiffness = 600f`、深色覆盖 `Color.White@0.08f` / 浅色覆盖 `Color.Black@0.06f`、遮罩淡入淡出 `120ms`）；
  - 提供 `LocalFolioReducedMotion`、`LocalFolioLowPerformance` 供内层控件统一读取开关；
  - 提供纯计算函数 `resolveFolioPressScale`、`resolveFolioPressOverlayAlpha`（`lowPerformance` 与 `reducedMotion` 下缩放恒为 `1f`）；
  - 提供 `Modifier.folioPressFeedback(interactionSource, ...)` 与 `Modifier.folioPressable(...)`，缩放与遮罩仅在 `graphicsLayer` / `drawWithContent` 阶段读取，并关闭 Android 默认 `indication`（消除蓝色水波纹）。
- **播放页全面替换 `.clickable`**：
  - 替换 `FolioPlayer.kt`、`FolioPlayerControls.kt`、`FolioPlayerContent.kt` 中按钮、行项、专辑标题/封面点击；保留外围空白揭示区域及透明进度点击条（`FolioCirclePlayHairlineBar`）为无按压缩放的 `indication = null` 点击区（行内超链接保持 `LinkAnnotation.Clickable`）。
  - **缩起态胶囊与内嵌 Play/Pause 分离（`FolioPlayerCollapsedCapsule`）**：外层玻璃胶囊通过 `expandInteraction` 挂载 `folioPressFeedback`，信息区 `Row` 独占 `clickable(expandInteraction, indication = null)`，右侧 Play/Pause 小圆按钮单独使用 `.folioPressable(compact = true)` 拦截点击——点播/暂停仅按钮自身缩放，不带动整条胶囊缩小。

### 第 2 条：弹窗从按钮锚点长出 & 控件切换对齐
- **歌词菜单（`译` -> `FolioLyricsDisplaySettingsOverlay`）**：
  - 在 `FolioPlayer.kt` 改为常驻挂载，由 `open = lyricsSettingsOpen` 驱动 `updateTransition`，支持关闭动画完整播完后卸载，`BackHandler(enabled = open && lyricsSettingsOpen)` 同步拦截返回键。
  - `TransportRow` 用 `LocalFolioPlayerAnchors` 记录 `译` 按钮与队列按钮的 `LayoutCoordinates`。
  - `Wide` / `Ultrawide`（`AnchoredPopover`）：用 `computeAnchorTransformOrigin` 将 `TransformOrigin` 算到 `译` 按钮中心，执行 `0.92f -> 1f` 缩放 + 淡入（开 `240ms EnterEasing` / 关 `180ms ExitEasing`）；
  - `Narrow` / `Medium` / `Short`（`BottomSheet`）：从底部 `24dp -> 0dp` 平移 + 淡入（开 `240ms` / 关 `180ms`）；
  - `lowPerformance` 保持纯淡入淡出，`reducedMotion` 为 `0ms`。
- **队列面板（`FolioQueuePopoverContainer` & `TwoColumnPlayerBody`）**：
  - `Short` / `Narrow` / `Medium` 打开或关闭 `DisplayState.Queue` 时，通过 `FolioQueuePopoverContainer` 以 Queue 按钮中心为 `TransformOrigin` 做 `0.92f -> 1f` 缩放 + 淡入（开 `240ms` / 关 `180ms`；无歌词态静态驻留队列不变）；
  - `Wide` 车机水平布局切换队列时，在 `TwoColumnPlayerBody` 中平滑退隐双栏并让队列面板从控制卡右边缘滑入（`translationX: -16dp -> 0dp`）+ 淡入（开 `240ms` / 关 `180ms`）；
  - `Ultrawide` 第三栏常驻队列与 `lowPerformance`（仅透明淡入）均按规范处理。
- **控件卡展开/收起（`FolioPlayerAnimatedControlsSlot`）**：
  - `FolioPlayerMotionTokens.CTL_TRANSLATE_MS` 从 `350ms` 调至 `320ms`（统一处于 `180..320ms` 内），同时补齐 `lowPerformance` 下关闭 `translationY` 与 `scale`、仅做透明度切换的分支。

### 第 3 条：细节动效
- **播放/暂停图标切换（`FolioAnimatedPlayPauseIcon`）**：
  - 抽出共用组件接入 `FolioCircleGlassPlayButton`、`FolioPlayerCollapsedCapsule`、`TransportRow`；通过 `AnimatedContent` 做 `0.8f -> 1f` 缩放与交叉淡入淡出（入 `200ms EnterEasing` / 出 `140ms ExitEasing`；`lowPerformance` 仅淡入淡出、`reducedMotion` 无动画）。
- **队列 Repeat / Shuffle 按钮高亮（`FolioQueueControlBar`）**：
  - 选中背景切换改用 `animateColorAsState`（`160ms` / `reducedMotion` 为 `0ms`）。
- **队列「正在播放」滚动定位（`FolioQueuePanel`）**：
  - 保持首次打开直接 `scrollToItem` 定位，后续切歌在 `!reducedMotion && !lowPerformance` 时改用 `animateScrollToItem` 平滑滚至当前曲目。

### 第 5 条：浅色主题液态玻璃与顶部深灰带修复（对照 `ref/android17-light-poster.jpg`）
- **顶部深色（深灰到透明）色带成因与修复**：
  - **成因定位**：`MainActivity` 使用透明系统栏并将浅色主题下状态栏/导航栏图标切为深色；竖屏 Soak 海报模式下 `FolioPlayerBackdrop` 从 `y = 0` 全出血铺开专辑封面（顶部 `0..34%` 未衰减）。《Appetite for Destruction》封面顶部接近纯黑，`FolioPlayer.kt` 中的 `posterScrim` 在顶部 `160dp` 无条件叠加了一层 `palette.bg.copy(alpha = 0.55f) -> 0f`——在浅色主题中 `palette.bg` 为浅灰白 `#F5F3F6`，它以 `55%` 透明度混到黑色封面之上，便形成截图顶端那条「从深灰渐变到透明」的脏色带。
  - **修复方式**：在 `FolioPlayerState.kt` 新增 `resolvePosterTopScrimColor`，在浅色主题下仅当 `useDarkHeaderInk == true`（浅色顶图配深色文字）时叠加 `lightBaseColor`；当深色封面自带足够暗背景（`headerColors.fg == Color.White`）时返回 `Color.Transparent`，避免把浅灰底色糊上黑封面；深色主题保持 `palette.bg` 不变。
- **浅色玻璃通透度与轮廓加强（`FolioGlass.kt`）**：
  - `FullA`、`BlurB` 浅色背景填充透明度由 `0.45f` 降至 `0.24f`（`0.22..0.28` 区间），边框 `border` 为 `#17151C@0.12f`，顶边高光 `highlightTop` 为 `White@0.62f`；
  - `FullA` 浅色启用更明显的折射效果 `TierAEffectsLight`（`lens(refractionHeight = 28.dp, refractionAmount = 42.dp)`，同时 B 档维持纯 blur 与 vibrancy）；
  - 仅在浅色主题为 A/B 档增加 `1dp` 白色内环高光（`onDrawSurface` 内 `inset(1.dp.toPx())`）及一层轻量浅色外阴影（`Shadow(radius = 12.dp, offset = DpOffset(0.dp, 3.dp), Color(0xFF17151C).copy(alpha = 0.08f))`），使其在平浅灰背板上也能清晰辨识为玻璃胶囊；
  - 深色主题参数与 C 档（车机 `FallbackC`）参数保持完全不变。
- **浅色文字对比度保持**：`FolioPlayer.kt` 中浅色模式 `fg2` 由 `0.62f` 提升到 `0.72f`，保障 `0.24f` 透明胶囊内副标题等次级文本的清晰度。

---

## 三、动效时长与缓动一览

| 动效项 | 时长 | 缓动 / 物理曲线 | `lowPerformance` | `reducedMotion` |
|---|---|---|---|---|
| 卡片 / 按钮按压缩放（`FolioPress`） | spring | `dampingRatio = 0.8f`, `stiffness = 600f`（卡片 `0.97f` / 圆钮 `0.92f`） | 仅遮罩淡入（无缩放） | 无缩放无延迟 |
| 按压高光/暗化遮罩 | `120ms` | 默认线性/tween | 保留（纯透明度） | `0ms` |
| 歌词菜单（`AnchoredPopover` / `BottomSheet`） | 开 `240ms` / 关 `180ms` | 开 `CubicBezier(0.2, 0.8, 0.2, 1)` / 关 `CubicBezier(0.4, 0, 0.2, 1)` | 仅透明度 | `0ms` |
| 队列弹窗 / Wide 水平滑入 | 开 `240ms` / 关 `180ms` | 同上（手机端从 Queue 钮 `0.92->1`；Wide `-16dp->0`） | 仅透明度 | `0ms` |
| 控件卡展开平移（`CTL_TRANSLATE_MS`） | `320ms`（原 `350ms`） | `EnterEasing` | 仅透明度（禁用 `translationY` / `scale`） | `0ms` |
| 播放/暂停图标切换 | 入 `200ms` / 出 `140ms` | `EnterEasing` / `ExitEasing` + `scale 0.8 -> 1` | 仅淡入淡出 | 瞬间切换 |
| 队列 Repeat / Shuffle 背景切换 | `160ms` | `tween(160)` | 保留（纯颜色/透明度） | `0ms` |
| 队列「正在播放」切歌滚动 | 系统列表平滑动效 | `animateScrollToItem` | `scrollToItem` | `scrollToItem` |

---

## 四、文件变更清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `app/src/main/java/dev/amatv/flyme/FolioPress.kt` | 新增 | 通用按压反馈修饰符、配置 locals 与纯计算函数 |
| `app/src/main/java/dev/amatv/flyme/FolioGlass.kt` | 修改 | 浅色 A/B 档透明度降为 `0.24f`、折射加强、`1dp` 内环高光与浅色外阴影 |
| `app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt` | 修改 | `FolioPlayerMotionTokens` 动效常量、`computeAnchorTransformOrigin`、`resolvePosterTopScrimColor` |
| `app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt` | 修改 | 控件点击改用 `folioPressable`、胶囊内 Play 分离、`FolioAnimatedPlayPauseIcon`、歌词菜单锚点弹窗动画、按钮坐标采集 |
| `app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt` | 修改 | `FolioQueuePanel` 切歌平滑滚动、Repeat/Shuffle `160ms` 变色动画、NoLyrics/Queue 改用 `folioPressable`（海报段落已还原至 `3cf0860`） |
| `app/src/main/java/dev/amatv/flyme/FolioPlayer.kt` | 修改 | 注入动画偏好 Locals、浅色 `fg2` 对比提升、顶栏 `posterScrim` 修复、队列弹窗开合/Wide 滑入动效、全页点击改用 `folioPressable` |
| `app/src/test/java/dev/amatv/flyme/FolioGlassTest.kt` | 修改 | 同步更新浅色 A/B 档透明度（`0.24f`）与高光断言 |
| `app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt` | 修改 | 更新 `CTL_TRANSLATE_MS = 320` 与新增动效常量检查；新增按压反馈、锚点原点、顶栏 scrim、浅色玻璃参数单测 |

---

## 五、单测与构建结果

- 执行命令（工作区根目录）：
  ```powershell
  $env:JAVA_HOME = "D:\Data for Apps and etc\Codex\tools\jdk17\jdk-17.0.11+9"
  $env:ANDROID_HOME = "D:\Data for Apps and etc\Codex\am_atv\work\android-sdk"
  .\gradlew --no-daemon '-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt' '-Pflyme.uiVerification=true' '-Pflyme.previewAbi=arm64-v8a' :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko
  ```
- 单元测试结果：`72` 个测试套件，共 **`471` 项单测全部通过**（`FAILURES=0, SKIPPED=0`；其中 `FolioPlayerStateTest` 36 项、`FolioGlassTest` 4 项）。
- 构建结果：**`BUILD SUCCESSFUL in 41s`**，成功产出 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`（`191,662,374` 字节）。
