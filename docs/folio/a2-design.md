# A2 设计方案：AccountStore、ContentStore

本文档定义 AM Folio 15 重构阶段 A2 的核心层架构与迁移设计。核心层文件位于 `app/src/main/java/dev/amatv/core/`，包名为 `dev.amatv.core`。
本阶段目标：将 `AtvApp.kt`（旧界面巨型 Compose 函数）中的账号登录认证、Storefront 地区管理，以及首页、最新发行、资料库（含分页）、搜索、详情页加载/缓存全部抽取到核心层，旧界面改接调用，行为与原代码严格保持一致。

---

## 总体原则与生命周期规矩（继承 A1 规范）

1. **搬代码，不重写逻辑**：原代码块能原样剪切就原样剪切，只改变量的来源，不改动已有判断条件、错误处理和执行顺序。
2. **纯 Kotlin 业务逻辑**：核心层类零 Compose 依赖（零 `androidx.compose.*`），不持有 Activity 或 UI 组件引用。
3. **单一数据源**：状态搬入 Store 后，同一个提交里将其所有读写方改接至 Store，旧界面不再直接存这份状态。
4. **进程级单例**：
   - `AccountStore` 与 `ContentStore` 由 `AmAtvApplication` 单例持有。
   - 内部协程作用域统一使用 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`，保证状态更新保留在主线程（与 Compose 响应模型一致）。
   - 网络与磁盘 I/O 底层调用在调用点显式使用 `withContext(Dispatchers.IO)`，与原代码一致。
   - Compose 界面内统一直接通过 `(context.applicationContext as AmAtvApplication).xxx` 获取实例，不加兜底。
5. **唯一允许的可见行为变化**：Activity 重建后内存里的内容依然存在，无需重新拉取。其他任何行为都不变。
6. **不写防御性代码、不吞异常**：不加用不到的空判断或 try/catch，底层异常自然透传，调用处用与原代码相同的规范（`cancellableResult`）处理失败。
7. **纯 JVM 单元测试**：测试位于 `app/src/test/java/dev/amatv/core/`，全部采用 Fake 依赖，测试用例覆盖关键并发、缓存与边界路径。

---

## 一、AccountStore

### 1. 要搬的东西清单

#### 状态与变量
- `connectionState: MusicConnectionState`（原 `AtvApp.kt` 约 209 行 `var connectionState by remember { mutableStateOf(services.connectionState) }`）：当前连接状态（`Demo`、`ReadyToSignIn`、`SignedIn`）。
- `serviceMessage: String?`（原 `AtvApp.kt` 约 210 行 `var serviceMessage by remember { mutableStateOf<String?>(null) }`）：账号相关的临时提示文案（如登录失败、未登录拦截提示、已退出登录等）。
- `userStorefront: String?`（原底层 `services.api.cachedUserStorefront` / `userStorefront()`）：当前账号所属 Storefront 地区代码（如 `"tw"`、`"us"`、`"cn"`）。

#### 函数与操作
- `startSignIn()`（原 `AtvApp.kt` 约 557–564 行）：检查 Demo 模式提示，否则调用 `createSignInIntent()` 返回授权 Activity 的 Intent。
- `completeSignIn(data: Intent)`（原 `AtvApp.kt` 约 429–449 行）：处理 `MusicKitWebAuthActivity` 授权成功返回的 Intent，调用 `services.completeWebAuthentication(data)` 写入 token，更新连接状态为 `SignedIn`。
- `signOut()`（原 `AtvApp.kt` 约 1811–1833 行中账号部分）：调用 `services.signOut()` 清空 token，更新状态为未登录，并派发退出清理事件。
- `dismissServiceMessage()`：清除临时 serviceMessage。

#### 明确不搬入的清单及原因
- `webAuthenticationLauncher`（原 `AtvApp.kt` 约 419–428 行）：`rememberLauncherForActivityResult` 是 Android UI ActivityResult 回调契约，必须留在 Activity / Composable 层；UI 拿到返回的 `Intent` 后调用 `accountStore.completeSignIn(data)`。
- `authLaunchGate`（原 `AtvApp.kt` 约 310 行）：防重复点击弹窗的 UI 防抖状态，属于 UI 控制层。
- 登录/退出时附带的**播放重置与队列清空**逻辑（如 `isPlaying = false`, `queueController.reset()`, `playbackPrefs.clearSession()`）：属于播放与队列域，留给 A3（`PlaybackController` / `SessionStore`）。

---

### 2. 退出与登录时清空的内容分析（412、442、1829 行）

代码中目前有三处出现 `shelvesByDestination = emptyMap()`，各自的触发源和职责如下：

1. **412 行（`refreshAllContent()` 内）**：
   - **触发方**：用户在设置页点击「刷新 Apple Music 内容」，或网络加载失败点击重试按钮（`onRetry`）。
   - **职责**：强制刷新内容。清除内存中的货架 `shelvesByDestination`、清空资料库分页 `resetLibraryPagination()`、清除详情页内存缓存 `services.clearDetailPages()`、清除歌词缓存 `lyricsStore.onCacheCleared()`，并递增 `reloadNonce` 重新向网络请求最新数据。
   - **归属**：由 `ContentStore.refreshAllContent()` 统一接管。

2. **442 行（`webAuthenticationLauncher` 登录成功后）**：
   - **触发方**：用户在 Web 授权页面完成 Apple Music 登录流程并成功返回。
   - **职责**：登录成功后的账号上下文切换。更新 `connectionState = SignedIn`；由于账号发生变化，此前已加载的游客/Demo 货架全部失效，因此清空 `shelvesByDestination`、重置资料库分页、重置歌词缓存，并将当前页导向 `Destination.Home` 重新拉取已登录用户的个性化推荐。
   - **归属**：`AccountStore.completeSignIn()` 成功后触发 `ContentStore.clearContent()` 与重新拉取。

3. **1829 行（`onSignOut` 退出登录后）**：
   - **触发方**：用户在设置页点击「从这台电视退出登录」。
   - **职责**：完整登出清理。调用 `services.signOut()` 清除 Keystore 内的用户 token 与快照；清空货架 `shelvesByDestination = emptyMap()` 与 `recentTracks = emptyList()`；重置资料库分页；清空歌词缓存；清空播放队列与会话；提示「已退出 Apple Music」。
   - **归属**：`AccountStore.signOut()` 负责清除 token 与本地认证状态，同时调用 `ContentStore.clearContent()` 清除内容货架与分页缓存。

---

### 3. 对外接口定义

```kotlin
package dev.amatv.core

