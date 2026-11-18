# B1a 完成报告：新界面骨架（AM Folio 15）

- 分支：`b1-gemini`（基于 `feat/page-cache` `aefdb83`）
- 包名：`dev.amatv.flyme`（构建变体 `flymeGecko`）

---

## 1. 做了什么（按任务第 2 节 1–6 逐项对照）

### 2.1 开关与窗口挂载（`SettingsStore` + 旧设置页 + 新设置页 + `MainActivity`）
- **`SettingsStore` / `SettingsState`（`app/src/main/java/dev/amatv/core/SettingsStore.kt`）**：
  - 新增持久化布尔字段 `tryNewUi: Boolean = false`（默认关闭）。
  - 在 `SettingsStorage` 与 `SharedPreferencesSettingsStorage` 中以 `KEY_TRY_NEW_UI = "try_new_ui"` 读写于 `am_atv_flyme_playback` SharedPreferences，并在 `SettingsStore` 提供 `setTryNewUi(enabled: Boolean)`。
- **旧界面切换入口（`FlymeMediaSettings.kt`、`AtvApp.kt`）**：
  - 新增 `FlymeTryNewUiSettings(settingsStore)`，并在 `AtvApp.kt` 的 `cacheSettingsContent` 顶部挂载「试用新界面：开启 / 关闭」按钮（多语言文案位于 `values/flyme.xml`、`values-zh-rCN/flyme.xml`、`values-zh-rTW/flyme.xml`）。
- **新界面切回入口（`FolioSettingsPage.kt`）**：
  - 遵循 H5「设置是『现在收听』左边一页，无顶栏齿轮」的设计，将「试用新界面」开关放在第 0 页「设置」页第一个分组下，关闭后即刻切回旧界面。
- **`MainActivity` 与小窗 `MiniWindowActivity`（`MainActivity.kt`）**：
  - `MainActivity` 订阅 `settingsStore.state`，在 `BuildConfig.FLYME_AUTO && settings.tryNewUi` 时渲染 `FolioApp()`，**不套 `CarLayout`**，并打开 edge-to-edge（透明状态栏与导航栏，图标明暗随主题同步）；关闭时恢复旧界面 `CarLayout { AmAtvApp() }` 与系统栏行为。
  - `MiniWindowActivity` 继承自 `MainActivity`，开启开关后小窗同样直接以真实窗口尺寸运行 `FolioApp()`。

### 2.2 新包 `dev.amatv.flyme` 与边界遵守
- 全部新界面代码均放在 `app/src/main/java/dev/amatv/flyme/` 下：
  - `FolioTier.kt`：密度换算与五档布局规格。
  - `FolioTheme.kt`：明暗两套主题颜色、圆角、两档动效时长、字体加载及双径向渐变背景。
  - `FolioComponents.kt`：公共组件。
  - `FolioPivot.kt`：Metro 翻页标题栏与坐标/遮罩计算。
  - `FolioBrowsePages.kt`：「现在收听」「最新发行」「资料库」「搜索」页面与 `contentStore` / `playbackStore` 衔接。
  - `FolioSettingsPage.kt`：「设置」页（接 `settingsStore`、`contentCache`、`LogRecorder`）。
  - `FolioAccountOverlay.kt`：右上/左上圆形账号按钮呼出的滑动账号浮层。
  - `FolioApp.kt`：新界面根组件（状态读取与事件分发，不持有任何业务状态，仅保留页面、账号浮层、搜索框输入等界面状态）。
- **复用的纯图片加载工具**：仅复用了 `dev.amatv.ui.MotionArtwork`（来自 `ui/Artwork.kt`，封装在 `FolioArtwork` 中）与 `dev.amatv.ui.ArtworkImages`（缓存大小查询与清理）。未 import 任何 `dev.amatv.ui` 旧界面布局、焦点或歌词组件。
- **未修改禁止改动的代码**：核心层除按要求为 `SettingsStore` 增加 `tryNewUi` 外，未改其余行为；未改播放、队列、MusicKit、媒体会话与 `WindowHosts`。

