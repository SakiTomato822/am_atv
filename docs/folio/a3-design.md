# A3 设计稿：PlaybackStore（Claude，基于 feat/page-cache 33130fd）

A3 是风险最大的一步，分两段做：

- **A3a 纯搬家（本任务）**：把 `AtvApp.kt` 里播放、队列、会话保存/恢复的状态和逻辑搬进 `dev.amatv.core.PlaybackStore`。生命周期**不变**：仍然每个窗口（Activity 的 composition）一个实例，窗口销毁时照旧 `release()` 播放器、照旧同步保存会话。用户看到的行为必须完全不变。
- **A3b 进程级（之后，Claude 做）**：把实例挪到 `AmAtvApplication`，切小窗、Activity 重建时不再释放播放器；`WindowHosts` 的交接逻辑随之简化。这一段会有行为变化，单独发包上车测。

A3a 之所以不直接做成进程级：现在的代码靠「窗口销毁 → release → 新窗口从存档恢复」来交接。如果先把状态挪到进程级、又保留 release，状态和播放器会对不上。所以先在原生命周期下搬干净、测干净，再改生命周期。

A1/A2 的规矩全部照旧，见 `A2-TASK.md` 开头：搬代码不重写、单一数据源、LaunchedEffect 的 key 不变、不吞异常、不加兜底。

## 1. 搬进 PlaybackStore 的东西（行号是 33130fd 的 AtvApp.kt）

### 状态
155–183 `startupCheckpoint`、`startupSessionLoaded`、`startupCursorEdited`、`startupQueueActions`、`track`、`hasCurrentTrack`、`positionAnchorMs`、`playStartedRealtimeMs`、`isPlaying`、`playWhenReady`；
193–203 `playbackPrepared`、`hasUserPlaybackRequest`、`playbackMessage`、`waitingQueueEntryId`、`expectedPlaybackGeneration`、`lastPlaybackFailure`、`commandFailure`、`failureHandledSelection`、`successfulSelection`、`networkRetryJob`、`networkRetryCount`；
208–209 `lastPlaybackNotice`、`stationHistory`；241–242 `gaplessLookahead`、`adoptingGaplessEntryId`；245 `advancingEndedTrackId`；263 `pendingRestore`；267–268 `playbackRequestJob`、`selectionGeneration`；276–279 `pendingRequestedSongCatalogId`、`systemPlaybackSnapshot`、`queueController`。

注意 203 行 `networkRetryCount` 现在是 `remember(track.id)`：换歌就归零。搬进 Store 后在 `track` 的 id 变化处显式归零，语义一样。

### 函数
281–308（station 判断、`requestedPlaybackTrack`、`currentPlaybackSnapshot`、`withCachedLyrics`、`acceptResolvedTrack`）、346–449（`currentPositionMs`、`launchNativePlayback`、`pausePlayback`、`requestPlayback`、`togglePlayback`、`retryCurrentLyrics`、`seekPlaybackBy`）、471–513 `executeTrackPlayback`、571–628（`waitForNextQueueEntry`、`applyQueueAction`、`dispatchQueueAction`、`supersedeStartupQueueWithNewContext`）、679–743 `playStationAsSongs` / `playTrack`、761–827 `playCollection`、`advanceNextTrack`、`selectQueueTrack`、`skip`、844–888 `handlePlaybackFailure`、917–1026 `publishSystemSnapshot`、`applyPlaybackSnapshot`、1061–1097 保存会话。2142–2160 的 station history 读写。

