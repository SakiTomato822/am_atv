# C3 播放页收起态混合布局 ⑤ 实施报告

## 1. 需求条目对照（对应 `C3-PLAYER.md` 1–9）

1. **Narrow `c`（竖屏手机 / 小窗）**
   - 保留底部折叠胶囊 `FolioPlayerCollapsedCapsule`（`①`），沿用 C2 封面缩略图规则（`showCollapsedCapsuleArtwork`：非 `Poster` 态显示 44dp 封面，Poster 态隐藏）。
   - 胶囊在隐藏封面时（`Poster` 态）文字区域左内边距通过 `collapsedCapsuleStartPaddingDp(showArtwork)` 设为 **`20.dp`**（显示封面时保持 `10.dp`）。
2. **Short `m`（横屏手机）**
   - 保持左下 `FolioPlayerCollapsedCapsule`，不显示封面缩略图（`showCollapsedCapsuleArtwork(FolioTier.Short, ...) == false`），文字区域左内边距为 **`20.dp`**。
3. **Medium `t`（平板竖屏）→ `③`**
   - 顶部栏 `FolioPlayerHeader` 在收起态常驻显示标题与第二行（由 `resolveCollapsedHeaderTitleMode` 解析）：
     - **`Lyrics`（Scrolling Lyrics 态）**：`LargeWithCover` 模式——标题左侧带 **`84.dp`** 方形圆角封面（`8.dp` 圆角与阴影），歌名 `24.sp`（最多 2 行，超出省略），第二行 `15.sp`（单行省略）；`heightIn(min = 92.dp)` 保证展开/收起切换时歌词区顶距稳定无跳动。
     - **`Poster` / `Queue` / `None` 态**：`Compact` 模式——无 `84.dp` 封面，歌名 `17.sp`（1 行）+ 第二行 `13.5.sp`（1 行）。
   - 底部通过 `resolveCollapsedLayout == FolioCollapsedLayout.CircleButtonAndHairline` 渲染 `FolioCirclePlayHairlineBar(buttonOnLeft = false)`：不再显示玻璃胶囊，右下为 **`64.dp`** 圆形玻璃播放键 `FolioCircleGlassPlayButton`，左侧为 **`3.dp`** 细进度线 `FolioHairlineProgressBar`（起点 `20.dp` + `horizontalPad 28.dp = 48.dp`，与右侧 64dp 按钮垂直居中对齐）。
4. **Wide `e`（横屏大屏 / 车机）→ `④`（Lyrics）/ `③`（Poster）**
   - **`Lyrics`（Scrolling Lyrics 态）→ `④`**：`resolveCollapsedLayout == FolioCollapsedLayout.UnderCoverInfo`，顶部 header `.tt` 隐藏（`resolveCollapsedHeaderTitleMode == None`）。`TwoColumnPlayerBody` 左侧 `400.dp` 大封面正下方直接放置 `FolioCarUnderCoverCollapsedBar`（无外框玻璃胶囊）：左侧歌名 `26.sp`（最多 2 行）+ 第二行 `17.sp`（1 行），右侧 **`56.dp`** 圆形玻璃播放键，下方横跨封面宽度的 `3.dp` 细进度线。
   - **`Poster`（及 `Queue` / `None` 态）→ `③`**：`resolveCollapsedHeaderTitleMode == Compact`，顶部左上常驻歌名 `17.sp` + 第二行 `13.5.sp`；底部左侧（主驾侧，屏幕左边距 `horizontalPad 36.dp + startPad 36.dp = 72.dp`）放置 **`64.dp`** 圆形玻璃播放键，右侧至 `right = 64.dp` 为细进度线。
   - Wide `e` 在两种主视图下收起态播放键均位于左侧（主驾侧）。
5. **Ultrawide `u`**
   - 保持 `UltrawidePlayerBody` 与三栏逻辑未作改动；`resolveCollapsedLayout` 保持为 `Capsule`，`resolveCollapsedHeaderTitleMode` 保持为 `None`。
