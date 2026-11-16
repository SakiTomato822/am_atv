# A1 设计方案：SettingsStore、LibraryActions、LyricsStore（修订版）

本文档定义 AM Folio 15 重构阶段 A1 的核心层架构与迁移设计。核心层文件位于 `app/src/main/java/dev/amatv/core/`，包名为 `dev.amatv.core`。
本设计稿已根据 Claude 的审查意见（`A1-REVIEW.md`）进行了全面修订。

---

## 总体原则与单例生命周期

1. **搬代码，不重写逻辑**：能把原代码块原样剪切过去就原样剪切，只改变量的来源，不改动已有判断条件、错误处理和执行顺序。
2. **纯 Kotlin 逻辑**：核心层类不依赖任何 Compose 库（零 `androidx.compose.*` 依赖），不持有 Activity 或 UI 组件引用。
3. **单例与协程作用域**：
   - 三个核心 Store 全局由 `AmAtvApplication` 单例持有。
   - 内部协程作用域统一采用 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`，保证状态更新保留在主线程（与 Compose 响应模型一致）。
   - 涉及网络与磁盘 I/O 的底层调用在调用点使用 `withContext(Dispatchers.IO)`，与原代码一致。
   - Compose 界面内统一使用 `remember { (context.applicationContext as AmAtvApplication).xxx }` 获取实例。
4. **存储与格式完全兼容**：沿用现有 `PlaybackPreferences`（`am_atv_playback_preferences`）、`FlymePlaybackOptions`（`am_atv_flyme_playback`）以及 `ContentCache`（`content-cache`）的 SharedPreferences 文件与键名，不改存储结构，不做数据迁移。
5. **纯 JVM 单元测试**：测试位于 `app/src/test/java/dev/amatv/core/`，全部采用 Fake 依赖，不改动、不削弱已有测试。

---

## 一、SettingsStore

### 1. 唯一数据源保证（消除双重数据源）

为防止内存出现双重状态，在**同一个提交**中，将所有设置字段的读写方全部迁移至 `SettingsStore`。
原有的 `PlaybackPreferences`、`FlymePlaybackOptions` 和 `ContentCache` 仅保留底层存储适配器的角色，界面与业务逻辑不再直接读写旧类的设置项。

#### 「字段 → 原读写方 → 改后」对照表

| 字段名 | 原存储 / 键名 | 原读写方 | 改后读写方 |
|---|---|---|---|
| `repeatMode` | `am_atv_playback_preferences`<br>`pref_playback_repeat_mode` | - `AtvApp.kt` (行 271 remember)<br>- `PlaybackQueueController.kt` (行 132, 430, 609) | `SettingsStore.repeatMode` / `SettingsStore.setRepeatMode`。`PlaybackQueueController` 构造时直接传入 `SettingsStore`。 |
| `shuffleEnabled` | `am_atv_playback_preferences`<br>`pref_playback_shuffle_enabled` | - `AtvApp.kt` (行 272 remember)<br>- `PlaybackQueueController.kt` (行 133, 172, 408, 610) | `SettingsStore.shuffleEnabled` / `SettingsStore.setShuffleEnabled`。 |
| `autoplayEnabled` | `am_atv_playback_preferences`<br>`pref_playback_autoplay_enabled` | - `AtvApp.kt` (行 273 remember)<br>- `PlaybackQueueController.kt` (行 134, 388, 611) | `SettingsStore.autoplayEnabled` / `SettingsStore.setAutoplayEnabled`。 |
| `lowPerformanceMode` | `am_atv_playback_preferences`<br>`pref_playback_low_performance_mode` | - `AtvApp.kt` (行 208 remember, 行 2123)<br>- `PlaybackQueueController.kt`<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lowPerformanceMode`，更新调 `settingsStore.setLowPerformanceMode`。 |
| `lyricsPreferred` | `am_atv_playback_preferences`<br>`pref_playback_lyrics_preferred` | - `AtvApp.kt` (行 167 remember, 行 2150)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricsPreferred`，更新调 `settingsStore.setLyricsPreferred`。 |
| `lyricOffsetMs` | `am_atv_playback_preferences`<br>`pref_playback_lyric_offset_ms` | - `AtvApp.kt` (行 274 remember, 行 2118)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricOffsetMs`，更新调 `settingsStore.setLyricOffsetMs`。 |
| `lyricTextSize` | `am_atv_playback_preferences`<br>`pref_lyric_text_size` | - `AtvApp.kt` (行 275 remember, 行 2214)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricTextSize`，更新调 `settingsStore.setLyricTextSize`。 |
| `lyricTranslationEnabled` | `am_atv_playback_preferences`<br>`pref_lyric_translation_enabled` | - `AtvApp.kt` (行 276 remember, 行 2228)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricTranslationEnabled`，更新调 `settingsStore.setLyricTranslationEnabled`。 |
| `lyricPronunciationEnabled` | `am_atv_playback_preferences`<br>`pref_lyric_pronunciation_enabled` | - `AtvApp.kt` (行 279 remember, 行 2232)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricPronunciationEnabled`，更新调 `settingsStore.setLyricPronunciationEnabled`。 |
| `lyricTranslationLanguageTag` | `am_atv_playback_preferences`<br>`pref_lyric_translation_language` | - `AtvApp.kt` (行 282 remember, 行 2236)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricTranslationLanguageTag`，更新调 `settingsStore.setLyricTranslationLanguageTag`。 |
| `lyricPronunciationLanguageTag` | `am_atv_playback_preferences`<br>`pref_lyric_pronunciation_language` | - `AtvApp.kt` (行 285 remember, 行 2240)<br>- `PlayerScreen.kt` | `SettingsStore.state.value.lyricPronunciationLanguageTag`，更新调 `settingsStore.setLyricPronunciationLanguageTag`。 |
| `lyricStyle` | `am_atv_flyme_playback`<br>`lyric_style_soak` | - `FlymePlaybackOptions.kt` (行 50)<br>- `FlymeMediaSettings.kt` (行 121)<br>- `PlayerScreen.kt` (行 83) | `SettingsStore.state.value.lyricStyle`，更新调 `settingsStore.setLyricStyle`。`FlymePlaybackOptionsSettings` 与 `PlayerScreen` 改接 `settingsStore`。 |
| `appearance` | `am_atv_flyme_playback`<br>`appearance` | - `FlymePlaybackOptions.kt` (行 45)<br>- `FlymeMediaSettings.kt` (行 139)<br>- `CarLayout.kt` (行 22) | `SettingsStore.state.value.appearance`，更新调 `settingsStore.setAppearance`。`CarLayout` 读 `settingsStore.state`。 |
| `lightFromMinute`, `lightUntilMinute` | `am_atv_flyme_playback`<br>`light_from_minute`, `light_until_minute` | - `FlymePlaybackOptions.kt` (行 48-49)<br>- `FlymeMediaSettings.kt` (行 158-170)<br>- `CarLayout.kt` (行 22) | `SettingsStore.setLightSchedule(from, until)`。`FlymeAppearanceSettings` 改接 `settingsStore`。 |
| `fadeAlbums`, `fadeStations`, `fadePlaylists` | `am_atv_flyme_playback`<br>`fade_albums`, `fade_stations`, `fade_playlists` | - `FlymePlaybackOptions.kt` (行 41-43)<br>- `FlymeMediaSettings.kt` (行 107-112)<br>- `AtvApp.kt` (行 721-729) | `SettingsStore.setFadeAlbums` 等。`AtvApp.kt` 切歌淡入淡出 LaunchedEffect 读 `settingsStore.state`。 |
| `stationsAsSongs` | `am_atv_flyme_playback`<br>`stations_as_songs` | - `FlymePlaybackOptions.kt` (行 44)<br>- `FlymeMediaSettings.kt` (行 118)<br>- `MusicServices.kt` (行 202) | `SettingsStore.setStationsAsSongs`。`MusicServices` 读 `settingsStore.state.value.stationsAsSongs`。 |
| `cacheDiskEnabled` | `content-cache`<br>`enabled` | - `ContentCache.kt` (行 43, 57)<br>- `ContentCacheSettings.kt` (行 53)<br>- `AtvApp.kt` (行 1376) | `SettingsStore.state.value.cacheDiskEnabled`，更新调 `settingsStore.setCacheDiskEnabled`。`ContentCache` 监听同步更新。 |
| `cachePrefetchNextTwo` | `content-cache`<br>`prefetch-next-two` | - `ContentCache.kt` (行 44, 60)<br>- `ContentCacheSettings.kt` (行 60)<br>- `AtvApp.kt` (行 1376) | `SettingsStore.state.value.cachePrefetchNextTwo`，更新调 `settingsStore.setCachePrefetchNextTwo`。 |

