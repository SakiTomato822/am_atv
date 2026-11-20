# D1 切歌计时日志验收报告

分支 `d1-gapless-log` 从 `c3-merge` 的 `9fd4d77` 创建。本次增加 MusicKit 歌曲切换诊断和 Folio 设置页的历史查看入口。原有队列选择、预排、重建、播放、淡化和重试逻辑沿用原路径。

## 计时与记录

- 每次切换使用一个 ID。应用选定预排目标时记下 `selected`，之后在同一条记录上补解析完成、`addQueueItems` 调用和 `onPlaybackQueueItemsAdded`。回退到同一个预排队列条目时，沿用原 ID 和预排时间。MusicKit 1.1.1 的追加回调没有请求 ID，使用当前请求代次及队尾追加类型按调用顺序关联。
- 原生续播从上一首的 `onItemEnded` 计时。缺失时，以 `onCurrentItemChanged` 或首次识别到原生续播的快照时间作推断。自动回退沿用上一首的结束时间。若结束由播放状态机推断，取它首次观察到结束迹象的时间，计入后续结束确认、等待推荐、解析和重建所用的时间。
- 新开请求记录 controller 重建起止、SDK `prepare` 和实际下发的 `play`。回退原因记录为 `no-lookahead`、`lookahead-not-queued` 或 `native-did-not-continue`。切歌尚未完成时触发的自动网络重试保留原 ID、结束时间和首次 `prepare`，并标出第一次重试时点。
- `onCurrentItemChanged`、首段缓冲开始／结束和首次位置推进逐项记录。首次推进取匹配目标曲目、处于播放状态且位置超过请求起点的第一份原生快照。所有阶段共用 `elapsedRealtime`，`offsetsMs` 的零点是目标选定时刻，阶段缺失显示 `-`。
- `AMATV-Gapless` 在首次推进时输出一条 `native`、`fallback` 或 `manual` 汇总。`gapMs` 对原生续播与回退取上一首结束到首次推进的差值，对手动切换取选择请求到首次推进的差值。日志同时列出解析、追加确认、重建、缓冲、结束到请求、请求到 `prepare`、`prepare` 到 `play` 和 `play` 到首次推进的耗时。
- 汇总带有 MusicKit audio session、淡化效果的挂载状态、已观测到的淡出／淡入挂载结果和 Android 媒体输出路由。API 33 起记录媒体音频属性对应的系统预期设备类型，较旧系统记录 `MediaRouter` 的音频路由设备类型和播放类型。路由字段反映系统提供的信息，MusicKit 实际使用的物理输出端口不确定。 [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes)) · [Android MediaRouter.RouteInfo](https://developer.android.com/reference/android/media/MediaRouter.RouteInfo#getDeviceType())
- Folio 设置页的「切歌诊断记录」可展开最近 30 条汇总，最新在前，重启后保留。日志导出也能取得 `AMATV-Gapless` 行。

## 文件

| 范围 | 文件 |
| --- | --- |
| 选择、传递与回退关联 | `AmAtvApplication.kt`、`PlaybackStore.kt`、`PlaybackContracts.kt`、`MusicServices.kt` |
| 原生回调与音频诊断 | `MusicKitBridge.kt`、`NativePlaybackIntent.kt`、`GaplessFade.kt` |
| 单行汇总与历史 | `GaplessTransition.kt`、`PlaybackDiagnostics.kt` |
| 设置页 | `FolioSettingsPage.kt`、英语及简繁中文的 `strings_diagnostics.xml` |
| JVM 测试 | `GaplessTransitionTest.kt` 新增 5 项，`PlaybackStoreTest.kt` 更新测试替身签名 |

## 验收与限制

- 按 `B1A-TASK.md` 第 4 节执行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`，结果为 **BUILD SUCCESSFUL**。Kotlin daemon 的临时目录写入受限，Gradle 自动改用进程内编译。
- 80 个测试套件，**531 项全部通过**，0 失败／错误／跳过。新增用例覆盖原生续播、回退从上一首结束计时、手动及缺失时点、迟到的结束回调、重试复用计时。
- ARM64 预览 APK 位于 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。
- `gapMs` 是应用侧估计值，精度受 SDK 回调投递和快照采样频率影响。被取消或未到达首次推进的请求不会进入设置页历史，Gecko 电台流不在本轮测量范围内。按约定未操作模拟器，实际出声间隔和车机上的路由、淡化及设置页显示仍待设备验证。
