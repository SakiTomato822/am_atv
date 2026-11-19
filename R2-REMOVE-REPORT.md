# R2 旧界面与非 Flyme 包清理

`r1-new-ui-only` 在指定 worktree 快进合并 `c3-merge` 至 `328db11`，以该提交为统计基线。主窗口和小窗现在直接进入 Folio，状态栏及导航栏沿用 Folio 的透明与 inset 配置，图标明暗随主题更新。两处「试用新界面」开关、状态、偏好读写和对应测试断言已移除。旧 `try_new_ui` 键仍可留在升级用户的偏好文件中。

## 删除范围

- R1 审计第 4 节的 16 个旧路径全部移除。整文件删除 `AtvApp.kt`、`HomeScreen.kt`、`CollectionBrowser.kt`、`CollectionScreen.kt`、`FlymeArtist.kt`、`FlymeGlyphs.kt`、`LibraryArtistBrowser.kt`、`LibraryArtistScreen.kt`、`FlymeMediaSettings.kt`、`ContentCacheSettings.kt`、`LanguageSettings.kt`、`BrowseContentMotion.kt`、`HomeCardWarmup.kt`、`ShelfNavigationCursor.kt` 和 `Theme.kt`。`CarFonts.kt` 的原生字体加载部分仍服务 Gecko 登录页，迁到 `flyme/`。
- 移除 `PlayerScreen.kt`、`FlymeCarLyrics.kt`、`FlymePortraitLyrics.kt`、`FlymeDesign.kt`、`FlymeSearch.kt`、`LibraryCategories.kt`、`LyricViewport.kt`、`LyricPresence.kt` 及 `Artwork.kt` 的旧界面路径，下表列出迁出的声明。整文件移除 `CarLayout.kt`、`Focus.kt`、`FocusFrame.kt`、`FocusFrameMotion.kt`、`FocusScrollPlacement.kt`、`ThemeFlip.kt`、`PlayerBackdrop.kt`、`LavaBackdropView.kt`、`LavaPalette.kt`、`BackdropMotion.kt`、`CarLyricEffects.kt`、`AppleLoadingIndicator.kt` 和 `AudioBandIndicator.kt`。
- 删除 TV banner、旧 debug 清单和三种语言中只供旧界面使用的 76 个资源名称，合计 216 处定义，其中 75 个字符串和 1 个复数资源。R1 清单中的 `album_show_complete`、`album_show_library`、`playback_fade_albums`、`playback_fade_stations`、`playback_fade_playlists`、`playback_option_on` 和 `playback_option_off` 仍由 Folio 引用。动态封面布局、字体、应用图标、通知图标和 locale 配置仍有调用。

`git diff --stat 328db11..795fc8c` 汇总了六笔实现提交，共 137 个文件、增加 2,340 行、删除 18,307 行，净减 **15,967 行**。其中生产 Kotlin 净减 13,750 行，资源净减 237 行，单测和 instrumentation 净减 1,961 行，Gradle、清单及工作流净减 19 行。

## 共享声明去向

| 迁入文件 | 保留声明或职责 |
| --- | --- |
| `flyme/CarLyrics.kt` | `CarLyricRows`、`CarLyricFlow`、`SoakGlow` |
| `flyme/KaraokeLyrics.kt` | `KaraokeLyricLine`、`lyricFlexRows`、逐词渲染和遮罩 |
| `flyme/InstrumentalInterludeRow.kt`、`InterludeMotion.kt` | 歌词间奏点渲染及时间曲线 |
| `flyme/BackgroundVocalTypography.kt`、`LyricPresence.kt`、`KaraokeMaskRamp.kt`、`rendering/TextureAlphaMask.kt`、`MotionTokens.kt`、`AutomationSemantics.kt` | 和声排版、歌词高亮淡出、遮罩、渲染时序及歌词行语义 |
| `flyme/PosterTypography.kt`、`LyricPalette.kt` | `posterFontSize`、`DarkFlymePalette`、`LightFlymePalette`、`LocalFlymePalette` 及歌词配色 |
| `flyme/LibrarySort.kt`、`SearchHistory.kt` | `LibraryCategory`、`LibrarySort`、`sortLibrary`、`sorts` 和 `pushSearchHistory` |
| `flyme/MotionArtwork.kt`、`artwork/ArtworkImages.kt`、`artwork/ArtworkPalette.kt` | 动态封面、共享图片加载与缓存、`rememberArtworkPalette` 及封面取色 |
| `flyme/CarFonts.kt` | Gecko 登录页的原生字体加载 |

