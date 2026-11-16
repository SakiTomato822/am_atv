# A1 设计方案：SettingsStore、LibraryActions、LyricsStore

本文档定义 AM Folio 15 重构阶段 A1 的核心层架构与迁移设计。核心层文件位于 `app/src/main/java/dev/amatv/core/`，包名为 `dev.amatv.core`。

---

## 总体设计原则

1. **纯 Kotlin 逻辑**：核心层类不依赖任何 Compose 库（零 `androidx.compose.*` 依赖），不持有 Activity 或 UI 组件引用。
2. **状态单向流动**：核心层对外仅暴露 `StateFlow<不可变 data class>` 供界面与外部观察，所有操作通过普通成员函数调用。
3. **存储与格式完全兼容**：沿用现有 `PlaybackPreferences`（`am_atv_playback_preferences`）、`FlymePlaybackOptions`（`am_atv_flyme_playback`）以及 `ContentCache`（`content-cache`）的 SharedPreferences 文件与键名，不改存储结构，不做数据迁移。
4. **单例与生命周期**：整个进程生命周期只有一份，由 `AmAtvApplication` 在启动时初始化并持有，全局可通过 Application 或依赖注入访问。
5. **纯 JVM 单元测试**：测试位于 `app/src/test/java/dev/amatv/core/`，全部采用 Fake 实现依赖，不依赖 Mock 框架，不依赖 Android 设备/模拟器环境。

---

## 一、SettingsStore

### 1. 要搬的东西清单

#### 搬入清单
- `AtvApp.kt`:
  - `lyricsPreferred`（行 167）：歌词默认展开/显示偏好。
  - `lowPerformanceMode`（行 208）：低性能模式开关。
  - `repeatMode`（行 271）：循环模式默认偏好初值。
  - `shuffleEnabled`（行 272）：随机播放开关默认偏好初值。
  - `autoplayEnabled`（行 273）：自动播放/无限续播偏好初值。
  - `lyricOffsetMs`（行 274）：歌词时间轴微调偏移量（毫秒）。
  - `lyricTextSize`（行 275）：歌词字体尺寸。
  - `lyricTranslationEnabled`（行 276-278）：歌词翻译开关。
  - `lyricPronunciationEnabled`（行 279-281）：歌词注音/罗马音开关。
  - `lyricTranslationLanguageTag`（行 282-284）：歌词翻译目标语言 tag。
  - `lyricPronunciationLanguageTag`（行 285-287）：歌词注音目标语言 tag。
  - 各类设置变更回调（行 2118-2126, 2150-2153, 2214-2217, 2228-2243）：包括 `onLyricOffsetChange`、`onLowPerformanceModeChange`、`onLyricsPreferredChange`、`onLyricTextSizeChange`、`onLyricTranslationEnabledChange`、`onLyricPronunciationEnabledChange`、`onLyricTranslationLanguageChange`、`onLyricPronunciationLanguageChange`。
- `FlymePlaybackOptions.kt`:
  - `lyricStyle`（行 19, 50-51）：歌词背景样式（Flow 流光 / Soak 浸润）。
  - `appearance`（行 16, 45-47）：外观日夜模式（Light, Dark, System, Time）。
  - `lightFromMinute`, `lightUntilMinute`（行 17-18, 48-49）：定时日间模式时间区间。
  - `fadeAlbums`（行 9, 41）：专辑切歌淡入淡出。
  - `fadeStations`（行 11, 42）：电台切歌淡入淡出。
  - `fadePlaylists`（行 12, 43）：歌单切歌淡入淡出。
  - `stationsAsSongs`（行 14, 44）：电台以普通单曲模式播放偏好。
- `ContentCacheSettings.kt` / `ContentCache.kt`:
  - `cacheDiskEnabled`（`enabled`）：磁盘内容缓存总开关。
  - `cachePrefetchNextTwo`（`prefetch-next-two`）：队列后续两首预取开关。

#### 明确不搬的清单及原因
- `activeTab` / `saveActiveTab`（`PlaybackPreferences.kt` 行 151-159, `AtvApp.kt` 行 174-179）：属于界面导航浏览状态，留给 A2（ContentStore / Navigation）。
- `saveSession`, `restoreSession`, `restoreCheckpointSession`, `clearSession`（`PlaybackPreferences.kt` 行 161-171, `AtvApp.kt` 行 168-172）：属于播放会话持久化，留给 A3（SessionStore）。
- `PlaybackQueueController` 内部运行态的 `repeat`, `shuffle`, `infinite` 状态机（`PlaybackQueueController.kt` 行 388, 408, 430）：属于播放队列实时状态（A3），`SettingsStore` 仅持有持久化默认值并实现 `QueuePreferences` 供其读取和回写。
- `LanguageSettings`（`LanguageSettings.kt` 行 40, 67）：应用多语言设置，直接调用 `AppCompatDelegate.setApplicationLocales` 由系统持久化，不经过 SharedPreferences，属于系统/UI 层配置。
- `stationHistory`（`AtvApp.kt` 行 221）：属于播放历史记录，留给 A2/A3。
- `search_history`（`FlymeSearch.kt` 行 22）：属于搜索历史，留给 A2。