### 副作用（LaunchedEffect / DisposableEffect）
| 原位置 | 原 key | 搬后由什么驱动 |
|---|---|---|
| 537 喜欢状态 `onCurrentTrackChanged` | `track.catalogId, connectionState` | Store 内 `combine(state.map{track.catalogId}, accountStore.state.map{connectionState}).distinctUntilChanged()` |
| 542 渐变开关 | `queue context kind, stationFeed, 三个 fade 设置` | Store 内 combine 队列状态和 `settingsStore.state`，distinct |
| 552 无缝预载 | `current entryId, gaplessNext entryId/catalogId, playbackPrepared` | Store 内 collectLatest，**原样保留 1.5 秒 delay**，key 变化即取消 |
| 630 启动恢复 | 只跑一次 | `attach()` 里 launch 一次 |
| 661 启动后延迟的 Play | `startupSessionLoaded, playWhenReady, track.id` | Store 内 collect 这三个值的 distinct 组合 |
| 675 `activatePlaybackSession` | `hasUserPlaybackRequest` | 在 `hasUserPlaybackRequest` 变 true 的地方直接调用（只会从 false 变 true） |
| 890 `commandFailure` | `commandFailure` | 保留「下一拍再处理」：赋值后 `scope.launch { handlePlaybackFailure(...) }`，不要同步调用 |
| 894 等下一首 | `waitingQueueEntryId, current entryId, awaitingNext, autoplayStatus` | Store 内 combine，distinct |
| 1038 播放监听和系统媒体回调 | `services` | `attach()` 设置，`dispose()` 清掉并 `release()`，顺序照旧 |
| 1053 队列能否跳转变了就重发系统快照 | `canSkipNext, canSkipPrevious, current entryId` | Store 内 combine，distinct |
| 1099 ON_STOP 同步保存、`hostWindow.saveNow`、dispose 时同步保存 | `lifecycleOwner` | **留在 AtvApp**，只是改成调用 `store.saveSession(synchronous = true)` |
| 1111 改动后 350ms 保存 | 9 个 key | Store 内 combine 同样的值（`destination`、`previousDestination` 来自第 3 节的 `setNavigation`），`collectLatest { delay(350); save(false) }` |
| 1118 `WindowHosts.playing` | `playWhenReady` | 留在 AtvApp，读 Store 状态 |
| 1119 播放中每 5 秒保存 | `isPlaying, track.id` | Store 内 collectLatest |
| 1127 资料库详情预取 | `services, connectionState, playWhenReady` | Store 内 combine，collectLatest，原样 5 秒 delay |
| 1135 演示模式自动下一首 | `isPlaying, track.id, track.durationMs` | Store 内 collectLatest |
| 1162 歌词到了就重发系统快照 | `lyrics identity, lines, hasCurrentTrack` | Store 内 combine `lyricsStore.state`，distinct |
| 1216 station 历史 | `track.catalogId, stationQueueContext` | Store 内 combine，distinct |

所有「Store 内 combine」都必须先 `distinctUntilChanged()` 再处理，效果等于原来的 key 集合；`collectLatest` 用在原来 effect 里有 `delay` / 循环的地方（key 一变旧的就取消，和 LaunchedEffect 一样）。

### 不搬（留在 AtvApp）
- 导航：`destination`、`previousDestination`、`browseDetailRequest`、`openFromPlayer`（读 Store 的 `track`）、`showNowPlaying`（读 `hasCurrentTrack` 和货架）。
- 登录：`authLaunchGate`、`webAuthenticationLauncher`、`startSignIn`、`serviceMessage`。
- 界面：`keepScreenOn`（读 Store 状态）、频谱权限和 `setSpectrumActive`、`playerNotice` 和喜欢提示、歌词语言 `lyricsLocalization` / `lyricsVariant`、1170–1196 歌词预取和当前歌（读 Store 的 `track`、队列状态，key 不变）、1198–1211 内容加载。
- 1227–1250 派生值（`isLiveStream`、`canSeekPlayback`、`canSkipPrevious/Next`、`nowPlayingSubtitle`）可以做成 `PlaybackState` 的计算属性，也可以留在界面里读状态算，二选一，写清楚。

## 2. 对外接口