6. **第二行格式（统一 `歌手 — 专辑`）**
   - 新增纯函数 `formatFolioPlayerSubtitle(artist, album)`：两者均非空时返回 `"$cleanArtist — $cleanAlbum"`（单个空格 + em-dash `—` + 单个空格），专辑为空（如电台）时仅返回 `cleanArtist`（不留孤立的 `—`）；单行 `TextOverflow.Ellipsis`。
   - 应用于 `FolioPlayerCollapsedCapsule`、`FolioPlayerHeader`（收起态与 Narrow 展开态）、`FolioCarUnderCoverCollapsedBar`、`FolioPlayerExpandedControls`（横屏车机条与卡片 `.cmeta`）及 `VerticalPlayerBody`（Medium Flow）。
7. **点击行为（收起态 vs 展开态）**
   - **收起态（`!controlsExpanded`）**：
     - 点歌名、第二行、收起区（含 Medium `Lyrics` 顶部 84dp 封面行、细进度线区域、Wide `Lyrics` 封面与下方信息块）仅调用 `onExpand` / `revealControls()` 展开 `.ctl`，**不跳转专辑/歌手详情页**。
     - 点圆形播放键（`Capsule` / `CircleButtonAndHairline` / `UnderCoverInfo`）通过独立的 `onCollapsedPlayPause` 仅切换播放/暂停（不触发 `revealControls()`）。
   - **展开态（`controlsExpanded`）**：
     - `.ctl` 中 `FolioExpandedSubtitle` 使用 `LinkAnnotation.Clickable` 为同一行 `"歌手 — 专辑"` 分别绑定 `onOpenArtist` 与 `onOpenAlbum`；专辑标题与封面绑定 `onOpenAlbum`。
8. **展开时不重复**
   - 收起态元素（`FolioPlayerHeader` 中的 `.tt`、`FolioCirclePlayHairlineBar`、`FolioCarUnderCoverCollapsedBar`、`FolioPlayerCollapsedCapsule`）均使用 `PMETA_OPACITY_MS`（`250ms`，尊重 `reducedMotion`）随 `controlsExpanded` 淡出至 `0f`，且在非激活状态立即禁用点击（`interactive = !controlsExpanded`、`TransportRow` 中 `playEnabled = controlsExpanded`），保证任意时刻屏幕上只有一个可点击的播放键。
9. **纯函数与单元测试**
   - 在 `FolioPlayerState.kt` 新增纯函数 `resolveCollapsedLayout`、`resolveCollapsedHeaderTitleMode`、`collapsedCapsuleStartPaddingDp`、`formatFolioPlayerSubtitle`，并在 `FolioPlayerStateTest.kt` 新增对应全组合单测。

## 2. 修改文件清单

