# C2 播放页交付报告（`c2-player`）

## 1. 改动内容

- **纯函数规则（`FolioPlayerState.kt`）**：
  - 新增 `showCollapsedCapsuleArtwork(tier: FolioTier, displayState: FolioPlayerDisplayState): Boolean`，仅在竖屏档位（`Narrow` / `Medium`）且非海报状态（`displayState != FolioPlayerDisplayState.Poster`）时返回 `true`；横屏档位（`Short` / `Wide` / `Ultrawide`）以及所有档位的 `Poster` 状态均返回 `false`，竖屏的 `Queue` 与 `None` 状态保持返回 `true`（对齐 H5 `.player.s-poster .pmeta .pc,.t-m .player .pmeta .pc,.t-e .player .pmeta .pc,.t-u .player .pmeta .pc{display:none}`）。
- **收起胶囊视图（`FolioPlayerControls.kt` / `FolioPlayer.kt`）**：
  - `FolioPlayerAnimatedControlsSlot` 与 `FolioPlayerCollapsedCapsule` 新增 `displayState` / `showArtwork` 参数并在 3 处调用点（底栏、`UltrawidePlayerBody`、`ShortPlayerLayout`）传入当前 `displayState`。
  - 当 `showArtwork == false` 时，不渲染胶囊内的 `FolioArtwork` 缩略图，左内边距从 `10.dp` 调整为 `18.dp`，歌名与歌手直接左对齐；胶囊尺寸、点击区域、播放/暂停按钮与底部进度细线及其他控件动效保持不变。
- **单元测试（`FolioPlayerStateTest.kt`）**：
  - 新增 `collapsedCapsuleArtworkAppearsOnlyInPortraitNonPosterModes`，枚举全部 5 个档位 × 4 种展示状态共 20 种组合进行断言。

---

## 2. 修改文件清单

- `app/src/main/java/dev/amatv/flyme/FolioPlayerState.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayerControls.kt`
- `app/src/main/java/dev/amatv/flyme/FolioPlayer.kt`
- `app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt`
- `C2-PLAYER-REPORT.md`

---

## 3. 单测数量与构建结果

- **验证命令**（沙箱外以 `--no-daemon` 执行）：
  ```powershell
  .\gradlew.bat --no-daemon "-PAM_DEVELOPER_TOKEN_FILE=D:\Data for Apps and etc\Codex\am_atv\work\upstream-public-developer-token.jwt" "-Pflyme.uiVerification=true" "-Pflyme.previewAbi=arm64-v8a" :app:testFlymeGeckoUnitTest :app:assembleFlymeGecko
  ```
- **构建结果**：**`BUILD SUCCESSFUL in 2m 55s`**（50 actionable tasks: 13 executed, 37 up-to-date）。
- **单测结果**：
  - 全量单元测试：**83** 个测试套件共 **558** 项单测全部通过（**0** 失败，**0** 错误，**0** 跳过）。
  - [FolioPlayerStateTest.kt](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/app/src/test/java/dev/amatv/flyme/FolioPlayerStateTest.kt) 共 **18** 项单测全部通过（含新增的 `collapsedCapsuleArtworkAppearsOnlyInPortraitNonPosterModes`）。