import android.content.Intent
import dev.amatv.integration.MusicConnectionState
import kotlinx.coroutines.flow.StateFlow

data class AccountState(
    /** 当前 Apple Music 连接/授权状态 */
    val connectionState: MusicConnectionState = MusicConnectionState.ReadyToSignIn,
    /** 开发者 Token 与服务配置是否就绪 */
    val isCatalogConfigured: Boolean = false,
    /** 当前已登录用户的 Storefront 地区代码（如 "tw", "us"），未登录时为默认 Storefront */
    val storefront: String? = null,
    /** 静态引导配置文案（如证书过期等），无异常时为 null */
    val setupMessage: String? = null,
    /** 临时操作提示（如登录取消、登录失败、已退出登录等） */
    val serviceMessage: String? = null,
) {
    val isSignedIn: Boolean get() = connectionState == MusicConnectionState.SignedIn
}

/** AccountStore 底层操作接口，便于纯 JVM 单元测试注入 Fake */
interface AccountOperations {
    val connectionState: MusicConnectionState
    val setupMessage: String?
    val isCatalogConfigured: Boolean
    suspend fun createSignInIntent(): Intent
    fun completeWebAuthentication(data: Intent): String
    fun signOut()
    suspend fun userStorefront(): String
    fun cachedUserStorefront(): String?
}