- [`FolioPlayerState.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt)：新增 `FolioCollapsedLayout`、`FolioCollapsedHeaderTitleMode`、`resolveCollapsedLayout`、`resolveCollapsedHeaderTitleMode`、`collapsedCapsuleStartPaddingDp`、`formatFolioPlayerSubtitle`。
- [`FolioPlayerControls.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)：更新 `FolioPlayerHeader`、`FolioPlayerAnimatedControlsSlot`、`FolioPlayerCollapsedCapsule`、`FolioPlayerExpandedControls`、`TransportRow`，新增 `FolioCircleGlassPlayButton`、`FolioHairlineProgressBar`、`FolioCirclePlayHairlineBar`、`FolioCarUnderCoverCollapsedBar`、`FolioExpandedSubtitle`。
- [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)：接入 `onCollapsedPlayPause`、`FolioPlayerHeader` 收起态标题参数、`TwoColumnPlayerBody` 左列 `UnderCoverInfo` 布局与 Wide 非常驻控制条底部叠加层，并更新 Medium Flow 副标题。
- [`FolioPlayerStateTest.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt)：新增 4 组 C3 纯函数单测。

## 3. 与 H5 原型（`ref/flyme15-app.html`）的实现差异说明

1. **Wide `e` 车机 Rolling 态封面自适应防裁切**：H5 固定 `.t-e .art { 400px }` 与绝对定位 `top: calc(sb+484px/560px)`；Compose 端使用 `min((bodyHeight - 134.dp).coerceAtLeast(160.dp), min(leftColumnWidth * 0.78f, 400.dp))` 垂直排列，在 `1280×720+` 车机屏幕保持精确 `400.dp × 400.dp`，同时在分屏或更矮窗口自动缩放避免底部进度线裁切。
2. **Wide `e` `UnderCoverInfo` 展开动画无封面跳动**：将非 `alwaysOpen`（Soak）Rolling 态的 `.ctl` 槽置于 `Box` 底部叠加层（`Alignment.BottomStart`），展开时 `FolioCarUnderCoverCollapsedBar` 原地淡出、`.ctl` 从底部滑入，左侧 `400.dp` 封面位置与尺寸全程静止。
3. **Medium `t` 顶栏高度稳定与不遮挡歌词**：原生布局采用 `Column` 顺序排列 `FolioPlayerHeader` 与 `VerticalPlayerBody`，在 `Medium + Lyrics` 下通过 `heightIn(min = 92.dp)` 稳定 84dp 封面头栏高度并仅对内容做透明度淡出，避免 `.ctl` 展开时歌词区突跳。
4. **展开态第二行分段点击**：原生使用 `AnnotatedString` + `LinkAnnotation.Clickable` 在同一行 `Text(..., maxLines = 1, Ellipsis)` 内区分歌手与专辑点击区，保证长文本单行截断效果与 H5 一致且点击精确。

## 4. 单元测试与构建验证结果

- 执行命令（工作区外沙箱模式、`--no-daemon`）：
  `.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=..." "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- **单元测试结果**：
  - `FolioPlayerStateTest`：**22** 个测试全部通过（0 跳过、0 失败、0 错误）。
  - 全量 `:app:testFlymeGeckoUnitTest`（83 个测试套件）：**562** 个测试全部通过（`FAILURES=0`、`SKIPPED=0`）。
- **编译组装结果**：`:app:assembleFlymeGecko` 返回 `BUILD SUCCESSFUL`（3m 1s）。

## 第二轮

1. **展开态「歌手 — 专辑」去除超链接样式**
   - 在 [`FolioPlayerControls.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt) 的 `FolioExpandedSubtitle` 中，为 `LinkAnnotation.Clickable` 显式传入 `TextLinkStyles`：常规态、聚焦态、悬停态均固定为传入次要文字颜色 `color`（`--pfg2`）、`FontWeight.Normal`、`TextDecoration.None`（消除 Material3 默认紫色与下划线）；`pressedStyle` 仅降低透明度（`alpha * 0.45f`，下限 `0.15f`）提供按压反馈，同时保留「歌手」与「专辑」各自独立的点击跳转区域。
2. **对唱合唱判断改用 `LyricAgentType`**
   - 在 [`FolioPlayerState.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt) 中重构 `isOppositeSingerLine`：移除 `"v1000"` 与 `"group"` 字符串猜测，改为 `line.agentId.isNullOrBlank() || line.agentType == LyricAgentType.Group` 时归为主唱（返回 `false`），其余按 `line.isOppositeAgent` 返回。
   - 在 [`FolioPlayerStateTest.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt) 的 `duetPosterColorsBySingerHandlesChorusAsPrimaryAndMovesLaterOverlapToSmallLines` 中补充验证：`Group` 均为主唱，而 `Person` / `Character` 即使带 `"v1000"` 或 `"group_lead"` ID 仍按 `isOppositeAgent` 判定，空白 `agentId` 固定归为主唱。
3. **第二轮验证结果**
   - 同一命令验证：`FolioPlayerStateTest` **22/22** 通过，全量 `:app:testFlymeGeckoUnitTest`（84 个测试套件）**573/573** 通过（0 失败、0 跳过），`:app:assembleFlymeGecko` 返回 **`BUILD SUCCESSFUL`**（3m 49s）。