```kotlin
package dev.amatv.core

data class PlaybackState(
    val track: Track,
    val hasCurrentTrack: Boolean,
    val positionAnchorMs: Long,
    val playStartedRealtimeMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackPrepared: Boolean,
    val playbackMessage: String?,
    val stationHistory: List<Track>,
    val snapshot: PlaybackSnapshot?,      // 已按 expectedPlaybackGeneration 过滤，等于原 currentPlaybackSnapshot()
    val startupSessionLoaded: Boolean,
)

sealed interface PlaybackEvent {
    data class Message(val text: String?) : PlaybackEvent   // 原来写 serviceMessage 的地方（947 行 notice）
    data object SignInRequired : PlaybackEvent              // 原来 serviceMessage = 需要登录 + startSignIn()
    data object ShowNowPlaying : PlaybackEvent              // 原来写 destination = NowPlaying 的地方
}

class PlaybackStore(
    private val player: PlayerOperations,
    private val sessions: PlaybackSessionStorage,
    private val accountStore: AccountStore,
    private val settingsStore: SettingsStore,
    private val lyricsStore: LyricsStore,
    private val libraryActions: LibraryActions,
    private val coroutineScope: CoroutineScope,
    private val clock: () -> Long,               // SystemClock.elapsedRealtime
    initialPlayWhenReady: Boolean,               // hostWindow?.resume == true
) {
    val state: StateFlow<PlaybackState>
    val queueController: PlaybackQueueController   // 界面继续直接读它的 state 和调用队列面板的操作
    val events: SharedFlow<PlaybackEvent>

    fun attach()                                // 启动恢复 + 设置监听/媒体回调 + 启动内部 collect
    fun dispose()                               // 原 1047–1051：清回调、清监听、release()
    fun setNavigation(destination: Destination, previous: Destination)   // 只用于保存会话
    fun saveSession(synchronous: Boolean)

    fun currentPositionMs(): Long
    fun requestPlayback()
    fun pausePlayback()
    fun togglePlayback()
    fun seekPlaybackBy(delta: Long)
    fun skip(relative: Int)
    fun selectQueueTrack(index: Int)
    fun dispatchQueueAction(action: StartupQueueAction): Boolean
    fun retryCurrentLyrics()
    fun playTrack(nextTrack: Track, contextQueue: List<Track> = emptyList(), webStation: Boolean = false)
    fun playCollection(collection: Track, tracks: List<Track>, index: Int, shuffle: Boolean)
    fun onSignedIn()                            // 原 329–337 里播放相关的几行
    fun onSignedOut()                           // 原退出登录里播放相关的几行，顺序照旧
}
```

要点：
- 切到正在播放：原代码 `playStationAsSongs`、`playTrack`、`playCollection` 里会写 `previousDestination` / `destination = NowPlaying`，station 那条还是异步的（704–714）。Store 不碰导航，改成在原来写 `destination = NowPlaying` 的位置发 `PlaybackEvent.ShowNowPlaying`。AtvApp 收到后执行原来那两行：`if (destination != NowPlaying) previousDestination = destination; destination = NowPlaying`。原来在 `++selectionGeneration` 之后、`queueController.playContext` 之前写 `previousDestination` 的，界面这边在收到事件时一起写，效果相同（这两个变量 Store 只在保存时读）。`PlaybackEvent` 加上 `data object ShowNowPlaying`。
- 需要登录：原来 `serviceMessage = 需要登录; startSignIn(); return` 的三处（477、697、764）改成发 `SignInRequired` 然后 return；AtvApp 收到后执行原来那两行。
- `StartupQueueAction`、`StartupQueueActions`、`waitingRecommendationError`（都在 `ui/StartupQueueActions.kt`）整个文件挪进 `dev.amatv.core`（core 不依赖 ui，A2 的规矩）。`PlaybackSnapshot`、`PlaybackRepeatMode`（integration/MusicKitBridge.kt）、`PlaybackFailureKind`、`classifyPlaybackFailure`（integration/NativePlaybackIntent.kt）、`PlaybackSession`（integration/PlaybackPreferences.kt）本来就在 integration，直接 import。
- 事件流用 `MutableSharedFlow(extraBufferCapacity = 16)` + `tryEmit`，和 A2 的 `messages` 一样；AtvApp 用一个 `LaunchedEffect(playbackStore) { events.collect { ... } }` 处理。
- 时间全部走 `clock()`，测试才能控制。

### PlayerOperations（MusicServices 实现，只放 Store 真正调用的）
`isCatalogConfigured`、`widevineReady`、`resolvePlayableTrack`、`preparePlayback`、`play`、`pause`、`seekTo`、`skipToNext`、`skipToPrevious`、`skipToQueuedLookahead`、`setLookahead`、`setFadeEnabled`、`stationSongs`、`prefetchLibraryDetails`、`setPlaybackListener`、`setSystemPlaybackCallbacks`、`publishSystemPlayback`、`release`。另外 `PlaybackQueueController` 需要的 `RecommendationProvider` 照旧由 `MusicServices` 提供。

### PlaybackSessionStorage（PlaybackPreferences 实现）
`restoreCheckpointSession()`、`restoreSession()`、`saveSession(session, synchronous)`、`saveActiveTab(tab, synchronous)`，以及 station history 的读写（把 2145–2160 两个函数挪进 `PlaybackPreferences`，或新建一个小类实现这两个方法）。`activeTab` 和 `clearSession()` 界面还在用，就继续直接用 `PlaybackPreferences`。

