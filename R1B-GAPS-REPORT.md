# R1B 新界面补缺报告

工作树 `D:\Data for Apps and etc\Codex\am_atv_b1_gpt`，分支 `r1-new-ui-only`，起始提交 `d612951`。

## 1. 播放设置的淡入淡出

`flyme/FolioSettingsPage.kt` 在播放分组保留淡入淡出的标题和说明，沿用旧版 `playback_fade_*` 文案。专辑、电台、播放列表各有一个开关，分别读写 `fadeAlbums`、`fadeStations`、`fadePlaylists`。提交 `da6ee99`。

## 2. 资料库专辑与完整专辑

`flyme/FolioDetailCollection.kt` 默认显示资料库曲目，在曲目列表下方用 Folio 胶囊切换版本。显示条件沿用旧界面，专辑必须是已关联目录 ID 的资料库专辑，且当前版本有曲目。封面区的播放和随机，以及点选曲目，都使用当前版本的曲目和专辑标识。`core/LibraryAlbum.kt` 从 `ui/CollectionBrowser.kt` 提取了资格判断与目录专辑转换，供两个界面共用。提交 `62da6c9`。

## 3. 资料库歌手的专辑

`flyme/FolioDetailArtist.kt` 根据歌手的资料库资源类型，在歌手页顶部加入「资料库中的专辑」。分区使用现有的资料库歌手专辑服务，支持缓存、分页和重试，点击专辑进入对应详情。目录匹配成功时，热门歌曲和目录分区继续显示在下面。没有目录匹配时，页面保留歌手标题和资料库专辑分区。英文、简体和繁体标题分别加在 `values/strings_browse.xml`、`values-zh-rCN/strings_browse.xml` 和 `values-zh-rTW/strings_browse.xml`。提交 `a4af5cf`。

## 4. 退出登录后的播放会话

代码核实确认，修复前 Folio 退出只重置内存队列，磁盘上的会话仍可在冷启动恢复。`PlaybackStore` 启动时读取检查点和完整会话文件，恢复这一阶段没有登录状态判断。退出后内存中没有当前曲目，后续 `saveSession()` 不会覆盖旧会话。

`flyme/FolioApp.kt` 现在在 `playbackStore.onSignedOut()` 之后调用与旧界面相同的 `PlaybackPreferences.clearSession()`。它清除检查点、完整时间线和旧版会话，并使还在排队的旧会话写入失效。结论来自代码检查，冷启动及界面交互的实机验证仍待确认。提交 `1a918ba`。

## 验收

- 按 `B1A-TASK.md` 第 4 节，用 Windows 的 `gradlew.bat` 运行 `:app:testFlymeGeckoUnitTest` 和 `:app:assembleFlymeGecko`，使用 Flyme UI 验证和 `arm64-v8a` 预览参数。
- Gradle 输出 `BUILD SUCCESSFUL in 3m 41s`。50 个任务中 30 个执行、20 个为最新状态。Kotlin daemon 临时目录写入被沙箱拒绝，Gradle 自动回退并完成编译。
- 单测 XML 共 85 个测试套件，**585 项全部通过**，失败、错误、跳过均为 0。
- APK 已生成于 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。
