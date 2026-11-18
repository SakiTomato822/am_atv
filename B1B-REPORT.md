# B1b 交付报告 — AM Folio 15 播放页（Now Playing Layer）

## 1. 功能实现对照（第 2 节 1–10 项）

1. **展开与收起**
   - 点击底部迷你播放胶囊、车机/带鱼屏顶部圆点，或在 `PlaybackEvent.ShowNowPlaying` 触发时将 `playerOpen` 设为 `true`，播放页自底部上滑并淡入展开（`FolioMotion.SLOW_MS = 600ms` / `PageEasing`）。
   - 收起提供三条路径：左上角下拉箭头按钮、顶部下拉手势条及封面下拉手势（`rememberFolioPullDownToClose`，下拉超过 `120dp` 松手关闭，带缩放与透明度跟手反馈，未达阈值弹回），以及系统返回键 `BackHandler(enabled = open)`（`FolioMotion.FAST_MS = 200ms` 收起）。
2. **五档布局与自适应**
   - 通过 `playerSpecForTier(tier)` 完整对照 H5 五档设计 token（`fs`、`pfs`、`lyricGap`、`pib`、`rd`、`pp`、`qc`、`qt`、`cw2`、`anchorFraction`）：
     - `Narrow (c)`：单列纵向，Soak 顶部封面垂直渐隐入底色，无歌词时展示 `.nonebox` 大圆角封面 + 可点标题/歌手 + 「这首歌没有歌词」。
     - `Short (m)` / `Wide (e)`：双列布局；`Wide (e)` 车机底部控制栏采用左（驾驶侧 `[译 / 上一曲 / 播放暂停 / 下一曲 / 队列]`）+ 右（封面/曲名/歌手/可拖拽进度条）的横向卡片；队列面板使用 `.queue.h` 横向卡片条。
     - `Medium (t)`：平板竖屏大字号与较大控件边距（`pib=48dp`, `rd=58dp`, `pp=70dp`），Flow 时上方横向展示专辑封面与曲名，Soak 时顶部渐隐封面。
     - `Ultrawide (u)`：三列布局（左列封面+曲名/歌手+控制栏，中列滚动歌词/大字歌词/无歌词提示，右列常驻纵向播放队列）。
3. **四种展示态切换**
   - 在 `resolvePlayerDisplayState` 中实现 `Lyrics` / `Poster` / `Queue` / `None` 四态切换：`Queue` 优先（带鱼屏保持中列内容且右列常驻队列），无歌词切 `None`，仅有静态歌词时停留在 `Lyrics`（不允许切 `Poster`），其余根据 `Tt` 开关切换 `Lyrics` 与 `Poster`。
   - `isQueuePanelVisible` 实现：`Ultrawide (u)` 常驻、`Narrow (c)` 仅 `Queue` 时展示、`Short/Medium/Wide (m/t/e)` 在 `Queue` 或 `None`（无歌词）时自动展示队列面板。
4. **两种背景样式与明暗适配**
   - 读取 `settingsStore.state` 的 `lyricStyle`：
     - `Soak`（取色渐隐）：提取封面主色调并通过 `folioPlayerBaseColor` 压暗（深色）或提亮混合纸白（浅色），`c`/`t` 顶部出血渐隐，`m`/`e`/`u` 左侧出血渐隐 + 深色下 `SoakGlow`。
     - `Flow`（流光）：复用 `CarLyricFlow`，并在浅色模式下叠加柔和高亮白纱（`0xFFF6F4F9 @ 0.72`），保证深色文字（`#16141B`）具备充足对比度。
   - 控制栏显隐规则与标题规则：`alwaysOpenControls` 在 `Flow + (e/u/t)` 以及 `Narrow + 无歌词 + 非队列` 时常驻展开；其余场景播放中 5 秒自动收起为 `.pmeta` 胶囊并展示底部进度细线；仅 `Narrow (c)` 且控制栏展开（非 `None`）时顶栏 `.hdr` 显示曲名/歌手，其它档位在 `.cmeta`/`.pmeta`/左列显示。
5. **歌词复用与大字（Poster）歌词**
   - 仅在 `FolioPlayer*.kt` 中复用 `dev.amatv.ui` 歌词/封面相关基础组件，未改造旧界面源码。
   - 滚动歌词传入 `LocalFlymePalette`（按深浅色切换 `DarkFlymePalette`/`LightFlymePalette`），直接支持 3 秒拖拽浏览回弹、点击歌词跳播、间奏等待三点呼吸与淡出虚化。
   - `FolioPosterLyricsPanel` 使用 `computeSongPosterFontSize` 基于全曲最长一句计算统一字号，结合 `LyricTimeline.Schedule` 展示上一句、当前卡拉OK逐字扫动或等待三点 `InstrumentalInterludeRow`（含可选译文）及后续即将演唱行。