---

### 2. 对外接口

```kotlin
package dev.amatv.core

import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.QueuePreferences
import kotlinx.coroutines.flow.StateFlow

enum class AppearanceMode { Light, Dark, System, Time }
enum class LyricBackgroundStyle { Flow, Soak }

data class SettingsState(
    // 播放偏好 (am_atv_playback_preferences)
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val shuffleEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val lowPerformanceMode: Boolean = false,

    // 歌词偏好 (am_atv_playback_preferences)
    val lyricsPreferred: Boolean = true,
    val lyricOffsetMs: Long = 0L,
    val lyricTextSize: LyricTextSize = LyricTextSize.Standard,
    val lyricTranslationEnabled: Boolean = false,
    val lyricPronunciationEnabled: Boolean = false,
    val lyricTranslationLanguageTag: String = "",
    val lyricPronunciationLanguageTag: String = "en-US",

    // 车机外观与播放选项 (am_atv_flyme_playback)
    val lyricStyle: LyricBackgroundStyle = LyricBackgroundStyle.Flow,
    val appearance: AppearanceMode = AppearanceMode.System,
    val lightFromMinute: Int = 7 * 60,
    val lightUntilMinute: Int = 19 * 60,
    val fadeAlbums: Boolean = false,
    val fadeStations: Boolean = true,
    val fadePlaylists: Boolean = true,
    val stationsAsSongs: Boolean = false,

    // 缓存偏好 (content-cache)
    val cacheDiskEnabled: Boolean = true,
    val cachePrefetchNextTwo: Boolean = true,
) {
    /** 辅助判断当前时刻在指定系统夜间模式下是否应渲染为浅色界面 */
    fun isLight(systemDark: Boolean, minuteOfDay: Int): Boolean = when (appearance) {
        AppearanceMode.Light -> true
        AppearanceMode.Dark -> false
        AppearanceMode.System -> !systemDark
        AppearanceMode.Time -> if (lightFromMinute <= lightUntilMinute) {
            minuteOfDay in lightFromMinute until lightUntilMinute
        } else {
            minuteOfDay >= lightFromMinute || minuteOfDay < lightUntilMinute
        }
    }
}

interface SettingsStorage {
    fun load(): SettingsState
    fun persist(state: SettingsState)
}

class SettingsStore(
    private val storage: SettingsStorage,
) : QueuePreferences {
    val state: StateFlow<SettingsState>

    // 播放偏好操作
    fun setRepeatMode(mode: PlaybackRepeatMode)
    fun setShuffleEnabled(enabled: Boolean)
    fun setAutoplayEnabled(enabled: Boolean)
    fun setLowPerformanceMode(enabled: Boolean)

    // 歌词偏好操作
    fun setLyricsPreferred(preferred: Boolean)
    fun setLyricOffsetMs(offsetMs: Long)
    fun setLyricTextSize(size: LyricTextSize)
    fun setLyricTranslationEnabled(enabled: Boolean)
    fun setLyricPronunciationEnabled(enabled: Boolean)
    fun setLyricTranslationLanguageTag(tag: String)
    fun setLyricPronunciationLanguageTag(tag: String)

    // 车机样式与音频过渡操作
    fun setLyricStyle(style: LyricBackgroundStyle)
    fun setAppearance(appearance: AppearanceMode)
    fun setLightSchedule(fromMinute: Int, untilMinute: Int)
    fun setFadeAlbums(fade: Boolean)
    fun setFadeStations(fade: Boolean)
    fun setFadePlaylists(fade: Boolean)
    fun setStationsAsSongs(asSongs: Boolean)

    // 缓存设置操作
    fun setCacheDiskEnabled(enabled: Boolean)
    fun setCachePrefetchNextTwo(enabled: Boolean)

    // QueuePreferences 协议实现，供 PlaybackQueueController 读写
    override var repeatMode: PlaybackRepeatMode
    override var shuffleEnabled: Boolean
    override var autoplayEnabled: Boolean
    override var lowPerformanceMode: Boolean
}
```

---

### 3. 依赖与生命周期