### 2.3 五档排版与车机密度补充（`FolioTier.kt`）
- 实现纯函数 `tierOf(widthDp: Float, heightDp: Float)`（及 `Dp` 重载），严格按 H5 规则顺序判断：
  1. `widthDp / heightDp >= 2.6f -> FolioTier.Ultrawide ('u')`
  2. `widthDp < 600f -> FolioTier.Narrow ('c')`
  3. `heightDp < 480f -> FolioTier.Short ('m')`
  4. `widthDp < 840f -> FolioTier.Medium ('t')`
  5. `else -> FolioTier.Wide ('e')`
- 实现纯函数 `folioRenderDensity(systemDensity)` 与 `folioLayoutDp(windowWidthPx, windowHeightPx, systemDensity)`：系统密度低于 `1.5` 时统一按 `1.5f` 换算并覆写根部 `LocalDensity`，确保 `1920×1080@160dpi` 换算为 `1280×720 dp`（`Wide`），`3840×1080@160dpi` 换算为 `2560×720 dp`（`Ultrawide`），密度 `>= 1.5` 的手机/平板保持系统原密度。
- `specForTier` 映射五档全部尺寸参数（`px`、`pr`、`ib`、`capw`、`pvh`、`pvf`、`pvg`、`secf`、`cw`、`gw`、`rowh`、`liw`、`lic`、`lif`、`lstGap`、`capsp`），与 `flyme15-app.html` CSS 变量一一对应。

### 2.4 主题与两档动效（`FolioTheme.kt`）
- 完整对照 H5 `:root` 与 `.app.light` CSS 变量实现 `DarkFolioPalette`（`#131016` 底、`#FF5C72` 强调色、顶部 `#3A1824` 与左下 `#10242A` 椭圆径向光晕）与 `LightFolioPalette`（`#F5F3F6` 底、`#C8243F` 强调色、顶部 `#F7DDE3` 与左下 `#DDEEF0` 椭圆径向光晕）。
- 明暗通过 `rememberFolioIsLight` 调用 `SettingsState.isLight(isSystemInDarkTheme(), minuteOfDay)` 跟随现有外观设置。
- 定义 `FolioMotion.FAST_MS = 200`（0.2 秒：开关、chip 选中、淡入淡出）与 `FolioMotion.SLOW_MS = 600`（0.6 秒：切页与主题配色切换，配 `CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)`）。
- 新建 `FolioFonts`，加载包内可变字体 `R.font.inter_variable` 与 `R.font.noto_sans_sc_variable` 并注册 `300..900` 字重，使标题与列表行还原 H5 `font-weight: 300` 的纤细观感。

### 2.5 根界面骨架（`FolioApp.kt`、`FolioPivot.kt`、`FolioBrowsePages.kt`、`FolioSettingsPage.kt`、`FolioAccountOverlay.kt`）
- **Metro 翻页标题栏（`FolioPivot.kt`）**：
  - 页序：`0 = 设置`、`1 = 现在收听（初始默认页）`、`2 = 最新发行`、`3 = 资料库`、`4 = 搜索`。
  - 使用纯函数 `computePivotPlacementForPage` 实现 H5 `at = prv ? min(px + prv.width + gap, pivotWidth / 3) : px` 计算；支持左右 `HorizontalPager` 滑动手势与点击标题平滑切页；前一页标题淡化（`0.30` 透明度）且当超过 `1/3` 屏宽时从左边缘裁剪并叠加 `18.dp` 渐变淡出遮罩。
- **内容接入 `contentStore` 与 `playbackStore`**：
  - **现在收听**：`contentStore.state` 货架渲染为「分区标题 + 多列大字列表行」。
  - **最新发行**：`contentStore.state` 货架渲染为「分区标题 + 自适应封面网格卡片」。
  - **资料库**：未登录时提示登录；已登录显示 H5 四类筛选胶囊（`歌单 / 专辑 / 歌手 / 歌曲`），分别以网格卡片与歌曲行呈现，并接入 `contentStore.loadMoreLibraryShelf` 滚动分页及失败重试；演示模式下自动回退展示 `demo-library` 货架。
  - **搜索**：圆角搜索栏触发 `contentStore.search`，展示快捷/最近建议词与结果分区的歌曲行、专辑列表行。
  - **点击行为**：点歌曲或电台调用 `playbackStore.playTrack(...)`；点专辑或歌单在无详情页栈的前提下直接读取 `cachedCollectionTracks` / `savedCollectionTracks` / `collectionTracks`（演示模式取 `MockCatalog.tracks`）并调用 `playbackStore.playCollection(...)` 立即播放；点歌手因 B2 未做详情页，以轻提示告知。