#### 明确不搬入的清单及原因
- `activeTab` / `saveActiveTab`（`PlaybackPreferences.kt` 行 151-159）：界面导航状态，留给 A2（ContentStore / Navigation）。
- `saveSession`, `restoreSession`, `restoreCheckpointSession`, `clearSession`（`PlaybackPreferences.kt` 行 161-171）：播放队列会话持久化，留给 A3（SessionStore）。
- `LanguageSettings`（`LanguageSettings.kt`）：系统语言配置，调用 `AppCompatDelegate.setApplicationLocales` 由系统持久化，属于系统层，不通过 SharedPreferences 存储。

---

### 2. 对外接口与按键精准持久化

为了避免全量 `persist(state)` 把未设置过的字段覆写为默认值、破坏「未设置」的原始语义，`SettingsStorage` 设计为按字段粒度持久化接口。

```kotlin
package dev.amatv.core

import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.QueuePreferences
import kotlinx.coroutines.flow.StateFlow

enum class AppearanceMode { Light, Dark, System, Time }
enum class LyricBackgroundStyle { Flow, Soak }

data class SettingsState(
    // 播放偏好
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val shuffleEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val lowPerformanceMode: Boolean = false,

    // 歌词偏好
    val lyricsPreferred: Boolean = true,
    val lyricOffsetMs: Long = 0L,
    val lyricTextSize: LyricTextSize = LyricTextSize.Standard,
    val lyricTranslationEnabled: Boolean = false,
    val lyricPronunciationEnabled: Boolean = false,
    val lyricTranslationLanguageTag: String = "",
    val lyricPronunciationLanguageTag: String = "en-US",

    // 车机外观与播放选项
    val lyricStyle: LyricBackgroundStyle = LyricBackgroundStyle.Flow,
    val appearance: AppearanceMode = AppearanceMode.System,
    val lightFromMinute: Int = 7 * 60,
    val lightUntilMinute: Int = 19 * 60,
    val fadeAlbums: Boolean = false,
    val fadeStations: Boolean = true,
    val fadePlaylists: Boolean = true,
    val stationsAsSongs: Boolean = false,

    // 缓存偏好
    val cacheDiskEnabled: Boolean = true,
    val cachePrefetchNextTwo: Boolean = true,
) {
    /** 从 FlymePlaybackSettings 原样剪切，不做重写 */
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

/** 按字段细粒度持久化接口，确保每次只写被修改的单个键 */
interface SettingsStorage {
    fun loadInitialState(): SettingsState

    fun saveRepeatMode(mode: PlaybackRepeatMode)
    fun saveShuffleEnabled(enabled: Boolean)
    fun saveAutoplayEnabled(enabled: Boolean)
    fun saveLowPerformanceMode(enabled: Boolean)

    fun saveLyricsPreferred(preferred: Boolean)
    fun saveLyricOffsetMs(offsetMs: Long)
    fun saveLyricTextSize(size: LyricTextSize)
    fun saveLyricTranslationEnabled(enabled: Boolean)
    fun saveLyricPronunciationEnabled(enabled: Boolean)
    fun saveLyricTranslationLanguageTag(tag: String)
    fun saveLyricPronunciationLanguageTag(tag: String)

    fun saveLyricStyle(style: LyricBackgroundStyle)
    fun saveAppearance(appearance: AppearanceMode)
    fun saveLightSchedule(fromMinute: Int, untilMinute: Int)
    fun saveFadeAlbums(fade: Boolean)
    fun saveFadeStations(fade: Boolean)
    fun saveFadePlaylists(fade: Boolean)
    fun saveStationsAsSongs(asSongs: Boolean)

    fun saveCacheDiskEnabled(enabled: Boolean)
    fun saveCachePrefetchNextTwo(enabled: Boolean)
}

class SettingsStore(
    private val storage: SettingsStorage,
) : QueuePreferences {
    val state: StateFlow<SettingsState>

    fun setRepeatMode(mode: PlaybackRepeatMode)
    fun setShuffleEnabled(enabled: Boolean)
    fun setAutoplayEnabled(enabled: Boolean)
    fun setLowPerformanceMode(enabled: Boolean)

    fun setLyricsPreferred(preferred: Boolean)
    fun setLyricOffsetMs(offsetMs: Long)
    fun setLyricTextSize(size: LyricTextSize)
    fun setLyricTranslationEnabled(enabled: Boolean)
    fun setLyricPronunciationEnabled(enabled: Boolean)
    fun setLyricTranslationLanguageTag(tag: String)
    fun setLyricPronunciationLanguageTag(tag: String)

    fun setLyricStyle(style: LyricBackgroundStyle)
    fun setAppearance(appearance: AppearanceMode)
    fun setLightSchedule(fromMinute: Int, untilMinute: Int)
    fun setFadeAlbums(fade: Boolean)
    fun setFadeStations(fade: Boolean)
    fun setFadePlaylists(fade: Boolean)
    fun setStationsAsSongs(asSongs: Boolean)

    fun setCacheDiskEnabled(enabled: Boolean)
    fun setCachePrefetchNextTwo(enabled: Boolean)

    // 实现 QueuePreferences 协议，供 PlaybackQueueController 读写
    override var repeatMode: PlaybackRepeatMode
    override var shuffleEnabled: Boolean
    override var autoplayEnabled: Boolean
    override var lowPerformanceMode: Boolean
}
```

