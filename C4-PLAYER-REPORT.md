# C4 播放页交付报告

## 1. 逐条实现说明（对应 `C4-PLAYER.md` 1–5）

### 1. 海报歌词换句：逐行同步上滚动画（解决首帧闪烁、旧句回闪、整列跳动）
- **同步角色切换与防闪帧机制**（[`FolioPlayerContent.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt#L160-L490)）：
  - 放弃原先 `LaunchedEffect` + `ghost` 方案，改为在组合期同步计算当前阶段的离散快照 `PosterStageKey`（`prevKey` / `focusKey` / `upcomingKeys`）及实测坐标 `PosterRollStage`，直接驱动单个 `updateTransition(targetState = currentStage)`。
  - 在换句瞬间（第 1 帧），`transition.currentState` 仍停留在上一阶段 `A`、`transition.targetState` 为新阶段 `B`，因此每条按 `PosterRollKey`（`Lead(lineIndex)` / `Gap(beforeLineIndex, startMs)`）唯一 key 化的歌词行与新入场底部行都**在同一帧直接从旧角色位置、字号缩放和透明度起步**，不会出现第 5 帧先闪新状态、旧句作为 ghost 回闪或同一句同时出现在两处的问题。
- **不重排文本，纯 GPU `graphicsLayer` 变换**：
  - 每条主唱行统一按 `typo.mainSp`（`lineHeight = mainSp * 1.16`、`FontWeight.ExtraBold`）排版并用 `rememberTextMeasurer()` 同步预测量像素高度（按歌词列表、面板宽度、字号缓存）。
  - 字号变化通过 `graphicsLayer { scaleX; scaleY; transformOrigin = TransformOrigin(0f, 0f) }` 实现，垂直位移动画通过 `translationY` 实现，时长 **`550ms`**（`POSTER_ROLL_DURATION_MS`），曲线使用 `FolioPlayerMotionTokens.EnterEasing`。
  - 只对目标角色为 `ExitingPrevious` 的离场旧上一句在 `API 31+` 添加最高 `12.dp` 模糊；其余所有行均不加模糊，保证 Android 11（API 30）车机上仅靠位移、缩放与透明度即可平滑上滚。
- **主句顶边固定 + 上下渐隐 + 间奏点 + 对唱 + 减少动效**：
  - 主句顶边固定在面板 **`40%`** 高度（`focusTopY = heightPx * 0.40f`），不随上一句或下句行数跳动；面板裁剪溢出并使用 `BlendMode.DstIn` 纵向边缘渐变（顶部 `0%–8%`、底部 `90%–100%`）柔和隐出。
  - 间奏三点 `GapKey` 作为主句角色参与同一 `updateTransition` 滚动与退出动画；每行对唱配色、副唱/重合小字随该行同步移动（副唱小字变化时仅平滑推移后续 `Upcoming` 坐标）；逐字点亮通过 `focusWeight` 在 `Upcoming -> Focus`（`1f -> charAlpha`）与 `Focus -> Previous`（`charAlpha -> 1f`）间连续过渡。
  - `reducedMotion == true` 时所有 `tween` 时长设为 `0ms`，并仅渲染目标阶段可见行，实现即时切换。

### 2. 翻译与下一句样式区分
- 在 [`FolioPlayerState.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt#L949-L971) 中将 `posterTypographyFor` 的翻译字号更新为 `mainSp * 0.40f`（行高 `1.30x`），下一句透明度序列定义并统一为 **`0.50f`、`0.32f`、`0.18f`**（`posterUpcomingAlphas`）。
- 在 [`FolioPlayerContent.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt#L502-L542) 中：
  - 翻译紧贴主句实测底边下方 **`4.dp`**（`transGapPx`），字重 **`FontWeight.Normal`**，常规主唱使用 `fg2`，对唱使用 `duetAccentColor.copy(alpha = 0.60f)`。
  - 翻译绑定在主句块的 `focusWeight` 透明度上，主句切为 `Previous` 时在前半程（`275ms`）淡出，`Previous` 不显示翻译。
  - 主句块（含翻译及副唱小字）底边与第一条下句之间保持 **`28.dp`**（`nextBlockGapPx`）间距，下句保持 `FontWeight.SemiBold` 视觉比例并按 `0.50 / 0.32 / 0.18` 递增透出。

### 3. 背景样式跟随显示模式与默认显示方式设置
- 在 [`FolioPlayerState.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt#L381-L406) 新增纯函数：
  - `resolveEffectiveBackgroundStyle(displayState, settingsStyle, lastContentBackgroundStyle)`：`Poster` 固定返回 `Soak`，`Lyrics` 固定返回 `Flow`，`None` 用设置值，`Queue` 保留进入队列前记录的 `lastContentBackgroundStyle`。
  - `resolveEffectivePosterPreferred(settingsStyle, sessionOverride)`：无会话覆盖时返回 `settingsStyle == LyricBackgroundStyle.Soak`。
  - `FolioPlayerSession` 新增 `posterOverrideState`、`syncDefaultDisplayFromSetting`、`togglePosterPreference`：冷启动或未点击 `Tt` 前每次打开播放页均按默认显示设置初始化；播放页内点击 `Tt` 后在当前进程内持续保持用户选择；在设置页切换默认显示方式时清除会话覆盖以使新默认设置生效。
  - 更新 `alwaysOpenControls`：移除 `Medium` 与 `Wide` 在 `Flow` 下强制常驻展开的旧规则（保留 `Ultrawide + Flow` 与 `Narrow + 无歌词`），保证 C3 布局 `③` / `④` 与 C4 第 4 条收起/展开动画正常工作；同时在 [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt#L980) 中限制 Medium `116.dp` 流光封面仅在 `displayState == None` 显示，避免 `Lyrics` 模式下与顶部 `84.dp` 封面重复。
- 在 [`FolioPlayerBackdrop.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerBackdrop.kt#L40-L152) 使用 `Crossfade(..., tween(400ms))` 实现 `Flow` 与 `Soak` 背景切换的 `400ms` 交叉淡化。
- 在 [`FolioSettingsPage.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioSettingsPage.kt#L218-L229) 及 `values` / `values-zh-rCN` / `values-zh-rTW` 的 [`strings_flyme_lyrics.xml`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values-zh-rCN/strings_flyme_lyrics.xml#L3-L5) 将设置项更新为「默认显示方式」「滚动歌词（流光背景）」「海报（封面背景）」。

### 4. 宽档 `e` 左侧紧凑控件卡与封面让位缩放
- 在 [`FolioPlayerControls.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt#L967-L1034) 重写 `isHorizontalCarControls`（仅影响 `Wide e`）：
  - 位置：`start = 4.dp`（加外层 `horizontalPad 36.dp` = **屏幕左 `40.dp`**），`bottom = 4.dp`（加外层 `bottomPad 20.dp` = **底 `24.dp`**）；宽度通过 `computeWideCarControlsWidthDp(wideCollapsedCoverDp)` 设为 `封面宽 + 64.dp`（400dp 封面下为 **`464.dp`**）。
  - 内容竖排三行：1) 歌名 `20.sp` 单行 + `FolioExpandedSubtitle`（`14.sp` 单行，歌手/专辑分段可点，**无小封面**）；2) `SeekSection`（进度条与两端时间）；3) `TransportRow(horizontalSpacing = null, fillMaxWidth)`（译 / 上一首 / 大播放暂停键 / 下一首 / 队列 均匀分布）。
- 在 [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt#L1425-L1475) 中，宽档 `e` 滚动歌词展开时，使用 `CTL_TRANSLATE_MS`（`350ms`，`EnterEasing`）与 `computeWideCarExpandedCoverSizeDp` 测算值将左栏封面以 `TransformOrigin(0f, 0f)` 保持左上角固定等比缩小，确保封面底边距离控件卡顶边至少 **`24.dp`**（如 594dp 主体高、226dp 控件卡时从 `400.dp` 缩至 `332.dp`）；收起时同步恢复 `400.dp`。海报模式无封面时控件卡同位置滑入；所有收起态与其余档位（`u/t/m/c`）均保持不变。

### 5. 海报模式顶部防遮挡渐隐遮罩
- 在 [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt#L337-L356) 根容器中、`FolioPlayerBackdrop` 之上且所有前景顶栏组件之后，当 `displayState == FolioPlayerDisplayState.Poster` 时渲染高度 **`160.dp`**、颜色为当前主题背景色 `palette.bg`（顶部 alpha **`0.55f`** 向下渐变至 **`0f`**）的遮罩，并随海报模式做 `400ms` 淡入淡出，保持原有取色逻辑完全不变。

## 2. 第 1 条各角色缩放、透明度与 Y 坐标计算表

设面板尺寸 `(widthPx, heightPx)`，`focusTopY = heightPx * 0.40f`，主句实测字号 `mainSp`（区间 `[baseSp, 1.3 * baseSp]`），`prevScale = (baseSp * 0.42f) / mainSp`，`nextScale = (baseSp * 0.46f) / mainSp`，单行主文本实测高度 `H(line)`：

| 角色 (`PosterLyricRole`) | 缩放 (`scale`) | 容器透明度 (`alpha`) | 目标 `Y` 坐标（顶边） | 模糊 (`API 31+`) | 翻译显示 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `ExitingPrevious` | `prevScale * 0.94f` | `0f` | `(prevY 或 focusTopY - 20dp) - 28dp` | 动画至 `12.dp` | 无 |
| `Previous` | `prevScale` (`0.32–0.42`) | `fg2.alpha`（深色 `0.68` / 浅色 `0.62`） | `focusTopY - 20dp - H(prev) * prevScale` | 无 | 淡出至 `0f` |
| `Focus` | `1.0f` | `1.0f`（字符逐字 `0.40 -> 1.0`） | **`focusTopY`**（固定在 `40%`） | 无 | 主句下 `4dp`，`0.40 * mainSp` |
| `Upcoming0` | `nextScale` (`0.35–0.46`) | **`0.50f`** | `focusTopY + H(focus) + H(trans) + 28dp + smallExtra` | 无 | 无 |
| `Upcoming1` | `nextScale` | **`0.32f`** | `upcoming0Y + H(u0) * nextScale + 14dp` | 无 | 无 |
| `Upcoming2` | `nextScale` | **`0.18f`** | `upcoming1Y + H(u1) * nextScale + 14dp` | 无 | 无 |
| `EnteringBelow` | `nextScale` | `0f` | `lastUpcomingBottomY + 14dp + 24dp` | 无 | 无 |

## 3. 修改文件清单

- [`FolioPlayerState.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt)：新增背景/默认显示模式解析纯函数、`FolioPlayerSession` 覆盖状态、`alwaysOpenControls` 规则调整、Wide 控件卡与封面缩放纯函数、`PosterLyricRole` 及比例常数。
- [`FolioPlayerContent.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt)：重写 `FolioPosterLyricsPanel`（`updateTransition` 逐行动画、`rememberTextMeasurer` 高度测量、翻译与下句样式、边缘渐变裁切）。
- [`FolioPlayerBackdrop.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerBackdrop.kt)：增加 `Crossfade` `400ms` 背景样式交叉淡化。
- [`FolioPlayerControls.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)：实现 Wide `e` 左栏 3 行紧凑控件卡、卡片高度回传与 `left 40dp / bottom 24dp` 定位。
- [`FolioPlayer.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)：接入 `effectiveStyle`、`posterPreferred` 初始化与 `Tt` 会话同步、海报模式 `160dp` 顶部主题渐隐遮罩、Wide `e` 封面等比让位缩放。
- [`FolioSettingsPage.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioSettingsPage.kt)：默认显示方式变更时调用 `FolioPlayerSession.syncDefaultDisplayFromSetting`。
- [`strings_flyme_lyrics.xml` (default)](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values/strings_flyme_lyrics.xml)、[`strings_flyme_lyrics.xml` (zh-rCN)](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values-zh-rCN/strings_flyme_lyrics.xml)、[`strings_flyme_lyrics.xml` (zh-rTW)](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values-zh-rTW/strings_flyme_lyrics.xml)：更新默认显示方式与两档名称字符串。
- [`FolioPlayerStateTest.kt`](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt)：新增背景样式绑定、设置初始化/会话保持、Wide 封面让位、海报角色缩放与透明度单测，并更新受 C4 调整规则的旧用例断言。

## 4. 单测与构建结果

- 执行命令：`.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=..." "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- **单测数量与结果**：
  - `FolioPlayerStateTest`：**26 / 26** 全部通过（0 失败、0 跳过）。
  - 全量 `:app:testFlymeGeckoUnitTest`（84 个测试套件）：**577 / 577** 全部通过（`FAILURES=0`、`SKIPPED=0`）。
- **构建结果**：`:app:assembleFlymeGecko` **`BUILD SUCCESSFUL`**（4m 25s）。