- **底部 / 顶栏播放状态展示**：
  - 数据全部读自 `playbackStore.state`（`hasCurrentTrack`、`track`、`playWhenReady`）。
  - `Narrow` / `Short` / `Medium` 档在底部显示居中限宽 `520.dp` 的 `FolioMiniPlayerCapsule`；`Wide` / `Ultrawide` 档在顶栏右侧显示带两层环边强调的 `FolioMiniPlayerDot`。
  - `FolioApp` 暴露 `onOpenPlayer: () -> Unit = {}` 参数，B1a 阶段传空实现不做页面展开。
- **系统栏避让**：
  - 在覆写 `LocalDensity` 之后读取 `WindowInsets.statusBars` / `displayCutout` 顶部安全距离与 `WindowInsets.navigationBars` 底部安全距离，分别应用在顶栏顶部偏移（`statusTop + 12.dp`）、底部胶囊距底偏移（`navBottom + 14.dp`）及各列表底部留白（`navBottom + capsp + 28.dp`）。小窗下系统栏高度为 `0.dp`，自动贴合小窗边缘。

### 2.6 公共组件（`FolioComponents.kt`）
- 已实现 `FolioSectionTitle`、大字列表行 `FolioListRowCard`、卡片 `FolioGridCard`、歌曲行 `FolioSongRow`（含编号 / 播放均衡器条 / 封面、「歌词」边框标签、「E」标、等宽 tabular 时长、当前曲目高亮）、`FolioMiniPlayerCapsule`、`FolioMiniPlayerDot`、`FolioCircleButton`、`FolioChip`、`FolioSwitch`、`FolioSegmentedPills` 与 `FolioAdaptiveGrid`。

---

## 2. 文件清单

### 修改的文件
1. `app/src/main/java/dev/amatv/core/SettingsStore.kt`（增加 `tryNewUi` 状态、存储与 setter）
2. `app/src/main/java/dev/amatv/MainActivity.kt`（接入新界面切换条件与系统栏透传控制）
3. `app/src/main/java/dev/amatv/ui/FlymeMediaSettings.kt`（新增旧设置页「试用新界面」开关组件 `FlymeTryNewUiSettings`）
4. `app/src/main/java/dev/amatv/ui/AtvApp.kt`（在旧设置区放入 `FlymeTryNewUiSettings`）
5. `app/src/main/res/values/flyme.xml`
6. `app/src/main/res/values-zh-rCN/flyme.xml`
7. `app/src/main/res/values-zh-rTW/flyme.xml`
8. `app/src/test/java/dev/amatv/core/SettingsStoreTest.kt`（更新 `FakeSettingsStorage` 并补充 `tryNewUi` 默认值、读取、持久化测试）
9. `app/src/test/java/dev/amatv/core/PlaybackStoreTest.kt`（仅同步更新内部 `FakeSettingsStorage` 实现新增接口方法）

### 新增的文件
1. `app/src/main/java/dev/amatv/flyme/FolioTier.kt`
2. `app/src/main/java/dev/amatv/flyme/FolioTheme.kt`
3. `app/src/main/java/dev/amatv/flyme/FolioComponents.kt`
4. `app/src/main/java/dev/amatv/flyme/FolioPivot.kt`
5. `app/src/main/java/dev/amatv/flyme/FolioBrowsePages.kt`
6. `app/src/main/java/dev/amatv/flyme/FolioSettingsPage.kt`
7. `app/src/main/java/dev/amatv/flyme/FolioAccountOverlay.kt`
8. `app/src/main/java/dev/amatv/flyme/FolioApp.kt`
9. `app/src/test/java/dev/amatv/flyme/FolioTierTest.kt`
10. `app/src/test/java/dev/amatv/flyme/FolioPivotTest.kt`

---

## 3. 和 H5 不一致的地方