---

## 二、LibraryActions

### 1. 业务逻辑与 API 规范核对

#### (1) 「是否在资料库」查询方式与依据
- **接口与请求**：
  在 `AppleMusicApi.kt` 中使用 `GET /v1/catalog/{storefront}/songs/$catalogSongId?include=library`（`needsUserToken = true`）。
- **字段核对依据**：
  根据 Apple Music API 规范，当在 catalog 歌曲资源中请求 `include=library` 时：
  - 若该歌曲已被加入用户个人 iCloud 资料库，Apple Music 会在返回 JSON 的 `data[0].relationships.library.data` 中嵌入对应关联资源（类型为 `library-songs`，包含对应的用户私有资料库 ID `i.xxx`）。
  - 若该歌曲未被加入用户资料库，`relationships` 中可能不包含 `library` 键，或 `relationships.library.data` 为空数组。
  - **真实的「不知道」处理**：如果响应格式不符合预期（如缺失 `relationships` 或无法确定），返回 `null`（未知）。不进行盲目兜底猜想。
- **模拟器验证要求**：
  在模拟器上播放一首 Catalog 歌曲时，在 Logcat 记录该请求的 HTTP 状态码以及返回体中 `relationships` / `attributes` 与 `library` 相关的键名，不输出用户 token。