6. **控制按钮、资料库收藏与小窗切换**
   - 顶栏集成：下滑关闭、`+` / `✓` 加入资料库（`libraryActions.addToLibrary()`）、`☆` / `★` 收藏切换（`libraryActions.toggleFavorite()`，收藏态以 `palette.accent` 实心星高亮）、`Tt` 滚动/大字切换、`.winb` 大小窗切换（读取 `LocalHostWindow.current`，当 `canOpenMini` 为真且处于小窗或 `e/u/m` 档位时显示，调用 `switchTo(!mini)`）。
   - 控制卡集成：`译` 翻译开关（直接读写 `settingsStore.setLyricTranslationEnabled`）、上一曲/播放暂停/下一曲、可点击与可拖拽的实时进度条（通过 `playbackStore.seekPlaybackBy(targetMs - currentPositionMs())` 跳播）、队列切换。
7. **播放队列分组与跳转**
   - 通过 `buildFolioQueueSnapshot` 将 `playbackStore.queueController.state` 分组为「已播放 · N 首」「正在播放」「接下来播放 · N 首」「自动播放」，保留真实 `timelineEntries` 索引，点击非当前曲目调用 `playbackStore.selectQueueTrack(index)`；电台播放时读取 `playbackState.stationHistory`，点击已播历史曲目通过 `playbackStore.playTrack` 切播。当前曲目旁显示动态三柱均衡器指示。
8. **点歌手 / 专辑跳转详情**
   - 点击任意处的歌名/封面调用 `musicServices.catalogAlbumOf(track)`，点击歌手名调用 `musicServices.catalogArtistOf(track)`；解析成功后执行 `onOpenDetail(resolved)`（由 `FolioApp.kt` 先设 `playerOpen = false` 并压入 `detailStack`），解析失败给出本地化轻量提示。
9. **大小窗切换状态保持与冷启动默认收起**
   - 使用进程内非持久化的 `FolioPlayerSession.openState`，在同进程全屏与 Flyme 小窗 Activity 切换间保持打开状态；冷启动初始为 `false`（收起）。
10. **PlaybackEvent.ShowNowPlaying**
    - 已将 `FolioApp.kt` 中 `PlaybackEvent.ShowNowPlaying -> Unit` 改为 `PlaybackEvent.ShowNowPlaying -> playerOpen = true`。

---

## 2. 文件清单

### 修改文件
- `app/src/main/java/dev/amatv/flyme/FolioApp.kt`（严格仅改允许的 2 处：`playerOpen` 状态接入 `FolioPlayerSession.openState`，以及 `PlaybackEvent.ShowNowPlaying -> playerOpen = true`）
- `app/src/main/java/dev/amatv/flyme/FolioPlayer.kt`（实现 `BoxScope.FolioPlayerLayer` 及各档主体布局、手势下拉关闭与播放时钟）

### 新增文件
- `app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerBackdrop.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt`
- `app/src/main/res/values/strings_folio_player.xml`
- `app/src/main/res/values-zh-rCN/strings_folio_player.xml`
- `app/src/main/res/values-zh-rTW/strings_folio_player.xml`
- `app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt`

---

## 3. 引用的旧歌词与封面组件清单（仅限 `FolioPlayer*.kt`）

- `dev.amatv.ui.CarLyricRows`
- `dev.amatv.ui.KaraokeLyricLine`
- `dev.amatv.ui.InstrumentalInterludeRow`
- `dev.amatv.ui.posterFontSize`
- `dev.amatv.ui.CarLyricFlow`
- `dev.amatv.ui.SoakGlow`
- `dev.amatv.ui.MotionArtwork`
- `dev.amatv.ui.rememberArtworkPalette`
- `dev.amatv.ui.LocalFlymePalette` / `DarkFlymePalette` / `LightFlymePalette`

未引用任何旧导航栏、旧弹出对话框、旧浏览页、旧设置组件，未修改任何 `dev.amatv.ui` / `dev.amatv.core` / `dev.amatv.playback` / `WindowHosts` / 其它 Folio 文件。

---

## 4. 与 H5 原型的差异及原因

1. **玻璃拟态卡片底色（无实时高斯模糊）**：Android 11（车机目标平台）不具备 Compose 跨层实时背景模糊 API，控制卡、胶囊与顶栏按钮复用实体/半透明混色边框方案（深色 `0xE0211D26` + 白边，浅色 `White @ 0.86f` + 暗边），确保在 Flow / Soak 动态背景下始终有高对比度与可读性。
2. **大字歌词（Poster）主行渲染**：在沿用全曲最长句统一计算字号（`computeSongPosterFontSize`）与上/下文逐级淡出排版基础上，当前主行直接接入 `KaraokeLyricLine` 以复用苹果原味逐字扫动与和声换行效果，而不是纯整行变色。
3. **带鱼屏（`Ultrawide`）队列按钮**：带鱼屏右列队列默认常驻可见，因此点击底部队列图标在带鱼屏上不覆盖中列歌词，仅唤醒控制栏聚焦；其它四档均正常开关队列面板。