- **构造依赖**：`SettingsStorage` 抽象接口。在生产代码中，由 `SharedPreferencesSettingsStorage(context: Context)` 实现，其内部读写现有的三份 SharedPreferences（`am_atv_playback_preferences`、`am_atv_flyme_playback`、`content-cache`）。在 JVM 单元测试中，传入 `FakeSettingsStorage`（基于内存 Map）。
- **无协程依赖**：所有设置读写为同步内存更新（StateFlow）+ 异步/后台持久化（`apply()`），无需外部 CoroutineScope 即可保证纯粹同步读。
- **生命周期**：整个进程生命周期内单例，由 `AmAtvApplication` 在 `onCreate()` 时初始化持有。

---

### 4. 和其他块的交界

- **与 `PlaybackQueueController`**：`SettingsStore` 实现了 `QueuePreferences`，`PlaybackQueueController` 可以继续接收它，并在用户循环播放/随机切换时直接读写该属性，`SettingsStore` 保证属性写入能同步更新 `state.value` 并落盘。
- **与 `LyricsStore`**：`LyricsStore` 观察 `SettingsStore.state`，当歌词语言、翻译开关或注音开关变化时，自动重新生成 `LyricsLocalizationRequest` 与变体标识，驱动当前歌曲的重新解析与加载。
- **与 `FlymePlaybackOptions`**：`FlymePlaybackOptions` 现有的调用方（`CarLayout`、`PlayerScreen`）可直接桥接到 `SettingsStore`，保持向后兼容。

---

### 5. 旧界面怎么接（`AtvApp.kt` 改动）

1. **删除 `AtvApp.kt` 中的 11 个 remember 状态**：
   删除 `lyricsPreferred`, `lowPerformanceMode`, `repeatMode`, `shuffleEnabled`, `autoplayEnabled`, `lyricOffsetMs`, `lyricTextSize`, `lyricTranslationEnabled`, `lyricPronunciationEnabled`, `lyricTranslationLanguageTag`, `lyricPronunciationLanguageTag`。
2. **在 `AmAtvApp` 顶部收集状态**：
   ```kotlin
   val settingsStore = (context.applicationContext as AmAtvApplication).settingsStore
   val settingsState by settingsStore.state.collectAsState()
   ```
3. **替换参数传递与回调**：
   ```kotlin
   PlayerScreen(
       ...
       lyricsPreferred = settingsState.lyricsPreferred,
       onLyricsPreferredChange = settingsStore::setLyricsPreferred,
       lyricOffsetMs = settingsState.lyricOffsetMs,
       onLyricOffsetChange = settingsStore::setLyricOffsetMs,
       lyricTextSize = settingsState.lyricTextSize,
       onLyricTextSizeChange = settingsStore::setLyricTextSize,
       lowPerformanceMode = settingsState.lowPerformanceMode,
       onLowPerformanceModeChange = settingsStore::setLowPerformanceMode,
       lyricTranslationEnabled = settingsState.lyricTranslationEnabled,
       onLyricTranslationEnabledChange = settingsStore::setLyricTranslationEnabled,
       lyricPronunciationEnabled = settingsState.lyricPronunciationEnabled,
       onLyricPronunciationEnabledChange = settingsStore::setLyricPronunciationEnabled,
       lyricTranslationLanguageTag = settingsState.lyricTranslationLanguageTag,
       onLyricTranslationLanguageChange = settingsStore::setLyricTranslationLanguageTag,
       lyricPronunciationLanguageTag = settingsState.lyricPronunciationLanguageTag,
       onLyricPronunciationLanguageChange = settingsStore::setLyricPronunciationLanguageTag,
   )
   ```

---

### 6. 测试计划（`SettingsStoreTest`）

1. `initializesWithDefaultValuesWhenPreferencesEmpty`：当持久化存储为空时，所有设置字段均为设计默认值。
2. `readsExistingPreferencesOnStartup`：当存储中已有历史值时，启动时能够完整准确恢复。
3. `updatingSettingPersistsToStorageAndEmitsNewState`：调用 `setLyricOffsetMs(500)`、`setLyricStyle(Soak)` 等，断言 `state.value` 立即更新且存储被同步持久化。
4. `queuePreferencesInterfaceUpdatesReflectInStateAndStorage`：通过 `QueuePreferences` 属性 setter（如 `repeatMode = One`）赋值，验证 StateFlow 与底层存储同步更新。
5. `isLightCalculatesCorrectlyForVariousModes`：断言 `AppearanceMode.Light`、`Dark`、`System` 以及跨越午夜的 `Time` 模式的时间判断结果。

---

### 7. 风险与不确定点