#### (2) 旧界面与新接口的行为边界
- **旧界面不新增按钮**：A1 阶段旧界面不增加「加入资料库」UI 按钮，界面行为保持完全一致。
- **`addToLibrary()` 仅由测试覆盖**：新功能留在 B 阶段新界面启用。
- **喜欢（Favorite）流程 100% 保持原有逻辑**：
  - 乐观更新：点击后立即更新状态为 true。
  - 失败回滚：API 抛出异常后回滚为 false，并发出通知提示（提示文案原样使用 `R.string.player_favorite_failed`）。
  - 已喜欢时再次点击：不调用 API，发出提示（原样使用 `R.string.player_favorite_remove_unsupported`）。
- **已在资料库时再次加入资料库**：
  - 不调用网络接口，直接发出 `LibraryNotice.RemoveFromLibraryUnsupported` 通知。

### 2. 对外接口

```kotlin
package dev.amatv.core

import dev.amatv.model.Track
import kotlinx.coroutines.flow.StateFlow

sealed interface LibraryNotice {
    data object FavoriteRemoveUnsupported : LibraryNotice
    data object FavoriteFailed : LibraryNotice
    data object RemoveFromLibraryUnsupported : LibraryNotice
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

    fun onCurrentTrackChanged(track: Track?, isSignedIn: Boolean)
    fun toggleFavorite()
    fun addToLibrary()
    fun dismissNotice()
}
```

