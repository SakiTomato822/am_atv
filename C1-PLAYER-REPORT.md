# C1 播放页改进交付报告（`c1-player`）

## 1. 逐项改动说明（对应 `C1-PLAYER.md` 第 1–5 条）

### 第 1 条：海报模式对唱区分歌手
- **识别规则（`FolioPlayerState.kt` `isOppositeSingerLine`）**：
  - 单人歌曲（`isOppositeAgent == false` 或 `agentId` 为空）全部视为主唱，显示效果与之前完全一致。
  - 无 `agentId` 或合唱标识（`agentId` 为 `v1000` 或包含 `group`，不区分大小写）归入主唱；仅 `isOppositeAgent == true` 且非合唱的句（例如 `v2`）标记为对唱另一位歌手。
- **双色投影与同时开口重叠（`FolioPlayerState.kt` `projectPosterLyrics` / `FolioPlayerContent.kt` `FolioPosterLyricsPanel`）**：
  - `PosterTextLine` 与 `PosterLyricsProjection` 为上一句（`.pv`）、当前大字（`.pn`）、和声/重叠小字（`.pbv`）及后续预告行（`.px`）统一带上 `isOppositeSinger` 标记。
  - 正常轮唱时，上一句、当前大字、后续预告均按各自歌手着色（主唱使用前景主色 `--pfg`：`#FFFFFF` / `#16141B`，对唱使用从封面提取并校正对比度后的 `duetAccentColor`）。
  - 两人同时唱（同一播放时刻 `activeLineIndices` 命中 2 条以上非纯和声主唱行）时，按 `(startMs, index)` 排序，**先开口的一句占大字（`.pn`），后开口的重叠句与既有纯和声一起进入 `.pbv` 小字区**（`--pfs × 0.50`、按各自歌手着色），并且预告行 `.px` 自动跳过已在小字区演唱的该句。

### 第 2 条：手机横屏（矮档 `m` / `Short`）布局对齐 H5 `.t-m`
- **尺寸参数（`FolioPlayerState.kt`）**：
  - `playerSpecForTier(FolioTier.Short).controlCardWidth` 调整为 `336.dp`（严格对齐 H5 `.t-m .ctl { width: 336px }`），保持 `--fs = 24.sp`、`--pfs = 34.sp`。
  - 新增纯函数 `shortTierLyricsStartDp(windowWidthDp)`：常见手机横屏（`>= 680dp`，如 914×411 dp）使用 `384.dp`。
- **独立双栏定位（`FolioPlayer.kt` `ShortPlayerLayout`）**：
  - 去掉原先上下挤压歌词区的纵向底栏，右半栏（`start = 384.dp`、`end = 28.dp`、`top = statusTop + 4.dp`、`bottom = navBottom + 2.dp`）全高放置滚动歌词与海报，不再受顶部 header 与底部控件挤压。
  - 左侧控件卡 `.ctl` 与收起胶囊 `.pmeta` 固定放置在左下角（`left = 16.dp`、`width = 336.dp`、`bottom = navBottom + 8.dp`），`Flow` 样式下上方可同时容纳封面。无论控件卡展开还是收起，右侧歌词区位置与尺寸保持 1 像素不变，不发生跳动。
  - 车机宽档 `e` 及其他三个档位（`c`/`t`/`u`）保持不变。

### 第 3 条：控件卡与海报换句动效（及「移除动画」适配）
- **「移除动画」系统开关检测（`FolioPlayerState.kt` / `FolioPlayer.kt`）**：
  - 通过 `isReducedMotionEnabled(Settings.Global.ANIMATOR_DURATION_SCALE, ValueAnimator.areAnimatorsEnabled())` 判断，对应 H5 `prefers-reduced-motion`；开启时所有控件切入切出、海报换句与逐字点亮均切换为无延迟直切。
- **控件卡与胶囊切换动画（`FolioPlayerControls.kt` `FolioPlayerAnimatedControlsSlot`）**：
  - `.ctl` 展开 / 收起按 H5 实现独立属性动画：透明度 `280ms`，位移 `14.dp ↔ 0.dp` 用时 `350ms`（`CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)`）；`.pmeta` 配合做 `250ms` 淡入淡出与 `300ms` 微位移缩放反转，同时由活跃态置顶绘制避免淡出过程抢占点击。