- **风险点**：`PlaybackQueueController` 在初始化恢复队列时会读写 `QueuePreferences`。如果 `SettingsStore` 的属性访问不是内存即时的，可能会导致启动时队列偏好混乱。
- **保证不变策略**：内部使用 `AtomicReference` 或 StateFlow 的即时值，属性 getter/setter 直接读写内存状态并触发异步持久化，确保与原有 `PlaybackPreferences` 内存语义完全一致。

---

## 二、LibraryActions

### 1. 要搬的东西清单

#### 搬入清单
- `AtvApp.kt`:
  - `favoriteState`（行 223）：`Map<String, Boolean>`，记录歌曲 Catalog ID 对应的喜欢状态缓存。
  - `favoriteCatalogId`（行 224）：记录当前正在检查喜欢状态的歌曲 Catalog ID。
  - `LaunchedEffect(track.catalogId, connectionState)`（行 709-718）：当歌曲 Catalog ID 变化且已登录时，触发从 Apple Music 查询喜欢状态。
  - `toggleFavorite()`（行 943-958）：处理点击星标（喜欢）逻辑，包含乐观更新、网络请求、失败回滚和不可取消提示。
- `AppleMusicApi.kt` 扩展：
  - `addFavorite(catalogSongId: String)`（行 400-404）：已存在，沿用。
  - `isFavorite(catalogSongId: String)`（行 385-393）：已存在，沿用。
  - `addToLibrary(catalogSongId: String)`：新增方法，通过 `POST /v1/me/library?ids%5Bsongs%5D=$catalogSongId` 将曲目加入用户资料库。
  - `isSongInLibrary(catalogSongId: String)`：新增查询，通过 `GET /v1/catalog/{storefront}/songs/$catalogSongId?include=library` 查询歌曲是否在用户资料库中。

#### 明确不搬的清单及原因
- `libraryNextPages`, `libraryVisitedPages`, `libraryLoadingShelves`, `libraryFailedShelves`, `libraryPagingGeneration`, `libraryPageJobs`（`AtvApp.kt` 行 257-262）：属于资料库页面（Library tab）的陈列架与分页浏览，留给 A2（ContentStore）。
- `libraryAlbumsPage`, `libraryArtistsPage`, `libraryPlaylistsPage`, `librarySongsPage`（`AppleMusicApi.kt`）：属于资料库资源浏览 API，留给 A2。
- 播放控制和队列跳歌相关逻辑：留给 A3。

---

### 2. 对外接口

```kotlin
package dev.amatv.core

import dev.amatv.model.Track
import kotlinx.coroutines.flow.StateFlow

sealed interface LibraryNotice {
    data object FavoriteRemoveUnsupported : LibraryNotice
    data object FavoriteFailed : LibraryNotice
    data object AddToLibraryFailed : LibraryNotice
    data object AddedToLibrary : LibraryNotice
    data object AddedToFavorites : LibraryNotice
}

data class CurrentSongLibraryState(
    val catalogId: String? = null,
    val isFavorite: Boolean? = null,
    val isInLibrary: Boolean? = null,
    val isFavoriteInProgress: Boolean = false,
    val isLibraryInProgress: Boolean = false,
)

data class LibraryActionsState(
    val current: CurrentSongLibraryState = CurrentSongLibraryState(),
    val favoriteCache: Map<String, Boolean> = emptyMap(),
    val libraryCache: Map<String, Boolean> = emptyMap(),
    val notice: LibraryNotice? = null,
)

interface LibraryActionsApi {
    suspend fun isFavorite(catalogSongId: String): Boolean?
    suspend fun addFavorite(catalogSongId: String)
    suspend fun isSongInLibrary(catalogSongId: String): Boolean?
    suspend fun addToLibrary(catalogSongId: String)
}

class LibraryActions(
    private val api: LibraryActionsApi,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val state: StateFlow<LibraryActionsState>

    /** 当当前播放歌曲或登录状态变化时调用 */
    fun onCurrentTrackChanged(track: Track?, isSignedIn: Boolean)

    /** 触发喜欢当前歌曲 */
    fun toggleFavorite()

    /** 触发将当前歌曲加入资料库 */
    fun addToLibrary()

    /** 消费/清除通知提示 */
    fun dismissNotice()
}
```

---

### 3. 依赖与生命周期

- **构造依赖**：
  - `LibraryActionsApi`：抽象 API 接口。生产代码由 `AppleMusicApi` 实现；JVM 单元测试由 `FakeLibraryActionsApi` 实现。
  - `coroutineScope: CoroutineScope`：管理异步网络请求，应用级由 `AmAtvApplication` 主协程作用域传入，测试中使用 `TestScope`。