1. **矮档（`Short / m`）底部控件形式**：
   - 说明：任务说明第 5 条括号中提及「窄、中档是玻璃胶囊……宽、超宽、矮档按 H5（车机封面圆点等）」，但作为唯一标准的 `flyme15-app.html` 实际规则为 `.app.t-c, .app.t-m, .app.t-t { --capsp: 84px }`（窄、矮、中三档均在底部显示玻璃胶囊，仅 `.t-e` 宽档与 `.t-u` 超宽档改为右上角封面圆点）。本实现严格以 `flyme15-app.html` 为准，令 `Short (m)` 使用底部玻璃胶囊。
2. **玻璃胶囊背景高斯模糊（Android 11 限制）**：
   - H5 使用 `backdrop-filter: blur(26px) saturate(180%)`；由于目标车机为 Android 11（API 30），Compose / RenderEffect 背景实时模糊（`Modifier.blur` 等）无法直接作用于背后滚动内容。当前使用与 H5 一致的半透明玻璃填充色 `palette.glass` + 顶部高光渐变边框模拟通透层次，无额外兼容垫片。
3. **动态封面（`.mo` CSS 漂移效果）**：
   - 在列表中 `FolioArtwork` 统一传入 `animate = false`（仅展示静态封面，不播放背景漂移视频以保持浏览列表流畅，待 B1b 播放页再启用动态视频封面）。
4. **设置页具体条目与真实开关映射**：
   - H5 设置页中包含部分原型占位项（如开关态写死的按钮和模拟存储数字 `"212 MB"`）。本实现改为全部直连真实 `SettingsStore`、`ContentCache` 与 `LogRecorder`，并增加了「试用新界面」开关以便切回旧界面。
5. **字体变量与字重 300 渲染**：
   - 因 `FolioFonts` 注册了 `wght 300..900`，具体在车机上显示与浏览器微调间距仍需以模拟器真机渲染截图为准。

---

## 4. 没做或不确定的地方

- **未做（留待后续任务）**：
  - 播放页（B1b）：当前 `onOpenPlayer` 留空，点击胶囊/封面圆点不打开播放页。
  - 详情页栈（B2）：点击专辑/歌单直接加载曲目并调用 `playbackStore.playCollection` 播放；点击歌手弹出「歌手详情页将在后续阶段提供」提示，尚未建立详情页栈。
  - 模拟器运行截图：按规则未启动或操作任何模拟器，未实际在模拟器 / 实车上目视验证渲染像素、手势手感与 edge-to-edge 系统栏最终像素偏移。
- **不确定的地方**：
  - `noto_sans_sc_variable.ttf` 在 Android 11（API 30）上经 `Font.Builder` 设置 `'wght' 300` 后的实际笔画粗细表现尚未经真机/模拟器目视确认。
  - Flyme 车机实际 `MiniWindowActivity` 切换进小窗时 `WindowInsets` 与 `density` 变化后的首帧实机表现尚未实测。

---

## 5. 单测数量与构建结果

- **验收命令**：
  ```bash
  export JAVA_HOME="D:/Data for Apps and etc/Codex/tools/jdk17/jdk-17.0.11+9"
  export ANDROID_HOME="D:/Data for Apps and etc/Codex/am_atv/work/android-sdk"
  ./gradlew -PAM_DEVELOPER_TOKEN_FILE="D:/Data for Apps and etc/Codex/am_atv/work/upstream-public-developer-token.jwt" -Pflyme.uiVerification=true -Pflyme.previewAbi=arm64-v8a :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko
  ```
- **单测结果**：`app/build/test-results/testFlymeGeckoUnitTest` 共 **79 个测试类 / 516 项单元测试**（原有 506 项 + 新增 10 项：五档边界与密度转换单测、Metro 标题定位与遮罩单测、`tryNewUi` 设置存取单测）：
  - 通过：**516 / 516**
  - 失败：**0**
  - 错误：**0**
  - 跳过：**0**
- **打包结果**：`:app:assembleFlymeGecko` 构建成功（`BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`，大小 193,537,238 字节）。

---

## 第二轮

对照 `B1A-FIX.md` 的返工要求，逐项核对与修改如下：