class AccountStore(
    private val operations: AccountOperations,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    val state: StateFlow<AccountState>

    /** 获取启动 Web 认证 Activity 的 Intent */
    suspend fun createSignInIntent(): Intent

    /** 处理 Web 认证完成返回的数据 */
    fun completeSignIn(data: Intent): Result<String>

    /** 退出当前 Apple Music 账号 */
    fun signOut()

    /** 弹出或设置提示文案 */
    fun setServiceMessage(message: String?)

    /** 关闭临时提示文案 */
    fun dismissServiceMessage()

    /** 主动刷新连接状态与 Storefront */
    fun refresh()
}
```

---

### 4. 依赖与生命周期

- **持有者**：`AmAtvApplication` 持有单例 `val accountStore by lazy { ... }`。
- **协程作用域**：`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`。
- **底层依赖**：`MusicServices` 实现 `AccountOperations` 接口。
- **生命周期**：与应用进程一致。跨 Activity 重建不会丢失登录状态；Storefront 异步解析完成后自动推送到 `state`。

---

### 5. 字段对照表（AccountStore）

| 字段名 | 原存储 / 来源 | 原读写方 | 改后读写方 |
|---|---|---|---|
| `connectionState` | `services.connectionState` / `SecureTokenStore` | `AtvApp.kt` (行 209 remember, 行 411, 440, 1828) | `accountStore.state.collectAsState().value.connectionState`。更新由 `accountStore.completeSignIn()` / `accountStore.signOut()` 驱动。 |
| `serviceMessage` | `AtvApp.kt` 内存状态 | `AtvApp.kt` (行 210 remember, 行 414, 425, 445, 448, 559, 583, 803, 870, 942, 1612, 1623, 1831, 1844, 1849) | `accountStore.state.collectAsState().value.serviceMessage`。设置调 `accountStore.setServiceMessage()`，关闭调 `accountStore.dismissServiceMessage()`。 |
| `storefront` | `AppleMusicApi.userStorefront` / `am_atv_storefront` | `AppleMusicApi.kt` / `MusicServices.kt` 内部直接读取 | `accountStore.state.value.storefront`。由 `AccountStore` 启动时及登录后异步加载并推送。 |

---

### 6. 测试计划（AccountStore）

测试文件：`app/src/test/java/dev/amatv/core/AccountStoreTest.kt`（纯 JVM，FakeAccountOperations）。

1. **初始状态根据配置与 Token 确定**：未登录且未配置 Developer Token 时为 `Demo`；配置有效但无 Token 时为 `ReadyToSignIn`；有 Token 时为 `SignedIn`。
2. **completeSignIn 成功**：调用底层写入 token，`connectionState` 变为 `SignedIn`，清空 `serviceMessage`，解析并推导当前 storefront。
3. **completeSignIn 失败抛异常**：捕获异常，`connectionState` 维持原状，`serviceMessage` 显示错误文案。
4. **signOut 清空用户状态**：调用底层 `signOut()`，状态变为 `ReadyToSignIn`，`serviceMessage` 设为「已退出登录」。
5. **dismissServiceMessage**：将 `serviceMessage` 置 null。
6. **Storefront 缓存机制**：先立即可见已缓存的 Storefront，后台异步请求 Apple 后更新。

---

## 二、ContentStore

### 1. 要搬的东西清单

#### 状态与变量
- `shelvesByDestination: Map<Destination, List<ContentShelf>>`（原 `AtvApp.kt` 约 250–257 行）：按 Destination（Home / Browse / Library）划分的货架列表。
- `savedShelfDestinations: Set<Destination>`（原 `AtvApp.kt` 约 260 行）：当前展示的是上一次退出时保存的本地磁盘快照、等待网络刷新覆盖的页面集合。
- `loadingDestinations: Set<Destination>`（原 `AtvApp.kt` 约 261–265 行）：当前正在执行网络加载的页面集合。
- `recentTracks: List<Track>`（原 `AtvApp.kt` 约 275–279 行）：最近播放单曲列表（在首页与资料库加载时作为副产品提取出来）。
- `searchResults: List<Track>`（原 `AtvApp.kt` 约 266 行）：搜索结果单曲列表。
- `searchLoading: Boolean`（原 `AtvApp.kt` 约 273 行）：搜索是否正在加载中。
- `libraryNextPages: Map<String, String>`（原 `AtvApp.kt` 约 267 行）：资料库各货架（如 `"lib-albums"`）的下一页分页 URL。
- `libraryVisitedPages: Map<String, Set<String>>`（原 `AtvApp.kt` 约 268 行）：资料库各货架已访问过的分页 URL（用于防重复死循环检测）。
- `libraryLoadingShelves: Set<String>`（原 `AtvApp.kt` 约 269 行）：当前正在加载下一页的货架集合。
- `libraryFailedShelves: Set<String>`（原 `AtvApp.kt` 约 270 行）：加载下一页失败的货架集合。
- `libraryPagingGeneration: Long`（原 `AtvApp.kt` 约 271 行）：资料库分页纪元计数（刷新或重置时递增，作废在途分页请求）。
- `libraryPageJobs: MutableMap<String, Job>`（原 `AtvApp.kt` 约 272 行）：资料库分页请求的协程 Job 映射。
- `shelvesLanguageTag: String`（原 `AtvApp.kt` 约 249 行）：当前货架所使用的语言 Tag。

#### 内部工具与机制
- `ShelfLoads`（原 `AtvApp.kt` 约 2604–2621 行）：页面货架请求去重与并发控制类，保持原有去重和生命周期管理语义。

#### 函数与副作用
- 核心页面加载 `LaunchedEffect`（原 `AtvApp.kt` 约 1304–1616 行）：涵盖 `Destination.Home`（推荐、最近播放、榜单）、`Destination.Browse`（精选、榜单、视频、电台）、`Destination.Library`（最近添加、艺人、专辑、歌单、歌曲、最近播放）的并发组装与本地快照先落再刷逻辑。
- 搜索默认榜单加载 `LaunchedEffect`（原 `AtvApp.kt` 约 1618–1626 行）：搜索页结果为空时，自动预拉取热门歌曲作为发现页。
- 搜索执行 `onSearch`（原 `AtvApp.kt` 约 1835–1854 行）：支持取消上一次在途搜索请求，防抖后发起搜索并更新结果。
- 资料库分页函数：`loadMoreLibraryShelf(shelfId: String)`（原 `AtvApp.kt` 约 365–404 行）、`resetLibraryPagination()`（约 345–353 行）、`rememberLibraryPage(shelfId: String, page: LibraryPage)`（约 355–363 行）。
- 内容全量刷新与清理：`refreshAllContent()`（约 408–417 行）、`clearContent()`。
- 详情页代理接口（原 `AtvApp.kt` 约 1857–1867 行）：`loadCollection`, `cachedCollection`, `savedCollection`, `collectionPartials`, `loadArtistAlbums`, `cachedArtistAlbums`, `savedArtistAlbums`, `loadArtistCatalog`, `cachedArtistCatalog`, `savedArtistCatalog`, `loadArtistViewMore`, `loadArtistOf`。

#### 明确不搬入的清单及原因
- `val firstCandidate = shelvesByDestination[destination]?.firstOrNull()?.items?.firstOrNull()`（原 `AtvApp.kt` 约 937–938 行）：在未选曲且未播放时，点击播放控件用当前页第一首歌作为启动兜底候选。这是播放器触发逻辑，调用方读取 `contentStore.state.value.shelvesFor(destination)` 即可，逻辑留在 `showNowPlaying()` / 播放域（A3）。
- `browseDetailRequest: Track?`（原 `AtvApp.kt` 约 247 行）：播放器界面点击「查看专辑/艺人」触发的 UI 弹窗导航状态，由 BrowseShell 内部处理，属于导航层。
- `stationHistory`（原 `AtvApp.kt` 约 1631–1641 行）：电台播放历史，属于播放会话持久化（A3）。

---

### 2. 对外接口定义

```kotlin
package dev.amatv.core