- **无 Activity / UI 依赖**：纯数据流和网络调用。
- **生命周期**：整个进程生命周期内单例，由 `AmAtvApplication` 持有。

---

### 4. 和其他块的交界

- **歌曲来源交界**：在 A3 之前，当前播放的 `track` 仍在 `AtvApp.kt` 状态中。当 `track` 变更或登录状态变更时，`AtvApp.kt` 调用 `libraryActions.onCurrentTrackChanged(track, isSignedIn)`。
- **本地资料库歌曲自动识别**：若传入的 `track.resourceType == "library-songs"` 或 `track.id.startsWith("library:")`，`LibraryActions` 直接将 `isInLibrary` 置为 `true`，无需发起网络请求。
- **通知消息交界**：`LibraryActionsState.notice` 暴露给 UI 层，由 `PlayerScreen` 统一转换为现有的提示文案（如 `R.string.player_favorite_remove_unsupported`、`R.string.player_favorite_failed`）。

---

### 5. 旧界面怎么接（`AtvApp.kt` 改动）

1. **删除 `AtvApp.kt` 中的状态与函数**：
   删除 `favoriteState`、`favoriteCatalogId`、`toggleFavorite()` 以及行 709 的 `LaunchedEffect(track.catalogId, connectionState)`。
2. **在 `AmAtvApp` 顶部收集状态**：
   ```kotlin
   val libraryActions = (context.applicationContext as AmAtvApplication).libraryActions
   val libraryState by libraryActions.state.collectAsState()
   
   LaunchedEffect(track.catalogId, connectionState) {
       libraryActions.onCurrentTrackChanged(track, connectionState == MusicConnectionState.SignedIn)
   }
   ```
3. **接入 `PlayerScreen`**：
   ```kotlin
   PlayerScreen(
       ...
       isFavorite = libraryState.current.isFavorite?.takeIf { libraryState.current.catalogId == track.catalogId },
       onToggleFavorite = libraryActions::toggleFavorite,
       // 若需要展示资料库状态（AM Folio 15 新功能）：
       // isInLibrary = libraryState.current.isInLibrary,
       // onAddToLibrary = libraryActions::addToLibrary,
   )
   ```

---

### 6. 测试计划（`LibraryActionsTest`）

1. `librarySongDetectedLocallyWithoutNetworkCall`：当 `track.resourceType == "library-songs"` 时，无需请求 API，`isInLibrary` 立即为 `true`。
2. `catalogSongFetchesFavoriteAndLibraryStatusWhenSignedIn`：已登录时传入 Catalog 歌曲，成功调用 API 并更新 `isFavorite` 与 `isInLibrary`。
3. `notSignedInDoesNotTriggerApi`：未登录时不发起 API 请求，状态保持 null。
4. `toggleFavoriteOptimisticUpdateAndRollbackOnFailure`：点击喜欢时立即变为 true；若 API 失败，回滚为 false 并发出 `FavoriteFailed` 通知。
5. `toggleFavoriteWhenAlreadyFavoritedEmitsNoticeWithoutCallingApi`：已喜欢时点击，不调用 API，直接发出 `FavoriteRemoveUnsupported` 通知。
6. `addToLibraryOptimisticUpdateAndRollbackOnFailure`：点击加入资料库时乐观更新为 true；若 API 失败，回滚为 false 并发出 `AddToLibraryFailed` 通知。
7. `cachedStatusReusedWhenSwitchingBackToPreviousSong`：切换曲目再次切回时优先读取内存缓存，避免重复网络调用。

---

### 7. 风险与不确定点

- **关于「从资料库移除」和「取消喜欢」的 API 限制调研结论**：
  - **取消喜欢（Remove from Favorite Songs）**：代码已有明确注释（`AppleMusicApi.kt` 行 396-399），Apple Music 公开 API 对 `DELETE /v1/me/favorites` 返回 40012 Insufficient Permissions (`Favorites:DELETE:IdsQuery`)，仅允许苹果第一方客户端取消喜欢，第三方公开 API 不支持。
  - **从资料库移除（Remove from Library）**：经核实 Apple Music REST API 官方规范与 Apple Developer Forums 开发者讨论，Apple Music 公开 API **仅提供** `POST /v1/me/library?ids[songs]=...` 用于添加，**没有提供**公开的 `DELETE` 接口从个人资料库删除单曲/专辑（`DELETE /v1/me/library/songs/{id}` 并不向第三方开放，仅能删除自建歌单 `DELETE /v1/me/library/playlists/{id}`）。因此，**第三方客户端同样不支持从资料库移除歌曲**。在设计中，若歌曲已在资料库中，再次点击加入资料库操作将被忽略或提示不可移除。