---

## 三、LyricsStore

### 1. 循环刷新防护与驱动 Key（重点）

#### (1) 循环刷新隐患与机制解析
在旧实现中，`track` 会被填入已加载的歌词行（`track = track.copy(lyrics = currentLyricsLines)`）。如果直接使用 `LaunchedEffect(track)` 驱动 `setCurrentTrack`，歌词填回 `track` 会触发 `track` 自身变化，进而重新触发 `LaunchedEffect`，陷入无限死循环。

#### (2) 驱动 `setCurrentTrack` 的权威 Key 集合
必须且仅能使用原 `AtvApp.kt` 约 1420 行大 `LaunchedEffect` 的原始参数作为触发条件：
```kotlin
val currentLyricsIdentity = track.lyricsIdentity()
val lyricsRequestReady = startupSessionLoaded && hasCurrentTrack &&
    connectionState == MusicConnectionState.SignedIn

LaunchedEffect(
    currentLyricsIdentity,
    track.catalogKind,
    lyricsRequestReady,
    reloadNonce,
    lyricsRetryNonce,
    cacheSettings.revision,
    lyricsVariant,
) {
    lyricsStore.setCurrentTrack(
        track = track.takeIf { lyricsRequestReady },
        isSignedIn = connectionState == MusicConnectionState.SignedIn,
        variant = lyricsVariant,
    )
}
```
**原原本本对应的 7 个 Key**：
1. `currentLyricsIdentity`（`track.lyricsIdentity()`）
2. `track.catalogKind`
3. `lyricsRequestReady`（`startupSessionLoaded && hasCurrentTrack && connectionState == SignedIn`）
4. `reloadNonce`
5. `lyricsRetryNonce`
6. `cacheSettings.revision`
7. `lyricsVariant`

#### (3) 幂等防重入保证
在 `LyricsStore.setCurrentTrack` 内部：
若传入的 `trackIdentity == state.value.trackIdentity` 且 `variant == state.value.resolvedVariant` 且处于 `Ready` 阶段，该调用必须是**严格空操作（No-op）**。避免在 Activity 重建、横竖屏切换或小窗恢复时引起重复加载。

#### (4) 歌词合回 `track` 的具体位置
原代码中，MediaSession 媒体元数据导出（EVCC 歌词广播、`publishSystemSnapshot`）依赖 `track.lyrics`。
搬移后，在 `AtvApp.kt` 原有合并点保留如下合回逻辑：
```kotlin
val currentLyricsLines = if (lyricsState.trackIdentity == currentLyricsIdentity) {
    lyricsState.lines
} else {
    track.lyrics
}
track = track.copy(lyrics = currentLyricsLines)
```
随后将该包含完整歌词的 `track` 传入 `publishSystemSnapshot` 及 `PlayerScreen(track = track)`，确保车机桌面、仪表盘及 MediaSession 的歌词输出与重构前百分百一致。