import dev.amatv.integration.CatalogArtistPage
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LibraryShelfPagination(
    val hasMore: Boolean,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val showAction: Boolean get() = hasMore || loading || failed
}

data class ContentState(
    /** 按页面归类的货架列表（Home / Browse / Library） */
    val shelvesByDestination: Map<Destination, List<ContentShelf>> = emptyMap(),
    /** 正在进行网络加载的页面集合 */
    val loadingDestinations: Set<Destination> = emptySet(),
    /** 正在展示磁盘快照、等待网络结果覆盖的页面集合 */
    val savedShelfDestinations: Set<Destination> = emptySet(),
    /** 最近播放单曲（首页/资料库加载时提取） */
    val recentTracks: List<Track> = emptyList(),
    /** 搜索结果 */
    val searchResults: List<Track> = emptyList(),
    /** 搜索是否在请求中 */
    val searchLoading: Boolean = false,
    /** 资料库各货架的分页状态，Key 为 shelfId（如 "lib-albums"） */
    val shelfPagination: Map<String, LibraryShelfPagination> = emptyMap(),
    /** 错误信息 */
    val errorMessage: String? = null,
) {
    fun shelvesFor(destination: Destination): List<ContentShelf> = shelvesByDestination[destination].orEmpty()
    fun isLoading(destination: Destination): Boolean = destination in loadingDestinations
}

/** ContentStore 数据源接口，隔离 MusicServices，便于纯 JVM 单元测试 */
interface ContentOperations {
    val isCatalogConfigured: Boolean
    suspend fun savedShelves(name: String, languageTag: String): List<ContentShelf>?
    suspend fun saveShelves(name: String, languageTag: String, shelves: List<ContentShelf>)
    suspend fun savedPlaylistIndex(): List<Track>?

    // 页面聚合接口
    suspend fun recommendationShelves(): List<ContentShelf>
    suspend fun recentlyPlayedTracks(): List<Track>
    suspend fun recentlyPlayed(): List<Track>
    suspend fun charts(types: String): List<Track>
    suspend fun liveRadio(): List<Track>
    suspend fun recentlyAdded(): List<Track>

    // 资料库分页接口
    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage

    // 搜索
    suspend fun search(query: String): List<Track>

    // 详情页加载与缓存
    suspend fun collectionTracks(collection: Track): List<Track>
    fun cachedCollectionTracks(collection: Track): List<Track>?
    suspend fun savedCollectionTracks(collection: Track): List<Track>?
    fun collectionPartials(collection: Track): Flow<List<Track>?>