- **海报换句与逐字点亮（`FolioPlayerContent.kt` `FolioPosterLyricsPanel` / `PosterLeadLineText` / `FolioPlayerMotionTokens`）**：
  - 新句入场 `liqIn`：主行 `.pn` 用时 `800ms`、`.pv`/`.px` 用时 `700ms`（`CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)`），从 `alpha = 0f`、`translationY = 14.dp`、`scale = 0.98f`、以及仅在 API 31+（`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`）施加 `Modifier.blur(16.dp -> 0.dp)`。
  - 旧句退场残影 `liqOut`：在叠加层保留上一句快照并在 `600ms`（`CubicBezierEasing(0.4f, 0f, 0.2f, 1f)`）动画至 `alpha = 0f`、`translationY = -16.dp`、`scale = 1.03f`、API 31+ `Modifier.blur(0.dp -> 14.dp)` 后销毁；API 31 以下不做任何替代模糊效果。
  - 逐字点亮 `posterCharAlpha`：每个字在精确时间点 `charStartMs` 即刻开始点亮（不延后音频节奏），用 `200ms` 从 `0.40f` 线性亮至 `1.0f`（对应 H5 `.pn .c { opacity: .4; transition: opacity .2s }`）。

### 第 4 条：切歌时不闪「无歌词」布局
- **状态识别纯函数（`FolioPlayerState.kt` `resolveLyricsAvailability` & `resolvePlayerDisplayState`）**：
  - 区分三种状态 `FolioLyricsAvailability`：`Pending`、`Ready`、`Unavailable`。
  - 对于已登录且有 `catalogId` 的歌曲，在 `displayedLines` 尚为空但 `lyricsState.trackIdentity != currentIdentity`（仍保留上一首歌词状态）或处于 `Idle`/`Loading` 时返回 `Pending`，`resolvePlayerDisplayState` 保持用户当前选择的 `Poster` 或 `Lyrics` 布局，歌词区暂时留空而不切换到 `None`。
  - 仅当当前歌确认 `Unavailable`/`Failed`（含加载完成仍为空、非歌曲或未登录无请求）时才进入 `None`。

### 第 5 条：顶栏按钮按封面顶部明暗统一着色
- **顶部采样与明暗判定（`FolioPlayerState.kt` `computeTopStripLuminance` / `isTopBackdropLight` / `resolveHeaderUseDarkInk` & `FolioPlayer.kt` `rememberArtworkTopLuminance`）**：
  - 换歌或封面 URL 变化时才执行一次计算（直接命中 `ArtworkImages` 已解码的 `"palette:$url"` `48x48` 位图缓存并写入 64 条 `LruCache`，绝不每帧采样），取顶部 `22%` 行的 sRGB 相对亮度均值；阈值 `>= 0.50f` 判为亮顶部背景。
  - 在顶部铺满封面（`Narrow` / `Medium` 的 `Soak` 风格且非无歌词独占图）时由顶部采样亮度决定顶栏用深色墨水（`#16141B`）还是浅色墨水（`#FFFFFF`）；其余场景按当前主题明暗决定。
- **统一样式（`FolioPlayerControls.kt` `FolioPlayerHeader` & `HeaderColorScheme`）**：
  - 收起箭头、`+`/`✓`、未收藏 `☆`、`Tt` 与窗口切换 `.winb` 统一使用同一个前景色 `headerColors.fg`，**`✓` 不再使用主题红**；深色/浅色顶栏胶囊与激活态背景均由前景色衍生：
    - 暗背景前景色：`--pfg = #FFFFFF`，圆底 `--prb = White @ 0.14`，描边 `--pglassb = White @ 0.22`，`Tt` 激活底 `White @ 0.16`（H5 `.ib.on`）。
    - 亮背景前景色：`--pfg = #16141B`，圆底 `0xFF16141B @ 0.10`（H5 `.app.light .player --prb` 为 `0.08`），描边 `0xFF16141B @ 0.20`，`Tt` 激活底 `0xFF16141B @ 0.12`。

---

## 2. 修改文件清单

- `app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayer.kt`
- `app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt`
- `C1-PLAYER-REPORT.md`

（未改动核心层、旧界面或任何其它 `Folio*.kt` 文件。）

---

## 3. 颜色选择与原因

