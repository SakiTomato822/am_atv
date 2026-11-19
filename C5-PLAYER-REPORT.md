# C5 播放页交付报告

- worktree：`D:\Data for Apps and etc\Codex\am_atv_a0`
- 分支：`c2-player`

## 一、实现说明

### 1. 小窗按钮只在车机上出现（且小窗内不显示）
- `WindowHosts.canOpenMini(context)` 改为委托纯函数 `canSupportMiniWindow(hasFlymeWindowMode(), BuildConfig.FLYME_UI_PREVIEW)`：移除 `hasFreeform(context)` 判断，平板与 DeX 等自由窗口设备不再显示小窗按钮；`BuildConfig.FLYME_UI_PREVIEW` 保留用于模拟器验证；`WindowHosts.switch()` 内给自由窗口准备 launch bounds 的逻辑保持不动。
- 新增纯函数 `shouldShowMiniWindowButton(canOpenMini, isMiniWindow, tier)`：`isMiniWindow == true` 时返回 `false`，仅在全屏车机相关档位（`Wide` / `Ultrawide` / `Short`）且 `canOpenMini == true` 时显示小窗按钮。
- 在 `FolioPlayerHeader` 中改用 `shouldShowMiniWindowButton(...)` 过滤 `pipWindow`，并删除原先 `if (pipWindow.mini)` 下「回到全屏」的图标和文案分支。

### 2. 窄档（`FolioTier.Narrow`）：＋ / ☆ 从顶栏移进控件卡
- 新增纯函数 `trackActionLocationForTier(tier)` 与枚举 `FolioTrackActionLocation`（`Header` / `ControlsCard`）：仅 `Narrow` 返回 `ControlsCard`，其余档位保持 `Header`。
- **顶栏**：抽取复用的 `AddToLibraryIconButton` 与 `FavoriteIconButton`，在 `FolioPlayerHeader` 中仅当 `trackActionLocationForTier(tierSpec.tier) == FolioTrackActionLocation.Header` 时渲染；`Narrow` 顶栏只保留 `↓`、歌名块（遵循 `showHeaderTitle`：收起隐藏、展开显示且最多 1 行歌名 + 1 行副标题末尾省略，占据中间全部剩余宽度）、`Tt`。
- **控件卡**：在 `FolioPlayerExpandedControls` 非车载横排分支的 `SeekSection` 上方，当 `trackActionLocationForTier == ControlsCard` 时新增一行（`Arrangement.SpaceBetween`），左侧放 `40.dp` 的加入资料库图标按钮（`Add` / `Check`，进行中或已加入禁用），右侧放 `40.dp` 的喜欢图标按钮（`StarBorder` / `Star`，进行中禁用，喜欢时使用 `favoriteAccent` 高亮）；主按键行不变；`FolioPlayer.kt` 的所有控件槽位统一传入喜欢/资料库状态与回调。

### 3. 海报上下句不要过早换行（含换句过渡策略）
- **角色实际排版宽度**：在 `FolioPlayerState.kt` 新增纯函数 `computePosterRoleLayoutWidthPx(panelWidthPx, scale)`（`(panelWidthPx / scale.coerceIn(0.1f, 4f)).roundToInt().coerceAtLeast(1)`）。`FolioPosterLyricsPanel` 对每个角色分别以 `panelWidthPx / roleScale` 作为约束宽度进行测量与排版，使非主句经 `graphicsLayer` 缩小回 `roleScale` 后正好占满面板宽度，避免按主句字号宽度过早折行。
- **自定义测高缓存 key**：将 `leadHeightCache` 的 key 从 `Int` 改为 `PosterLeadHeightCacheKey(lineIndex, maxWidthPx)`，按「行号 + 排版宽度」缓存测得的主句高度；`currentStage` 计算 `focusMainH` 使用 `focusWidthPx`、计算 `prevY` 使用 `prevWidthPx * prevScale`、计算 `upcomingYs` 使用 `nextWidthPx * nextScale`。
- **换句过渡期间的排版宽度控制**：新增纯函数 `resolvePosterTransitionLayoutWidthPx(currentWidthPx, targetWidthPx, isTransitioning)`，在 `transition.currentState != transition.targetState` 期间取来源角色与目标角色两者中较窄的排版宽度 `minOf(currentWidthPx, targetWidthPx)`，过渡结束再切换为 `targetWidthPx`：
  - `Upcoming0 -> Focus`：较窄宽度等于目标 `Focus` 宽度，因此从进入焦点动画第 1 帧起即按 `Focus` 宽度折行，放大过程中不会向面板右侧溢出，且动画结束瞬间宽度不变、零跳动；
  - `Focus -> Previous`：过渡期间保持较窄的 `Focus` 宽度并随 `scale` 缩小，避免飞行途中重排抖动，仅在过渡完成后切到更宽的 `Previous` 宽度；
  - `Upcoming2 -> Upcoming1`、`Upcoming1 -> Upcoming0` 等相同缩放比的角色切换，两端宽度一致，全程不触发重排；
  - 渲染时通过外层 `Box.layout` 向内层（携带 `graphicsLayer` 与 `blurMod` 的 `Box`）传入 `maxWidth = activeWrapWidthPx`（`minWidth = 0`）并向上汇报面板父级宽度，避免子 `RenderNode` 提前裁剪。