`FolioPlayer*.kt` 只调整迁移相关的 import，`FolioGlass.kt` 无差异。除移除开关外，核心代码的差异只有 Gecko 登录和媒体会话的迁移引用。歌词选显偏好 `lyricsPreferred` 留在偏好层，Folio 不消费该值。EVCC 歌词元数据、仪表盘和媒体会话行为按原实现保留。

## 变体与发布

- AGP 自动注册的 `debug` 和 `release` build type 留作空壳，在 `beforeVariants` 禁用其变体。原 release 的压缩配置移到 `flymeGecko`。Instrumentation 默认使用 `flymeGecko`。
- Flyme 正式应用 ID 为 `dev.amatv.flyme`，预览应用 ID 为 `dev.amatv.flyme.preview`，`versionCode` 仍为 42。签名选择规则、版本名称、发布说明和车机签名检查保持原样。
- `.github/workflows/flyme-auto.yml` 原本已经只跑 Flyme 的打包和单测，本轮只移除已冗余的 `amatv.testBuildType` 参数。

## 测试与构建

| 检查 | 结果 |
| --- | --- |
| `app/src/test` 与 `app/src/testFlymeGecko` 的 JUnit 用例数量 | 基线源码共 587 项，本轮 467 项，减少 120 项旧实现用例。 |
| B1A 第 4 节的 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko` | `BUILD SUCCESSFUL in 1m 5s`。JUnit XML 记录 72 个套件、467 项通过，失败、错误、跳过均为 0。 |
| `:app:assembleFlymeGeckoAndroidTest` | 随同一命令成功打包。设备执行留给 Claude 验证。 |
| `:app:tasks --all` | `BUILD SUCCESSFUL in 3s`。变体级组装任务只有 `assembleFlymeGecko`、`assembleFlymeGeckoAndroidTest` 和 `assembleFlymeGeckoUnitTest`。 |
| 预览 APK | `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。APK 清单报告包名 `dev.amatv.flyme.preview`、版本代码 42、启动 Activity `dev.amatv.MainActivity` 和 ABI `arm64-v8a`，且通过 `apksigner verify`。 |

保留歌词渲染与换行、背景人声、间奏和海报字号，搜索历史、资料库排序与分页、封面取色、会话恢复、媒体会话歌词与 seek 等覆盖。旧触控冒烟、旧资料库选择器、旧背景动效和焦点、旧歌词列表挂载与锚点等测试随该实现删除。保留的字体与和声高度 instrumentation 测试已接到 Folio 字体和共享歌词渲染。Kotlin daemon 在沙箱外的缓存目录写入受限，验收命令通过 `-Pkotlin.compiler.execution.strategy=in-process` 完成编译。正式包的车机覆盖安装及设备表现仍不确定。

## 给 Claude 的验证重点

1. 用同包名、同签名的正式包在车机覆盖升级。分别预置旧开关键为 true、false 和缺键，检查主窗口与小窗均直达 Folio、设置页没有开关，并验证横竖屏、透明系统栏、键盘避让和主题图标。
2. 在歌词页核对 Flow、Soak、海报、背景人声、逐词遮罩、间奏、翻译和音译，验证已有字号与偏移继续生效。跑迁移后的 `HarmonyLyricsTest` 和 `CarFontsTest`。
3. 核对 Gecko 登录字体及授权、专辑资料库／完整版本切换、搜索历史、资料库切换与排序、会话恢复及直播电台 seek 状态。英文、简中、繁中各走一遍常用路径。
4. 在支持车机集成的环境核对 EVCC 与仪表盘歌词、方向盘按键、系统媒体控制，以及小窗切换期间的播放连续性。