## 3. 和其他块的交界
- 导航：`setNavigation(destination, previousDestination)` 由 AtvApp 用 `LaunchedEffect(destination, previousDestination)` 调。350ms 保存的 combine 也读这两个值。启动时界面的初始 `destination` 照旧从 `startupCheckpoint` 读：把 `startupCheckpoint` 做成 Store 的公开只读属性，界面初始化 `destination` / `previousDestination` 时读它。
- 登录：AtvApp 的 sign-in 成功分支改成 `playbackStore.onSignedIn()`，加上原来那几行内容和歌词调用，顺序不变。退出登录同理，`onSignedOut()` 放在原来播放那几行的位置，`queueController.reset()`、`playbackPrefs.clearSession()` 等按原顺序。
- 歌词：Store 读 `lyricsStore.state`（`acceptResolvedTrack`、`requestPlayback`、`publishSystemSnapshot` 在用），调 `withCachedLyrics`、`onTrackIdentityResolved`、`retryCurrentLyrics`。
- 喜欢：537 的 effect 搬进 Store。

## 4. 测试计划（JVM，假的 PlayerOperations 和 SessionStorage，`runTest` + `StandardTestDispatcher`，`clock` 用测试时间）
1. 选歌：`playTrack` 之后先 `resolvePlayableTrack` → `preparePlayback(playWhenReady=false)` → `play()`；快照 `isPrepared` 之后 `playbackPrepared=true`、`isPlaying` 跟快照。
2. 准备期间暂停：`preparePlayback` 挂起时调 `pausePlayback()`，准备完调的是 `pause()` 不是 `play()`。
3. 旧快照：`requestGeneration` 不等于当前的快照被忽略。
4. 歌曲不可用：失败快照 `TrackUnavailable` + 正在播 → 跳到下一首；全队都失败 → `failureLimitReached` 的提示。
5. 网络失败：倒计时 3、2、1 的 `playbackMessage`，然后重新准备；第三次失败停在提示上；成功播放 250ms 后计数归零。
6. 无缝：current 已准备、有下一首 → 1.5 秒后 `setLookahead(下一首)`；快照报 `gaplessAdvanceCatalogId` → 队列前进、**不**调 `preparePlayback`。
7. 播完：`isEnded` 快照 → 自动下一首，同一首只前进一次。
8. 启动：存档里有队列 → 恢复 `track`、位置；会话加载完之前按「下一首」→ 排队，加载完后按顺序重放，不准备中间的歌；加载完之前按播放 → 加载完才 `preparePlayback`。
9. 跳转：station 播放时 `skip(1)` 走 `skipToNext()`，受快照 `canSkipNext` 约束；普通队列走队列控制器；有预载时 `skipToQueuedLookahead`。
10. 保存：状态变化 350ms 后保存一次（连续变化只保存最后一次）；播放中每 5 秒保存。
11. 退出登录：`onSignedOut()` 之后 `isPlaying=false`、`hasCurrentTrack=false`、`expectedPlaybackGeneration` 归零（照原代码断言）。
12. 需要登录：未登录 `playTrack` → 发 `SignInRequired`、不调 `preparePlayback`。

## 5. 提交顺序
1. `PlayerOperations`、`PlaybackSessionStorage` 接口，`MusicServices` / `PlaybackPreferences` 实现；`StartupQueueAction(s)` 等类型挪进 core。不改行为，单测全过。
2. `PlaybackStore` + 测试 + AtvApp 改接（同一个提交，单一数据源）。AtvApp 里 `remember { PlaybackStore(...) }`，scope 用 `rememberCoroutineScope()` 得到的那个（生命周期和原来一样），`DisposableEffect` 里 `attach()` / `dispose()`。

## 6. 风险
- 这是全 App 最复杂的状态机：`selectionGeneration`、`expectedPlaybackGeneration`、`pendingRequestedSongCatalogId` 三个闸门决定一个快照算不算数。只许原样剪切，判断顺序一个字不改。
- LaunchedEffect 改成 Flow collect 后，**第一次**的触发时机可能不同（LaunchedEffect 在首次组合后立刻跑一次）。每个 combine 都要确认首个值会被处理，和原来一样。
- `queueController` 原来是 `lateinit` + `remember`，回调里用 `rememberUpdatedState` 拿最新的 `executeTrackPlayback`。搬进 Store 后它是普通成员，回调直接调 Store 的方法，`rememberUpdatedState` 删掉。
- 原来的回调 `latestSystemPlaybackCallbacks` 也一样，直接指向 Store 方法。