1. **对唱另一位歌手颜色 `selectDuetAccentColor`（H5 无此规则，C1 专属）**：
   - 从封面提取的 5 个色板中跳过背景主色（`palette[0]`），优先选取色度 `>= 0.16f` 且与封面主色距离最大的辅助色彩，随后向主唱相反明度锚点（暗主题向 `#FFFFFF`、亮主题向 `#16141B`）按步进混色，直至与当前背景 `baseColor` 的 WCAG 相对亮度对比度达到 **`>= 4.8`**（同时暗模式保证相对亮度 `>= 0.42f`、亮模式保证相对亮度 `<= 0.26f`），既保证一眼能看出来自封面的温柔色相，又与纯白/近黑主唱截然不同。
   - **纯黑白灰封面保底色**（H5 无对应变量）：若封面所有取色色度均低于 `0.16f`，暗背景下采用 `#8EC5FF`（经同算法对比度校正，浅钴蓝），亮背景下采用 `#285EB8`（深钴蓝），避免在黑白照片上出现两色都偏灰白难分的情况。
2. **亮封面上的顶栏玻璃圆底（对照与微调说明）**：
   - H5 `.app.light .player` 中 `--prb` 为 `rgba(22,20,27,.08)`、`.ib.glass` 依赖 `backdrop-filter` 与 `--pglassb: rgba(255,255,255,.78)`；而在浅色真实封面照片之上（且 Android 11 无实时模糊）使用深墨色低透明度底色 `0xFF16141B @ 0.10`～`0.12` 与 `0xFF16141B @ 0.20` 边框，能保证「Tt」与小窗按钮轮廓在纯白/高光黑白照片上依然清晰锐利。

---

## 4. 和 H5 不一致的地方与未做 / 不确定事项

1. **和 H5 的差异**：
   - **TTML `LyricAgentType.Group` 透传限制**：核心模型 `LyricLine` 仅保存了 `agentId: String?` 与 `isOppositeAgent: Boolean`，未保存 TTML 头部解析的 `LyricAgentType`（`Person` / `Group`）。按「不改核心层」的地盘约束，UI 层将 `agentId == null`、以及 Apple Music 惯例的合唱标识 `"v1000"` / 包含 `"group"` 识别为合唱并归为主唱；若遇到自定义 ID（如 `"v3"`）的 `Group` 类型 agent，目前会**不确定**地被视为对唱歌手。
   - **Android 11 / 低版本模糊降级**：按要求 `Modifier.blur` 严格仅在 API 31+ 执行，Android 11 车机上海报换句保留透明度、位移和缩放而不加替代模糊。
   - **`★` 已收藏态高光**：未收藏态 `☆` 及已入库 `✓`、关闭、Tt、小窗按钮均与顶栏前景色完全统一；已收藏后变为实心 `★` 仍沿用 H5 `.hdr .fav.on { color: var(--accent) }` 的高亮色。
2. **未做 / 待实测项**：
   - **模拟器实拍对比**：按要求未直接操作模拟器，五档实机特别是《不該 (with 張惠妹)》对唱、914×411 手机横屏和浅色黑白封面顶栏对比由 Claude 在模拟器上统一验证（待截图核对）。

---

## 5. 单测与构建结果

- **验证命令**（沙箱外 `--no-daemon` 运行，因已有其他任务进程占用标准 daemon 锁）：
  ```powershell
  .\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko
  ```
- **构建结果**：**`BUILD SUCCESSFUL in 4m 1s`**（50 actionable tasks: 16 executed, 34 up-to-date）。
- **单元测试**：
  - 全量单测共运行 **82** 个套件、**549** 项测试，**0** 失败，**0** 错误，**0** 跳过。
  - [FolioPlayerStateTest.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt) 共 **17** 项测试全部通过（新增覆盖：对唱着色与合唱/重叠加小字 `duetPosterColorsBySingerHandlesChorusAsPrimaryAndMovesLaterOverlapToSmallLines`、矮档 m 宽度与歌词起始偏移 `shortTierUsesH5ControlWidthAndLyricsStart`、切歌歌词可用性四态 `lyricsAvailabilityCoversPendingUnavailableFailedAndReadyWithoutFlashingNone`、顶栏封面顶部采样亮度与统一色板 `headerAdaptiveColorDetectsTopStripLuminanceAndUnifiesAllButtons`、H5 动效参数与降级计算 `motionTokensAndPosterCharacterLightingFollowH5TimingAndReducedMotion`）。