---

## 5. 未做或不确定事项

- **模拟器真机多档视觉微调**：按分工约束未操作模拟器截图，五档尺寸（尤其是小窗极窄态与 2.6:1 带鱼屏内边距、车机底部控制条在极端字体比例下的间距）需待 Claude 在模拟器截图对比后确认是否需要细微调优（状态为：**待真机视觉确认**）。
- **直播电台可拖拽性**：直播流依据 `PlaybackSnapshot.isLiveStream == true` 禁用进度条拖拽并显示「直播」字样；真实 Apple Music 电台流下 `stationHistory` 在切台/恢复场景的返回时序未在真机实测（状态为：**不确定**）。
- **LyricsStore 后台加载触发点**：由于 `FolioApp.kt` 地盘限制不能新增顶层调用，已在 `FolioPlayerLayer`（随 `FolioApp` 根节点常驻挂载）内挂接 `lyricsStore.setCurrentTrack`，确保未展开播放器时也能跟随切歌与翻译开关拉取歌词；若后续 B1a/底座统一托管 `setCurrentTrack` 可随时去掉该 `LaunchedEffect`（该库方法本身幂等）。

---

## 6. 单测与构建验证结果

- **验证命令**（沙箱外执行）：
  ```powershell
  .\gradlew.bat "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko
  ```
- **构建结果**：`BUILD SUCCESSFUL in 22s`（50 actionable tasks: 10 executed, 40 up-to-date），`FolioPlayer*` 无编译错误与警告。
- **单测结果**：
  - 全量单元测试：`testFlymeGeckoUnitTest` 共运行 **81** 个测试套件、**527** 项单测，**0** 失败，**0** 错误，**0** 跳过。
  - 新增 `FolioPlayerStateTest`（8 项）：覆盖五档布局 token 校验、四态状态机与队列展示可见性、`Narrow` 顶栏标题显隐规则、`alwaysOpenControls` 规则、`FolioPlayerSession` 冷启动收起与同进程状态保持、常规队列四段分组与索引映射、电台 `stationHistory` 队列映射、以及按全曲最长句计算大字歌词字号。

---

## 7. 返工（对应 `B1B-FIX.md` 4 项修复）

1. **点收起后的小胶囊展开控件（`FolioPlayerControls.kt` / `FolioPlayer.kt`）**
   - 将 `positionState: State<Long>` 延迟至 `SeekSection` 与 `FolioPlayerCollapsedCapsule` 底部细进度条 `Canvas` 内部读取，消除了整屏每帧 60Hz 重组对点击态和语义节点的干扰。
   - 在 `FolioPlayerCollapsedCapsule` 中移除外层 `Box` 的 `.clickable`，改为参照 H5 `.pmeta` 与 `FolioMiniPlayerCapsule` 结构，直接在左侧 `[封面 + 曲名/歌手]` 的 `Row(Modifier.weight(1f).fillMaxHeight())` 上绑定 `.clickable(role = Role.Button, onClick = onExpand)`；右侧播放/暂停按钮保持独立兄弟可点击节点，仅控制播放/暂停。
2. **右上角与顶栏按钮样式（`FolioPlayerControls.kt`）**
   - 左上角收起箭头及右上角 `+`/`✓`、`☆`/`★`、`Tt` 均改为无底无描边纯图标（`Tt` 在激活大字歌词态时仅叠加轻薄无框圆底 `fg @ 0.16f`，对应 H5 `.ib.on`）。
   - 仅有小窗切换按钮（`.winb`）保留玻璃圆底与描边（`pillBg` + `pillBorder`）。
3. **控件条右侧显示剩余时间（`FolioPlayerState.kt` / `FolioPlayerControls.kt`）**
   - 新增纯函数 `formatFolioRemainingTime(positionMs, durationMs)` 并补充单测 `formatsRemainingTimeWithLeadingMinusSign`，`SeekSection` 右侧时间在非直播态统一渲染 `-${formatFolioDuration(remaining)}`（如 `-0:51`）。
4. **打开专辑/歌手详情不吞 `CancellationException`（`FolioPlayer.kt`）**
   - 移除两处 `runCatching { musicServices.catalogAlbumOf/ArtistOf(track) }.getOrNull()`，抽取 `openCatalogDetail`，遇到 `CancellationException` 立即重新抛出，其余异常安全降级为界面提示 `player_open_unavailable`。

- **返工后构建与单测验收**：
  - `.\gradlew.bat "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
  - **结果**：**`BUILD SUCCESSFUL in 27s`**，**81** 套件 **528** 项单测全部通过（**0** 失败、**0** 跳过，`FolioPlayerStateTest` 9/9 通过）。
