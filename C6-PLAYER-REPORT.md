# C6 播放页交付报告

- worktree：`D:\Data for Apps and etc\Codex\am_atv_a0`
- 分支：`c2-player`（基于 `d612951`）

## 一、实现说明

### 1. 队列面板：循环、随机、清空接下来
- **状态与可用性**：
  - 在 `FolioPlayerState.kt` 扩展 `FolioQueueSnapshot` 增加 `repeatMode: PlaybackRepeatMode` 与 `shuffleEnabled: Boolean`，在 `buildFolioQueueSnapshot` 中从 `queueState.repeat` / `queueState.shuffle` 读取（电台队列固定返回 `PlaybackRepeatMode.Off` / `false`）。
  - 新增纯函数 `resolveQueueControlAvailability(isStation, upNextCount, autoplayCount)` 返回 `FolioQueueControlAvailability(canSetRepeat, canSetShuffle, canClearQueue)`：当 `isStation == true`（电台队列）时三者全部为 `false`；非电台时 `canSetRepeat`、`canSetShuffle` 为 `true`，`canClearQueue` 在 `upNextCount > 0 || autoplayCount > 0` 时为 `true`（与旧版 `AtvApp` 保持一致）。
- **UI 布局与防误触**：
  - 在 `FolioPlayerContent.kt` 的 `FolioQueuePanel` 新增 `FolioQueueControlBar`，同时接入横排队列（`Wide` / `Short` 的 `LazyRow` 卡片顶部）与竖排队列（`Ultrawide` / `Narrow` / `Medium` 等的 `LazyColumn` 顶部固定行）。
  - 左侧放置两个 `40.dp` 圆形图标按钮：循环（单曲时切换为 `Icons.Rounded.RepeatOne`，其余为 `Icons.Rounded.Repeat`；`repeatMode != Off` 高亮）与随机（`Icons.Rounded.Shuffle`；`shuffleEnabled` 高亮），不可用时置灰禁用。
  - 右侧放置「清空」文字按钮：首次点击不弹对话框，按钮转为高亮描边的「确认清空？」，并启动 `LaunchedEffect` 3 秒超时自动还原；3 秒内再次点击触发清空并还原按钮态，或当队列不再有待播曲目时自动复位。
- **动作分发**：在 `FolioPlayer.kt` 为非电台队列分别派发 `StartupQueueAction.CycleRepeat`、`StartupQueueAction.ToggleShuffle` 与 `StartupQueueAction.ClearUpcoming`（并同步触发 `revealControls()`），传递到所有 6 处 `FolioQueuePanel` 调用。

### 2. 「译」菜单里加歌词偏移
- **位置与交互**：
  - 在 `FolioPlayerControls.kt` 的 `FolioLyricsDisplaySettingsOverlay` 主页中，于「歌词字号」下方新增 `FolioLyricsOffsetRow` 卡片，并在 `FolioPlayer.kt` 传入 `settings.lyricOffsetMs` 与 `settingsStore::setLyricOffsetMs`。
  - 卡片顶部左侧显示「歌词偏移」，右侧实时显示当前偏移状态（如「歌词提前 200 毫秒」「歌词延后 100 毫秒」「未调整」）；下方提供 `−100`、`−50`、`+50`、`+100` 四个微调按钮及「重置」按钮，点击后立即写入 `SettingsStore` 并同步反映到滚动歌词（`CarLyricRows`）与海报歌词（`FolioPosterLyricsPanel` 的 `lyricPosition`）。
- **范围限制、正负号含义与纯函数**：
  - 在 `FolioPlayerState.kt` 定义范围常量 `FOLIO_LYRIC_OFFSET_MIN_MS = -3000L`、`FOLIO_LYRIC_OFFSET_MAX_MS = 3000L` 及 `adjustFolioLyricOffsetMs(currentOffsetMs, deltaMs)`，将结果限制在 `±3000ms` 闭区间。
  - **正负号含义（与旧版 `HomeScreen.kt` / 歌词播放器一致）**：**正数（`+`）让歌词提前**，**负数（`-`）让歌词延后**。原因是滚动与海报歌词均按 `(positionState.value + offsetMs).coerceAtLeast(0L)` 计算歌词时钟：`offsetMs > 0` 使歌词判定时间比音频进度超前（画面提前亮起/滚动），`offsetMs < 0` 使歌词判定时间落后于音频进度（画面延后出现）。
  - 文案格式化采用纯函数 `resolveFolioLyricOffsetState` 与 `formatFolioLyricOffsetLabel`，配合 `res/values*/strings_folio_player.xml` 中「歌词提前 %1$d 毫秒」「歌词延后 %1$d 毫秒」「未调整」（含简/繁/英三套文案）。

## 二、修改文件清单
- [FolioPlayerState.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt)
- [FolioPlayerContent.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt)
- [FolioPlayerControls.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)
- [FolioPlayer.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)
- [values/strings_folio_player.xml](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values/strings_folio_player.xml)
- [values-zh-rCN/strings_folio_player.xml](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values-zh-rCN/strings_folio_player.xml)
- [values-zh-rTW/strings_folio_player.xml](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/res/values-zh-rTW/strings_folio_player.xml)
- [FolioPlayerStateTest.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt)

## 三、单测与构建结果
- 执行命令（沙箱外 `--no-daemon`）：
  `.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- 结果：`BUILD SUCCESSFUL`（耗时 1m 58s）。
- 单元测试汇总（基于 `app/build/test-results/testFlymeGeckoUnitTest/*.xml`）：
  - 全量套件：85 个 suite，共 587 个测试，0 失败，0 错误，0 跳过；
  - `FolioPlayerStateTest`：共 32 个测试，0 失败，0 跳过（含 C6 新增的 2 项单测：`queueControlsAvailabilityAndSnapshotRepeatShuffleState`、`lyricOffsetClampsRangeAndFormatsAdvanceDelayLabels`）。