- **`POST /v1/me/library` 请求格式核对**：
  - 遵循 `AppleMusicApi` 现有的 `addFavorite` 请求写法：
    ```kotlin
    suspend fun addToLibrary(catalogSongId: String) {
        require(catalogSongId.matches(Regex("[0-9]+"))) { "Catalog song ID is invalid" }
        requestRoot("/v1/me/library?ids%5Bsongs%5D=$catalogSongId", needsUserToken = true,
            method = "POST", postBody = "")
    }
    ```
  - Apple Music API 会对成功添加返回 202 Accepted，`requestRoot` 将其视为正常响应。

---

## 三、LyricsStore

### 1. 要搬的东西清单

#### 搬入清单
- `AtvApp.kt`:
  - `lyricsByTrackId`（行 308-310）：内存歌词缓存（最多保留 24 条记录）。
  - `lyricsLookupFailures`（行 311）：记录返回 404/Unavailable 的歌曲 ID，防止重复请求。
  - `activeLyrics`（行 312-321）：当前活跃歌曲的歌词状态（`ActiveLyricsState`：行数据、阶段、变体标识）。
  - `lyricsRetryNonce`（行 322）：歌词重试计数器。
  - `withCachedLyrics(candidate: Track): Track`（行 350-358）：为 Track 填充已缓存歌词。
  - `cacheLyrics(identity: String, variant: String, lines: List<LyricLine>)`（行 360-365）：将解析好的歌词写入内存缓存。
  - `retryCurrentLyrics()`（行 584-591）：清除不可用标记并重试加载当前歌词。
  - `acceptResolvedTrack` 中歌词缓存 key 的迁移（行 375-380）：Library 曲目解析出新 Catalog ID 时重命名缓存键。
  - 队列后续歌曲预取副作用（行 1372-1410）：当正在播放且缓存开启时，预拉取队列接下来的曲目歌词。
  - 缓存清理响应副作用（行 1411-1418）：清理磁盘缓存时保留正在显示的当前歌词，清空历史缓存。
  - 当前歌词加载副作用（行 1420-1545）：当前歌曲歌词的主加载链路，包含基线兜底、变体计算、API 拉取及重试。
  - `lyricsLocalization` 与 `lyricsVariant` 的动态计算（行 289-304）：根据语言、翻译和注音开关组合生成请求变体。

#### 明确不搬的清单及原因
- `lyricsPreferred`（行 167）：歌词开关偏好属于用户设置，归入 `SettingsStore`。
- `lyricOffsetMs`（行 274）：歌词偏移偏好属于用户设置，归入 `SettingsStore`。
- `lyricTextSize`（行 275）：歌词字体偏好属于用户设置，归入 `SettingsStore`。
- `systemPlaybackSnapshot` / `publishSystemSnapshot`（行 1361-1367）：将当前歌词发布至 Flyme 车机系统 MediaSession 元数据，属于系统集成与播放控制（A3），由调用方监听 `LyricsStore.state` 后触发。
- `ArtworkImages.prefetch(context, it)`（行 1385）：封面图片下载预取属于图片加载管线，不属于歌词业务。
- `queueController.updateCatalogIdentity`（行 374）：队列中曲目 Catalog ID 的更新属于播放队列状态（A3）。

---

### 2. 对外接口

```kotlin
package dev.amatv.core

import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import kotlinx.coroutines.flow.StateFlow

enum class LyricsLoadPhase {
    Idle,
    Loading,
    Ready,
    Unavailable,
    Failed,
}

data class LyricsState(
    val trackIdentity: String = "",
    val lines: List<LyricLine> = emptyList(),
    val phase: LyricsLoadPhase = LyricsLoadPhase.Idle,
    val resolvedVariant: String? = null,
) {
    val isLoading: Boolean get() = phase == LyricsLoadPhase.Loading
    val isLookupFailed: Boolean get() = phase == LyricsLoadPhase.Unavailable
    val isTransientFailure: Boolean get() = phase == LyricsLoadPhase.Failed
    val isReady: Boolean get() = phase == LyricsLoadPhase.Ready
}

interface LyricsSource {
    suspend fun resolvePlayableTrack(track: Track): Track
    suspend fun lyricsFor(track: Track, localization: LyricsLocalizationRequest): List<LyricLine>
    fun canWriteCache(): Boolean
    val cacheGeneration: Long
}

class LyricsStore(
    private val source: LyricsSource,
    private val settingsStore: SettingsStore,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val appLanguageTagProvider: () -> String,
) {
    val state: StateFlow<LyricsState>

    /** 当前播放歌曲或登录状态变化时由宿主调用 */
    fun setCurrentTrack(track: Track?, isSignedIn: Boolean)

    /** 队列后续预取目标变动时由宿主调用（最多预取前 2 首） */
    fun setPrefetchTargets(targets: List<Track>, isPlaying: Boolean)

    /** 重试加载当前歌曲歌词 */
    fun retryCurrentLyrics()

    /** 从缓存中填充歌曲歌词（用于播放前注入或展示） */
    fun withCachedLyrics(candidate: Track): Track

    /** 当 Library 歌曲解析完成并获得最新 Track 时调用，负责迁移歌词缓存 key */
    fun onTrackIdentityResolved(oldIdentity: String, newIdentity: String)

    /** 当磁盘缓存清空时调用，保留当前播放行的同时重置历史缓存 */
    fun onCacheCleared()
}
```