    suspend fun libraryArtistAlbumsPage(artist: Track, nextPage: String? = null): LibraryPage
    fun cachedArtistAlbums(artist: Track): LibraryPage?
    suspend fun savedArtistAlbums(artist: Track): List<Track>?

    suspend fun catalogArtistPage(artist: Track): CatalogArtistPage
    fun cachedArtistPage(artist: Track): CatalogArtistPage?
    suspend fun savedArtistPage(artist: Track): CatalogArtistPage?

    suspend fun catalogArtistViewMore(moreHref: String): List<Track>
    suspend fun catalogArtistOf(track: Track): Track?
    fun clearDetailPages(dropSnapshots: Boolean = false)
}

class ContentStore(
    private val operations: ContentOperations,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    val state: StateFlow<ContentState>

    /**
     * 驱动页面内容加载（对应原大 LaunchedEffect）。
     * 对同一页面、相同参数的重复调用为幂等空操作；已有内容不重复发起请求。
     */
    fun loadDestination(
        destination: Destination,
        connectionState: MusicConnectionState,
        contentLanguageTag: String,
        forceReload: Boolean = false,
    )

    /** 资料库货架加载更多（分页） */
    fun loadMoreLibraryShelf(shelfId: String, connectionState: MusicConnectionState)

    /** 重置资料库所有分页状态 */
    fun resetLibraryPagination()

    /** 搜索页默认内容预拉取（当搜索结果为空时拉取榜单推荐） */
    fun ensureDefaultSearch()

    /** 执行关键词搜索 */
    fun search(query: String)

    /** 清空搜索结果 */
    fun clearSearch()

    /** 全量刷新所有内容（用户点击刷新或重试） */
    fun refreshAllContent(
        currentDestination: Destination,
        connectionState: MusicConnectionState,
        contentLanguageTag: String,
    )

    /** 退出登录或切换账号时清空内容 */
    fun clearContent()

    /** 缓存被外部（如设置页清理磁盘缓存）清除时的回调 */
    fun onCacheCleared()

    // 详情页代理方法，供 UI / BrowseShell 统一调用
    suspend fun loadCollection(collection: Track): List<Track>
    fun cachedCollection(collection: Track): List<Track>?
    suspend fun savedCollection(collection: Track): List<Track>?
    fun collectionPartials(collection: Track): Flow<List<Track>?>

    suspend fun loadArtistAlbums(artist: Track, nextPage: String? = null): LibraryPage
    fun cachedArtistAlbums(artist: Track): LibraryPage?
    suspend fun savedArtistAlbums(artist: Track): List<Track>?

    suspend fun loadArtistCatalog(artist: Track): CatalogArtistPage
    fun cachedArtistCatalog(artist: Track): CatalogArtistPage?
    suspend fun savedArtistCatalog(artist: Track): CatalogArtistPage?

    suspend fun loadArtistViewMore(moreHref: String): List<Track>
    suspend fun loadArtistOf(track: Track): Track?
}
```

---

### 3. 和其他模块的交界设计

1. **ContentStore 与 AccountStore 之间**：
   - 当 `AccountStore.signOut()` 触发时，同步调用 `contentStore.clearContent()` 清空所有已载入的个性化货架、最近播放、资料库分页及搜索结果。
   - 当 `AccountStore.completeSignIn()` 成功时，同步调用 `contentStore.clearContent()`，由 UI 重置回 `Destination.Home` 并重新加载已登录推荐。
   - `ContentStore.loadDestination()` 入参包含 `connectionState: MusicConnectionState`。未登录时，访问 `Destination.Library` 不发起网络请求，直接静默退出。

2. **ContentStore 与 A1 的 LibraryActions 之间**：
   - **现有行为**：用户点击「喜欢」或「加入资料库」时，原代码**并不**重新拉取 `shelvesByDestination[Destination.Library]`，仅更新 Now Playing 当前歌曲的喜欢状态与乐观缓存（`LibraryActionsState.current`）。
   - **维持原样**：A2 完全保留此边界，喜欢/收藏单曲不触发资料库货架的整体刷新。

3. **ContentStore 与 AtvApp 播放逻辑之间**：
   - 原 937 行的 `showNowPlaying()` 读取 `shelvesByDestination` 的首曲作为冷启动备选。改后通过 `contentStore.state.value.shelvesFor(destination)` 读取，播放器本身的控制与选曲留在 AtvApp。

4. **本机内容缓存（`services.contentCache`）接入方式**：
   - 设置页清除缓存时：调用 `services.contentCache.clear { ... }`，随后触发 `services.clearDetailPages()` 和 `contentStore.onCacheCleared()`。
   - `ContentStore` 响应 `onCacheCleared()` 时使当前的本地快照标记失效，保证下一次请求直接读取最新网络数据。

---

### 4. 原 LaunchedEffect Key 对照表

| 原 LaunchedEffect 驱动代码 | 原 Key 集合 | 搬移后驱动方式 | 说明 |
|---|---|---|---|
| 约 1304 行（页面内容加载） | `destination`<br>`connectionState`<br>`reloadNonce`<br>`contentLanguageTag` | `LaunchedEffect(destination, connectionState, contentLanguageTag) { contentStore.loadDestination(destination, connectionState, contentLanguageTag) }`<br>刷新时调 `contentStore.refreshAllContent(...)` | 原 `reloadNonce` 的自增操作并入 `contentStore.refreshAllContent()` 内作为 `forceReload = true` 执行，不再需要外部维持单独的 Int 计数器。 |
| 约 1618 行（搜索页空结果预加载） | `destination` | `LaunchedEffect(destination) { if (destination == Destination.Search) contentStore.ensureDefaultSearch() }` | 保持与原逻辑完全一致：进入 Search 且结果为空时拉取热歌榜。 |
| 约 1835 行（搜索关键词执行） | `onSearch = { query -> ... }` 回调 | `contentStore.search(query)` | 原 `searchJob` 取消与启动内聚在 Store 中。 |

---

### 5. 字段对照表（ContentStore）

| 字段名 | 原状态位置 | 原读写方 | 改后读写方 |
|---|---|---|---|
| `shelvesByDestination` | `AtvApp.kt` 行 250 remember | - `AtvApp.kt` (行 250, 388, 391, 412, 442, 937, 1309, 1338, 1344, 1562, 1604, 1793, 1829) | `contentStore.state.collectAsState().value.shelvesByDestination`，读通过 `shelvesFor(dest)`，写全部归入 `ContentStore` 内部。 |
| `savedShelfDestinations` | `AtvApp.kt` 行 260 remember | - `AtvApp.kt` (行 260, 1322, 1339, 1347, 1602, 1605) | `contentStore.state.value.savedShelfDestinations`，完全由 `ContentStore` 内部维护快照覆写逻辑。 |
| `loadingDestinations` | `AtvApp.kt` 行 261 remember | - `AtvApp.kt` (行 261, 413, 443, 1310, 1324, 1328, 1610, 1799) | `contentStore.state.collectAsState().value.loadingDestinations`。`BrowseShell` 通过 `contentStore.state.value.isLoading(dest)` 读取。 |
| `recentTracks` | `AtvApp.kt` 行 275 remember | - `AtvApp.kt` (行 275, 1383, 1583, 1792, 1830) | `contentStore.state.collectAsState().value.recentTracks`。 |
| `searchResults` | `AtvApp.kt` 行 266 remember | - `AtvApp.kt` (行 266, 1619, 1622, 1790, 1842, 1848) | `contentStore.state.collectAsState().value.searchResults`。 |
| `searchLoading` | `AtvApp.kt` 行 273 remember | - `AtvApp.kt` (行 273, 1620, 1624, 1791, 1838, 1852) | `contentStore.state.collectAsState().value.searchLoading`。 |
| `shelfPagination` / `libraryNextPages` 等 | `AtvApp.kt` 行 267–272 remember | - `AtvApp.kt` (行 267-272, 345-404, 1522, 1533, 1547, 1569, 1794) | `contentStore.state.collectAsState().value.shelfPagination`，分页状态计算完全内聚在 Store 内。 |

---

### 6. 测试计划（ContentStore）

测试文件：`app/src/test/java/dev/amatv/core/ContentStoreTest.kt`（纯 JVM，FakeContentOperations）。
重点断言任务要求的全部 5 个关键场景：

1. **切页面不重复加载已有内容**：
   - 首次切入 `Destination.Home`，触发网络加载，`operations.recommendationShelvesCallCount == 1`；
   - 切入 `Destination.Browse` 后再切回 `Destination.Home`，断言不发起重复网络请求，计数仍为 1。
2. **资料库快照先显示、网络结果再覆盖**：
   - 存在本地快照数据时，`loadDestination(Destination.Library)` 启动瞬间断言 `savedShelfDestinations` 包含 Library，且 `shelvesFor(Library)` 立即显示快照内容；
   - 模拟网络响应返回后，断言 `shelvesFor(Library)` 被网络数据覆盖，且 `savedShelfDestinations` 不再包含 Library。
3. **加载失败时保留原有内容**（按现有代码行为断言）：
   - 当快照已在屏幕展示，后续网络请求全部抛出异常返回空货架时，断言 `keepSaved` 生效：`shelvesFor(target)` 依然保留本地快照内容，不被清空抹除，同时将错误信息暴露到 `errorMessage`。
4. **搜索快速连续输入只保留最后一次结果**：
   - 连续快速调用 `search("query1")` 和 `search("query2")`；
   - 前一次请求被 cancel，最终 `searchResults` 严格等于 `"query2"` 的返回结果，`searchLoading` 最终回到 false。
5. **退出登录后内容清空**：
   - 已加载内容的状态下调用 `clearContent()`，断言 `shelvesByDestination` 为空、`recentTracks` 为空、`searchResults` 为空，资料库所有分页状态重置。
6. **资料库分页防重复死循环**：
   - 当分页接口返回已出现过的 `requestedPage` 时，捕获异常并标记该 shelf 分页失败，不陷入死循环。

---

## 三、旧界面接入示例

### 1. Application 持有单例

```kotlin
// AmAtvApplication.kt
class AmAtvApplication : Application() {
    // A1
    val settingsStore by lazy { ... }
    val libraryActions by lazy { ... }
    val lyricsStore by lazy { ... }