### 4. 「译」按钮恢复成歌词设置菜单并补全音译显示
- **「译」按钮状态与触发**：在 `FolioPlayerState.kt` 新增纯函数 `isLyricDisplayActive(translationEnabled, pronunciationEnabled)`；`TransportRow` 的「译」按钮由旧的直接切换 `lyricTranslationEnabled` 改为打开歌词设置菜单（高亮条件为 `lyricsDisplayActive`，即翻译或音译任一开启）。
- **设置面板与交互**：在 `FolioPlayerControls.kt` 新增 `FolioLyricsDisplaySettingsOverlay`，使用 `folioPlayerGlass` 玻璃材质；按纯函数 `lyricsSettingsPlacementForTier(tier)` 在 `Wide` / `Ultrawide` 从左下方控件区域旁边以浮层弹出、在 `Narrow` / `Short` / `Medium` 从底部弹出（支持纵向滚动）；点击外部遮罩、按系统返回键（`BackHandler`）或点击「完成」关闭。
- **选项、可用性与状态说明**：完整对齐旧版 `LyricsDisplaySettingsDialog`，通过 `SettingsStore` 现有 setter 控制：
  - 歌词字号（`LyricTextSize`：`Standard` / `Large` / `ExtraLarge` 循环切换，并换算为相对默认字号的比例 `folioLyricsTextScaleMultiplier` 驱动滚动与海报字号）；
  - 原始语言（自动检测）；
  - 翻译开关 & 翻译语言选择子页面（`FolioTranslationLanguageTags`，空串为跟随 App 语言）；
  - 音译开关 & 音译文字选择子页面（`folioPronunciationLanguageTags(appLanguageTag)`：拉丁字母 / App 文字）；
  - 依据 `FolioPlayerState` 纯函数 `lyricTranslationAvailable`、`lyricPronunciationAvailable` 和 `resolveLyricsOptionStatus` 在开启且不可用/加载中/加载失败时展示对应原因文案；`FolioPlayer.kt` 同步按 `expectedLyricsVariant` 与 `lyricsState` 计算 `enhancementsReady` 和 `enhancementsFailed`，并在自动隐藏控件计时器中避开设置菜单打开态。
- **音译渲染**：
  - 滚动歌词：`FolioScrollingLyricsPanel` 委托 `CarLyricRows`，已按字级与行级绘制 `pronunciationText`（并放宽 `fontScale` 上限至 `1.80f` 以配合字号调节）；
  - 海报歌词：`FolioPosterLyricsPanel` 新增 `pronunciationEnabled` 与纯函数 `posterPronunciationText(line)`（兼容整行与逐词音译），在主句下方按 `FontWeight.Normal`、`fg2`（对唱为 `duetAccentColor.copy(alpha = 0.60f)`）紧贴主句绘制；当音译与翻译同时开启时 **音译在上、翻译在下**；`measureFocusExtrasHeightPx` 将两者高度合并进 `focusExtrasH`，共同决定 `upcoming0Y` 并随 `focusWeight` 参与换句动画。

## 二、修改文件清单
- [WindowHosts.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/WindowHosts.kt)
- [FolioPlayerState.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt)
- [FolioPlayerControls.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt)
- [FolioPlayerContent.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayerContent.kt)
- [FolioPlayer.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/main/java/dev/amatv/flyme/FolioPlayer.kt)
- [FolioPlayerStateTest.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt)

## 三、单测与构建结果
- 执行命令（沙箱外 `--no-daemon`）：
  `.\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`
- 结果：`BUILD SUCCESSFUL`（耗时 3m 51s）。
- 单元测试汇总（基于 `app/build/test-results/testFlymeGeckoUnitTest/*.xml`）：
  - 全量套件：85 个 suite，共 585 个测试，0 失败，0 错误，0 跳过；
  - `FolioPlayerStateTest`：共 30 个测试，0 失败，0 跳过（含 C5 新增的 4 项单测：小窗开关规则、窄档 ＋/☆ 归属规则、海报角色宽度与过渡防抖宽度、歌词设置菜单可用性/激活态/弹出定位及音译文本提取）。