#### (5) 基线兜底逻辑原样移植
原 `AtvApp.kt` 行 1462-1478 的 baseline 保护逻辑原封不动移入 `LyricsStore`：
```kotlin
val baseline = (if (activeLyrics.trackIdentity == lookupIdentity) {
    activeLyrics.lines
} else {
    track.lyrics
}).map { it.forDisplay(showTranslation = false, showPronunciation = false) }
```
在拉取本地化歌词时，`activeLyrics` 的初始状态以 `baseline` 填充，阶段置为 `Loading`；若发生临时网络故障（Transient Failure），保留 `baseline` 且阶段置为 `Failed`。测试用例必须严格断言在 `Loading` 与 `Failed` 状态下 `lines == baseline`。

#### (6) 行为变化明确记录
- **唯一允许的行为变化**：由进程级单例带来——当 Activity 重建（如小窗切全屏、配置变更）后，由于 `LyricsStore` 位于 Application 进程单例中，**内存歌词缓存依然保留**，无需重新从磁盘或网络拉取已载入的歌词。
- 其他所有可见 UI 行为、时序、状态流转保持绝对不变。

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

    fun setCurrentTrack(track: Track?, isSignedIn: Boolean, variant: String? = null)
    fun setPrefetchTargets(targets: List<Track>, isPlaying: Boolean)
    fun retryCurrentLyrics()
    fun withCachedLyrics(candidate: Track): Track
    fun onTrackIdentityResolved(oldIdentity: String, newIdentity: String)
    fun onCacheCleared()
}
```

---

## 四、单元测试计划

测试全部位于 `app/src/test/java/dev/amatv/core/`，使用假依赖（Fake），测试异步流转使用协程测试库或标准 `runBlocking`。

### 1. `SettingsStoreTest`
1. `initializesWithDefaultValuesWhenPreferencesEmpty`：存储为空时，所有字段均为设计默认值。
2. `readsExistingPreferencesOnStartup`：存储有历史记录时，启动时准确还原。
3. `updatingSettingPersistsOnlyModifiedKey`：更新单个字段（如 `setLyricOffsetMs`），断言仅有对应键被写入存储，其他键不产生多余写入。
4. `queuePreferencesInterfaceUpdatesReflectInStateAndStorage`：通过 `QueuePreferences` 接口（`repeatMode = One` 等）写入，内存 State 与底层存储均同步更新。
5. `isLightCalculatesCorrectlyForVariousModes`：验证浅色/深色/系统/定时（含跨午夜）模式下的时间判断结果。

### 2. `LibraryActionsTest`
1. `librarySongDetectedLocallyWithoutNetworkCall`：`track.resourceType == "library-songs"` 时，无需网络请求，`isInLibrary` 立即为 true。
2. `catalogSongFetchesStatusFromApi`：已登录时，调用 API 获取 `isFavorite` 与 `isInLibrary` 状态。
3. `notSignedInDoesNotTriggerApi`：未登录时不发起网络调用，状态为 null。
4. `toggleFavoriteOptimisticUpdateAndRollbackOnFailure`：喜欢操作先乐观置为 true，API 失败时回滚为 false 并发出 `FavoriteFailed`（对应 `R.string.player_favorite_failed`）。
5. `toggleFavoriteWhenAlreadyFavoritedEmitsNoticeWithoutCallingApi`：已喜欢时再点喜欢，不调 API，发出 `FavoriteRemoveUnsupported`（对应 `R.string.player_favorite_remove_unsupported`）。
6. `addToLibraryOptimisticUpdateAndRollbackOnFailure`：加入资料库操作乐观更新并处理失败回滚。
7. `addToLibraryWhenAlreadyInLibraryEmitsNoticeWithoutCallingApi`：已在资料库中再次点击，不调接口，发出 `RemoveFromLibraryUnsupported`。
8. `apiMissingLibraryRelationshipReturnsNull`：当 API 返回响应中未包含 library 关联信息时，返回 null（未知），不进行臆测。

### 3. `LyricsStoreTest`
1. `loadsLyricsSuccessfullyAndUpdatesStateToReady`：正常歌词获取成功，进入 Ready 阶段并更新行列表。
2. `hitMemoryCacheAvoidsNetworkCall`：命中内存缓存时不触发底层网络获取。
3. `cachesBaselineWhenLocalizationDisabled`：未开启本地化翻译且自带基线歌词时，直接使用基线并进入 Ready。
4. `handlesUnavailableDefinitiveMissAndRemembersFailure`：404/Unavailable 时进入 Unavailable 阶段，并记录不可用避免重复轮询。
5. `retainsBaselineDuringLoadingAndTransientFailure`：**必须断言**在 `Loading` 和临时 `Failed` 状态下，`state.value.lines` 严格等于原有 `baseline` 歌词，绝不闪烁空白。
6. `retryCurrentLyricsClearsFailureAndTriggersFetch`：重试歌词能清空失败标记并重新请求。
7. `duplicateSetCurrentTrackWithSameVariantIsNoOp`：相同曲目和变体重复调用 `setCurrentTrack` 为空操作，不发起多余网络请求。
8. `trackIdentityResolutionMigratesCacheKeys`：当 Library 歌曲解析出真实 catalogId 时，迁移内存缓存键。
9. `prefetchUpcomingTargetsLoadsAndCachesAhead`：满足预取条件时预拉取后两首曲目歌词并存入缓存。
10. `cacheClearedRetainsActiveLines`：触发 `onCacheCleared` 时清空历史缓存，但保留当前正在显示的行。

---

## 五、建议的提交顺序（按 A1-REVIEW.md 实施）

每个 Store 的「核心实现 + 单元测试 + 接入旧界面」放在同一个提交中，保证每一次提交均可独立编译、且全量单元测试通过：

1. **Commit 1: `docs: revise A1 architecture design per review comments`** (当前提交)
   - 提交本修订版设计文档 `docs/folio/a1-design.md`。不改动任何 Kotlin 代码。
2. **Commit 2: `core: implement SettingsStore, unit tests, and wire all callers`**
   - 新增 `dev.amatv.core.SettingsStore`、`SettingsState`、`SettingsStorage`。
   - `AmAtvApplication` 持有单例。
   - 实现全套 `SettingsStoreTest`。
   - 迁移所有读写方：`PlaybackQueueController`（接入 `SettingsStore` 作为 `QueuePreferences`）、`CarLayout`、`FlymePlaybackOptionsSettings`、`FlymeAppearanceSettings`、`ContentCacheSettings`、`AtvApp.kt`。
3. **Commit 3: `core: implement LibraryActions, AppleMusicApi extension, and wire favorite workflow`**
   - 在 `AppleMusicApi.kt` 增加 `addToLibrary` 与 `isSongInLibrary` 方法。
   - 新增 `dev.amatv.core.LibraryActions`、`LibraryActionsState`。
   - `AmAtvApplication` 持有单例。
   - 实现全套 `LibraryActionsTest`。
   - 在 `AtvApp.kt` 中改接喜欢流程，保持原 UI 字符串资源提示与完全相同的交互行为。
4. **Commit 4: `core: implement LyricsStore, unit tests, and wire lyrics pipeline`**
   - 新增 `dev.amatv.core.LyricsStore`、`LyricsState`。
   - `AmAtvApplication` 持有单例。
   - 实现全套 `LyricsStoreTest`（重点断言基线不闪烁与幂等性）。
   - 在 `AtvApp.kt` 中改接歌词加载管线，严格使用 7 个权威 Key 驱动防循环刷新，合回 `track` 保证 MediaSession 输出一致。