    // A2
    val accountStore by lazy {
        AccountStore(
            operations = musicServices,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    val contentStore by lazy {
        ContentStore(
            operations = musicServices,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
```

### 2. AtvApp.kt 接入改造

```kotlin
@Composable
fun AmAtvApp() {
    val context = LocalContext.current
    val app = context.applicationContext as AmAtvApplication

    val accountStore = remember(context) { app.accountStore }
    val accountState by accountStore.state.collectAsState()

    val contentStore = remember(context) { app.contentStore }
    val contentState by contentStore.state.collectAsState()

    // 驱动内容加载：Key 集合与原代码保持完全等价
    LaunchedEffect(destination, accountState.connectionState, contentLanguageTag) {
        contentStore.loadDestination(
            destination = destination,
            connectionState = accountState.connectionState,
            contentLanguageTag = contentLanguageTag,
        )
    }

    // 搜索发现页预拉取
    LaunchedEffect(destination) {
        if (destination == Destination.Search) {
            contentStore.ensureDefaultSearch()
        }
    }

    // BrowseShell 传参大幅简化
    BrowseShell(
        destination = activeBrowseDestination,
        shelves = contentState.shelvesFor(activeBrowseDestination),
        loading = contentState.isLoading(activeBrowseDestination),
        searchTracks = contentState.searchResults,
        searchLoading = contentState.searchLoading,
        recentTracks = contentState.recentTracks,
        shelfPagination = contentState.shelfPagination,
        onLoadMoreShelf = { shelfId ->
            contentStore.loadMoreLibraryShelf(shelfId, accountState.connectionState)
        },
        connectionState = accountState.connectionState,
        setupMessage = accountState.serviceMessage ?: accountState.setupMessage,
        onSignIn = {
            scope.launch {
                val intent = accountStore.createSignInIntent()
                webAuthenticationLauncher.launch(intent)
            }
        },
        onSignOut = {
            accountStore.signOut()
            contentStore.clearContent()
            // A3 播放与队列清理留在 UI
            queueController.reset()
            playbackPrefs.clearSession()
            // ...
        },
        onRetry = {
            contentStore.refreshAllContent(destination, accountState.connectionState, contentLanguageTag)
        },
        onSearch = contentStore::search,
        loadCollection = contentStore::loadCollection,
        cachedCollection = contentStore::cachedCollection,
        savedCollection = contentStore::savedCollection,
        collectionPartials = contentStore::collectionPartials,
        loadArtistAlbums = contentStore::loadArtistAlbums,
        cachedArtistAlbums = contentStore::cachedArtistAlbums,
        savedArtistAlbums = contentStore::savedArtistAlbums,
        loadArtistCatalog = contentStore::loadArtistCatalog,
        cachedArtistCatalog = contentStore::cachedArtistCatalog,
        savedArtistCatalog = contentStore::savedArtistCatalog,
        loadArtistViewMore = contentStore::loadArtistViewMore,
        // ...
    )
}
```

---

## 四、风险与不确定点分析

### 1. 核心风险

1. **并发与可重入的货架加载覆写风险 (`ShelfLoads` + `savedShelfDestinations` + `keepSaved`)**
   - **风险描述**：原代码在网络加载慢或快速切 tab（Home -> Browse -> Library -> Home）时，使用 `keepSaved` 机制保护屏幕上已展示的本地快照。若网络失败或返回空货架，现有逻辑通过 `if (keepSaved && (current?.isNotEmpty() == true || savedShelfDestinations.contains(target)))` 避免冲掉快照造成白屏/闪屏。
   - **应对策略**：将 `ShelfLoads` 原样迁入 `ContentStore`，完全保留原有请求去重机制和 `keepSaved` 覆写保护逻辑，单测专项验证「网络失败保留快照」路径。

2. **账号切换/登出时的全链路级联清理与并发请求失效**
   - **风险描述**：用户在设置页点击「退出登录」或完成 Web 重新登录时，正在并发进行的网络请求（如资料库分页、货架加载）如果未及时作废，稍后网络返回的数据可能写入已登出或新账号的状态中，导致跨账号数据污染。
   - **应对策略**：`clearContent()` 触发时递增 `libraryPagingGeneration`，取消所有在途的 `libraryPageJobs` 与 `ShelfLoads`；严格维持 412、442、1829 行原有的清除顺序。

3. **资料库分页的独立并发与边界死循环检测 (`visitedPages` + `libraryFailedShelves`)**
   - **风险描述**：资料库包含 4 个独立分页货架（Artists、Albums、Playlists、Songs），各自维护独立的分页 URL、已访问页面集合以及加载失败标记。若 API 返回异常的循环 `next` 链接或网络抖动，必须精准熔断单一货架，不得引发死循环，亦不得影响其他正常货架。
   - **应对策略**：将 `libraryNextPages`、`libraryVisitedPages`、`libraryLoadingShelves`、`libraryFailedShelves` 状态机完全内聚在 `ContentStore` 中，并在单测中覆盖重复页面 URL 注入检测。

---

### 2. 不确定点

1. **详情页控制器（`CollectionBrowser` 与 `LibraryArtistBrowser`）的职责边界**
   - 原代码中详情页的弹出控制由 `CollectionBrowser` 和 `LibraryArtistBrowser` 负责（位于 `dev.amatv.ui`，纯 Kotlin 状态机），它们调用 `services` 的数据加载与缓存方法。
   - 本设计中，`ContentStore` 代理暴露了对应的数据加载方法（如 `loadCollection`、`cachedCollection` 等），UI 弹窗控制器继续保留在 UI 层。后续 A3 若抽取全屏导航栈，可再将弹窗控制器进一步重构。

2. **跨 Store 协作机制：显式方法调用 vs Flow 监听**
   - `AccountStore` 登录成功或退出登录时，需要清空 `ContentStore` 内容。
   - 本设计遵循原代码的行为：在操作触发点（如 `onSignOut` / `onSignIn`）显式调用 `contentStore.clearContent()`，保持确定性与可测试性，避免引入隐式 Flow 观察带来的异步时序不可控。

3. **搜索热歌榜兜底与空搜索结果展示的区分**
   - 首次进入搜索页时，结果为空，原逻辑自动加载热门歌曲；若用户搜索关键词后无匹配结果，界面显示「未找到结果」，此时不应被热歌榜覆盖。
   - 必须通过区分 `searchResults` 为空与是否有活跃查询词，精确保留原代码的行为。

---

## 五、建议的提交顺序

遵循「实现 + 测试 + 旧界面改接」同一提交原则，拆分为 3 个逻辑清晰、可独立编译、单测全过的提交：

1. **Commit 1: `docs: add A2 architecture design for AccountStore and ContentStore`**
   - 提交本设计文档 `docs/folio/a2-design.md`。

2. **Commit 2: `core: implement AccountStore, unit tests, and wire authentication callers`**
   - 新增 `dev.amatv.core.AccountStore` 与 `AccountOperations`；
   - `MusicServices` 实现 `AccountOperations`；
   - `AmAtvApplication` 持有 `accountStore` 单例；
   - 编写 `AccountStoreTest`（纯 JVM 单测）；
   - 改接 `AtvApp.kt` 中的登录、登出、提示消息及所有使用 `connectionState` 的调用点；
   - 全量单测通过。

3. **Commit 3: `core: implement ContentStore, unit tests, and wire browse/search callers`**
   - 新增 `dev.amatv.core.ContentStore` 与 `ContentOperations`；
   - `MusicServices` 实现 `ContentOperations`；
   - `AmAtvApplication` 持有 `contentStore` 单例；
   - 编写 `ContentStoreTest`（覆盖全部 5 个关键场景断言）；
   - 改接 `AtvApp.kt` 中的 `shelvesByDestination`、大 `LaunchedEffect`、搜索、资料库分页及详情页代理；
   - 删除 `AtvApp.kt` 中的 `ShelfLoads` 及冗余 remember 状态；
   - 全量单测通过。