---

### 3. 依赖与生命周期

- **构造依赖**：
  - `LyricsSource`：抽象数据源接口。生产代码由 `MusicServices` 与 `ContentCache` 实现包装（包装 `services.resolvePlayableTrack`、`services.lyricsFor` 及 `services.contentCache`）；测试中使用 `FakeLyricsSource`。
  - `SettingsStore`：提供歌词本地化配置（翻译/注音开关、目标语言 tag）。
  - `appLanguageTagProvider: () -> String`：提供应用界面的当前语言（用于默认歌词语言判定）。
  - `coroutineScope: CoroutineScope`：管理歌词拉取作业生命周期。
- **无 Activity / UI 依赖**：纯 Kotlin 协程与业务数据流。
- **生命周期**：整个进程生命周期内单例，由 `AmAtvApplication` 持有。

---

### 4. 和其他块的交界

- **与 `SettingsStore`**：`LyricsStore` 观察 `SettingsStore.state`。当用户在界面中开关翻译、注音或切换语言时，`LyricsStore` 自动计算新的 `LyricsLocalizationRequest` 与 `lyricsVariant`，并触发当前歌曲的新变体加载流程。
- **与播放宿主（A3 前为 `AtvApp.kt`，A3 之后为 `PlaybackController`）**：
  - 宿主在切歌或 Session 恢复时调用 `setCurrentTrack(track, isSignedIn)`。
  - 宿主在准备播放下一首或恢复队列时调用 `lyricsStore.withCachedLyrics(track)`。
  - 宿主在队列或播放状态变动时调用 `setPrefetchTargets(targets, isPlaying)`。
- **与车机系统媒体会话（MediaSession）**：宿主监听 `lyricsStore.state`，当 `state.lines` 产生变更且处于当前歌曲时，调用 `publishSystemSnapshot` 发布到车机桌面与仪表盘。

---

### 5. 旧界面怎么接（`AtvApp.kt` 改动）

1. **删除 `AtvApp.kt` 中的歌词状态与函数**：
   删除 `lyricsByTrackId`, `lyricsLookupFailures`, `activeLyrics`, `lyricsRetryNonce`, `retryCurrentLyrics()`, `withCachedLyrics()`, `cacheLyrics()`，以及行 1372-1418、1420-1545 的两个大型 `LaunchedEffect`。
2. **在 `AmAtvApp` 顶部收集状态与驱动**：
   ```kotlin
   val lyricsStore = (context.applicationContext as AmAtvApplication).lyricsStore
   val lyricsState by lyricsStore.state.collectAsState()
   
   val lyricsRequestReady = startupSessionLoaded && hasCurrentTrack &&
       connectionState == MusicConnectionState.SignedIn
   LaunchedEffect(track, lyricsRequestReady) {
       lyricsStore.setCurrentTrack(track.takeIf { lyricsRequestReady }, connectionState == MusicConnectionState.SignedIn)
   }
   
   LaunchedEffect(prefetchTargets, isPlaying) {
       lyricsStore.setPrefetchTargets(prefetchTargets, isPlaying)
   }
   ```
3. **在播放与列表处调用 `withCachedLyrics`**：
   原有所有 `withCachedLyrics(...)` 统一替换为 `lyricsStore.withCachedLyrics(...)`。
4. **接入 `PlayerScreen`**：
   ```kotlin
   PlayerScreen(
       ...
       track = track.copy(lyrics = lyricsState.lines),
       lyricsLoading = lyricsState.isLoading && lyricsState.trackIdentity == currentLyricsIdentity,
       lyricsLookupFailed = lyricsState.isLookupFailed && lyricsState.trackIdentity == currentLyricsIdentity,
       lyricsTransientFailure = lyricsState.isTransientFailure && lyricsState.trackIdentity == currentLyricsIdentity,
       onRetryLyrics = lyricsStore::retryCurrentLyrics,
       lyricsEnhancementsReady = !lyricsLocalization.enabled ||
           (lyricsState.trackIdentity == currentLyricsIdentity &&
               lyricsState.resolvedVariant == lyricsVariant &&
               lyricsState.isReady),
   )
   ```