1. **车机密度核对（`FolioTier.kt`、`FolioApp.kt`、`FolioTierTest.kt`）**：
   - 核对确认 `FolioTier.kt` 的纯函数 `folioRenderDensity(systemDensity)` 与 `folioLayoutDp(windowWidthPx, windowHeightPx, systemDensity)`，以及 `FolioApp.kt` 先按物理像素换算并覆写根 `LocalDensity` 再读取 `WindowInsets` 的顺序已满足规则：系统密度 `< 1.5` 时按 `1.5f` 渲染、`>= 1.5` 保持不变。
   - 在 `FolioTierTest.kt` 的 `densityClampsLowDensityDisplaysToOnePointFiveAndConvertsDp` 补充 `2560×720@160dpi → 1706.67×480 dp（Ultrawide / u 档）` 用例，与 `1920×1080@160 → 1280×720 dp（e 档）`、`3840×1080@160 → 2560×720 dp（u 档）` 一并覆盖。
2. **底部玻璃胶囊背景透明度与可读性（`FolioTheme.kt`、`FolioComponents.kt`、`FolioTierTest.kt`）**：
   - 改动与取值理由：在 Android 11 无系统级实时背景模糊的前提下，为 `FolioPalette` 新增专用于胶囊的磨砂背景色 `capsuleBg`（不改变其他普通控件用的 `glass` 与车机/带鱼屏封面圆点 `FolioMiniPlayerDot`）：
     - **深色主题**：`capsuleBg = Color(0xE628252D)`（即底部背景 `#131016` 与约 10% `#FFFFFF` 混合后的深炭紫底色 `#28252D` 叠加 `0xE6 ≈ 90.2%` 不透明度）。仅余 ~10% 穿透亮度，使胶囊下方滚动的白字列表不再可辨认，但彩色封面/亮块滚过时仍保有轻微明暗晃动；同时保留 `glassHighlightTop -> glassb` 渐变描边、`White 6% -> Transparent` 顶部微反光与 `18.dp` 阴影。
     - **浅色主题**：`capsuleBg = Color(0xEBFBF9FC)`（`#F5F3F6` 与高比例白混合的浅雾白底色 `#FBF9FC` 叠加 `0xEB ≈ 92.2%` 不透明度），仅余 ~8% 穿透率以压住下方深色 `#17151C` 文字，搭配顶部 `White 16% -> Transparent` 高光、边框及 `10.dp` 阴影。
   - 在 `FolioTierTest.kt` 新增 `capsuleBackgroundKeepsHighOpacityWithoutBlur` 单测约束两套主题胶囊不透明度均 `>= 0.90f`。
3. **冷启动页与大/小窗同进程切页记忆（`FolioApp.kt`、`FolioPivotTest.kt`）**：
   - 移除原本读取 `playbackStore.navigation.value` 作为 `initialPage` 的逻辑（不改核心层）。
   - 在 `FolioApp.kt` 新增进程级、不持久化的 `internal object FolioUiSession`，初始页固定为 `HOME_PAGE_INDEX = 1`（`现在收听`）；用户滑动或点击切页时通过 `LaunchedEffect(pagerState.currentPage)` 更新 `FolioUiSession.onPageChanged(...)`。冷启动（新进程）必定回到「现在收听」，而在同一进程内通过 `WindowHosts` 在 `MainActivity` 与 `MiniWindowActivity` 之间销毁并重建 Activity 时可保留当前所在页。
   - 在 `FolioPivotTest.kt` 新增 `coldStartDefaultsToListenNowAndInProcessSwitchesPreservePage` 单测验证冷启动初始页为 `1`（`现在收听`）及同进程页面记忆。
4. **第二轮验收结果**：
   - 修改/新增代码文件：`app/src/main/java/dev/amatv/flyme/FolioTheme.kt`、`app/src/main/java/dev/amatv/flyme/FolioComponents.kt`、`app/src/main/java/dev/amatv/flyme/FolioApp.kt`、`app/src/test/java/dev/amatv/flyme/FolioTierTest.kt`、`app/src/test/java/dev/amatv/flyme/FolioPivotTest.kt`、`B1A-REPORT.md`。
   - 完整验收命令结果：`BUILD SUCCESSFUL in 1m 20s`；`app/build/test-results/testFlymeGeckoUnitTest` 共 **79 个测试类 / 518 项单元测试全部通过（通过 518 / 失败 0 / 错误 0 / 跳过 0）**；APK 产物 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`（194,078,405 字节）。
