# C7 播放页交付报告

- worktree：`D:\Data for Apps and etc\Codex\am_atv_a0`
- 分支：`c2-player`（已合并 `c3-merge` `328db11`）

## 一、实现说明

### 1. 宽档（`Wide` / `e`）打开队列时的左边界计算与动画
- 在 `FolioPlayerState.kt` 定义常量 `WIDE_CAR_CONTROLS_START_OFFSET_DP = 4f` 与 `WIDE_QUEUE_CONTROLS_GAP_DP = 24f`，并新增纯函数 `computeWideQueueStartPaddingDp(tier, controlsExpanded, controlsCardWidthDp, controlsStartOffsetDp, gapDp)`：
  - 当 `tier == FolioTier.Wide && controlsExpanded` 时返回 `controlsStartOffsetDp + controlsCardWidthDp + gapDp`（即 `4dp + 464dp + 24dp = 492dp` 相对 body 左侧，加上 `horizontalPad = 36dp` 后刚好落在控件卡右边界 `+ 24dp` 处），右侧仍贴齐屏幕右边距，上下居中位置不变，不与左下角控件卡重叠；
  - 当 `controlsExpanded == false` 或其它档位时返回 `0.dp`，宽档收起控件卡后队列面板平滑展开至全宽。
- 在 `FolioPlayerControls.kt` 的 `widePlacement` 复用 `WIDE_CAR_CONTROLS_START_OFFSET_DP.dp`，保持控件卡起点偏移与队列左边界计算同源。
- 在 `FolioPlayer.kt` 将 `wideControlsWidthDp` 传入 `TwoColumnPlayerBody`，在 `displayState == FolioPlayerDisplayState.Queue` 下使用 `animateDpAsState` 按与控件卡一致的 `FolioPlayerMotionTokens.CTL_TRANSLATE_MS`（`350ms`）与 `FolioPlayerMotionTokens.EnterEasing`（`reducedMotion` 时为 `0ms`）平滑动画 `padding(start = animatedQueueStartDp)`，确保展开或收起时左边界同步移动、无跳动。

### 2. 横排队列条目宽度与「正在播放」可见性
- `FolioQueuePanel` 横排条目的 `138.dp` 宽度保持不变，可见条数随可用宽度变化并支持横向滑动查看其余曲目。
- 在 `FolioPlayerContent.kt` 的 `FolioQueuePanel` 中监听 `tierSpec.isHorizontalQueue` 下的 `listState.layoutInfo.viewportSize.width`：仅当视口宽度发生变化（如控件卡展开/收起导致队列面板变窄/变宽）且 `currentRowIndex`（正在播放项）超出当前可视区域时调用 `listState.scrollToItem(currentRowIndex)` 将其保持在可见范围内；用户手动横向滚动时视口宽度不变，不会抢回焦点。

### 3. 超宽档（`Ultrawide` / `u`）核对
- 核对 `UltrawidePlayerBody`：其主体为三列 `Row`（左列 `tierSpec.controlCardWidth` 放封面、歌名与控件槽，中列 `weight(1.25f)` 放歌词/海报，右列 `weight(0.95f)` 常驻竖排 `FolioQueuePanel`），控件卡与队列面板处于互不重叠的独立列中，无需修改。其它档位（`Narrow` / `Medium` / `Short`）保持不变。

## 二、修改文件清单
- [FolioPlayerState.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt)
- [FolioPlayerControls.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)
- [FolioPlayer.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)
- [FolioPlayerContent.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt)
- [FolioPlayerStateTest.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt)

## 三、单测与构建结果
- 执行命令（沙箱外 `--no-daemon`）：
  `.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- 结果：`BUILD SUCCESSFUL`（耗时 4m 13s）。
- 单元测试汇总（基于 `app/build/test-results/testFlymeGeckoUnitTest/*.xml`）：
  - 全量套件：85 个 suite，共 588 个测试，0 失败，0 错误，0 跳过；
  - `FolioPlayerStateTest`：共 33 个测试，0 失败，0 跳过（含 C7 新增的 `wideQueueStartPaddingAvoidsExpandedControlsAndRestoresOnCollapse`）。