---

### 6. 测试计划（`LyricsStoreTest`）

1. `loadsLyricsSuccessfullyAndUpdatesStateToReady`：歌曲拥有有效歌词时，成功获取并发布 `LyricsLoadPhase.Ready` 及解析好的行。
2. `hitMemoryCacheAvoidsNetworkCall`：当歌词已存在于内存缓存中时，直接命中，不触发底层 `lyricsFor` 调用。
3. `cachesBaselineWhenLocalizationDisabled`：当未启用翻译/注音且歌曲已自带基线歌词时，直接使用基线并置为 `Ready`。
4. `handlesUnavailableDefinitiveMissAndRemembersFailure`：API 返回 404/Unavailable 时，进入 `Unavailable` 阶段，并记录失败 ID 避免重复请求。
5. `handlesTransientFailureWithRetryableState`：临时网络故障时进入 `Failed` 阶段，同时保留基线文本显示不发生闪烁。
6. `retryCurrentLyricsClearsFailureAndTriggersFetch`：调用 `retryCurrentLyrics()` 能够移除失败记录并重新拉取。
7. `withCachedLyricsEnrichesTrack`：验证 `withCachedLyrics` 能为 Track 填充已缓存的歌词行。
8. `trackIdentityResolutionMigratesCacheKeys`：当歌曲 Catalog ID 发生映射变更时，成功迁移内存缓存中的 Key，后续访问不失效。
9. `prefetchUpcomingTargetsLoadsAndCachesAhead`：当满足预取条件时，预拉取队列后续曲目的歌词并写入缓存。
10. `cacheClearedRetainsActiveLines`：触发 `onCacheCleared()` 时，清空历史缓存但保留当前正在播放的行。

---

### 7. 风险与不确定点

- **风险点：歌词基线兜底逻辑在异步加载中的平滑性**：
  `AtvApp.kt` 原有逻辑保证了在切换语言或网络重试时，不会闪烁空白歌词（已有的 `track.lyrics` 作为基线保留显示）。
- **保证不变策略**：
  在 `LyricsStore` 中原样继承 `ActiveLyricsState` 对 `baseline` 的保护机制：在进入 `Loading` 或 `Failed` 时，当前 state 的 `lines` 继续保持基线或已有行，只有在 `Unavailable` 且无基线时才显示空行。

---

## 四、建议的提交顺序

为了保证每个提交粒度清晰、独立完整、且每次提交均能通过编译与测试，建议按如下顺序实施：

1. **Commit 1: `docs: add a1 architecture and migration design`** (当前提交)
   - 提交 `docs/folio/a1-design.md`。不改动任何 Kotlin 代码。
2. **Commit 2: `core: implement SettingsStore with JVM unit tests`**
   - 新建 `app/src/main/java/dev/amatv/core/SettingsStore.kt` 及 `SettingsStorage.kt`。
   - 新建 `app/src/test/java/dev/amatv/core/SettingsStoreTest.kt`，实现全套 JVM 单元测试。
   - 保持现有 `AtvApp.kt` 不变，确保编译与测试通过。
3. **Commit 3: `core: implement LibraryActions and AppleMusicApi library extension`**
   - 在 `AppleMusicApi.kt` 中增加 `addToLibrary` 及 `isSongInLibrary` 方法。
   - 新建 `app/src/main/java/dev/amatv/core/LibraryActions.kt`。
   - 新建 `app/src/test/java/dev/amatv/core/LibraryActionsTest.kt`，编写完整 JVM 测试。
4. **Commit 4: `core: implement LyricsStore with JVM unit tests`**
   - 新建 `app/src/main/java/dev/amatv/core/LyricsStore.kt`。
   - 新建 `app/src/test/java/dev/amatv/core/LyricsStoreTest.kt`，覆盖内存缓存、基线兜底、变体切换、重试及预取测试。
5. **Commit 5: `ui: wire AtvApp to SettingsStore, LibraryActions, and LyricsStore`**
   - 在 `AmAtvApplication` 中注册并初始化这三个核心层 Store。
   - 重构 `AtvApp.kt`：移除对应的 `remember` 状态与副作用函数，改为观察和调用 `SettingsStore`、`LibraryActions` 和 `LyricsStore`。
   - 运行全量单元测试与构建验证，确保旧界面行为百分之百无退化。
