# A2 设计方案：AccountStore、ContentStore（修订版）

本文档定义 AM Folio 15 重构阶段 A2 的核心层架构与迁移设计。核心层文件位于 `app/src/main/java/dev/amatv/core/`，包名为 `dev.amatv.core`。
本设计稿依据 `A2-REVIEW.md` 审查意见进行了全面修订，严格遵循原有代码的调用语义与时序，不引入任何行为变更。

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

#### 函数与操作
- `completeSignIn(data: Intent): Result<Unit>`（对应原 `AtvApp.kt` 约 429–440 行）：处理授权成功返回的数据，执行 `runCatching { operations.completeWebAuthentication(data) }`，成功后更新 `connectionState = MusicConnectionState.SignedIn`。
- `signOut()`（对应原 `AtvApp.kt` 约 1827–1828 行）：调用底层 `operations.signOut()`，随后同步 `connectionState = operations.connectionState`。
- `syncConnectionState()`（对应原 `AtvApp.kt` 约 411 行）：强制同步底层最新的 `connectionState = operations.connectionState`。

#### 明确不搬入的清单及原因
- `serviceMessage`：保留在 `AtvApp`。播放控制（803、857、870、942、1053 行）等多处共享此状态，AccountStore 不管理提示消息。
- `storefront`：旧界面没有任何地方直接读取它，启动时若额外请求会产生新行为，故不纳入门面。
- `isCatalogConfigured` 与 `setupMessage`：均为 `MusicServices` 的固定只读配置，调用方继续直接读取 `services`，不放入 Store 状态。
- `createSignInIntent` 及相关引导逻辑（约 557–585 行）：包含 `authLaunchGate` 防抖及 UI 弹窗提示，属于 UI 交互层，留在 `AtvApp`。
- 登录 / 退出时的播放、队列、歌词清理：严格保留在 `AtvApp` 原处，保持原有执行顺序。

---

### 2. 对外接口定义

```kotlin
package dev.amatv.core

import android.content.Intent
import dev.amatv.integration.MusicConnectionState
import kotlinx.coroutines.flow.StateFlow

data class AccountState(
    /** 当前 Apple Music 连接/授权状态 */
    val connectionState: MusicConnectionState = MusicConnectionState.ReadyToSignIn,
) {
    val isSignedIn: Boolean get() = connectionState == MusicConnectionState.SignedIn
}

/** AccountStore 底层操作接口，便于纯 JVM 单元测试注入 Fake */
interface AccountOperations {
    val connectionState: MusicConnectionState
    fun completeWebAuthentication(data: Intent): String
    fun signOut()
}

class AccountStore(
    private val operations: AccountOperations,
) {
    val state: StateFlow<AccountState>

    /**
     * 处理 Web 认证完成返回的数据。
     * 成功后置 connectionState = SignedIn。
     */
    fun completeSignIn(data: Intent): Result<Unit>

    /**
     * 退出当前 Apple Music 账号。
     * 调用底层 signOut() 后同步 connectionState。
     */
    fun signOut()

    /**
     * 同步底层 connectionState（对应原 411 行）。
     */
    fun syncConnectionState()
}
```

---

### 3. 依赖与生命周期

- **持有者**：`AmAtvApplication` 持有单例 `val accountStore by lazy { ... }`。
- **底层依赖**：`MusicServices` 实现 `AccountOperations` 接口。
- **生命周期**：与应用进程一致。跨 Activity 重建保留当前连接状态。

---

### 4. 字段对照表（AccountStore）

| 字段名 | 原存储 / 来源 | 原读写方 | 改后读写方 |
|---|---|---|---|
| `connectionState` | `services.connectionState` / `SecureTokenStore` | `AtvApp.kt` (行 209 remember, 行 411, 440, 1828 写入；多处读取) | `accountStore.state.collectAsState().value.connectionState`。写入由 `accountStore.completeSignIn()`、`accountStore.signOut()`、`accountStore.syncConnectionState()` 驱动。 |

---

### 5. 测试计划（AccountStore）

测试文件：`app/src/test/java/dev/amatv/core/AccountStoreTest.kt`（纯 JVM，`FakeAccountOperations`）。

1. **初始状态**：根据 `operations.connectionState` 确定（例如 `ReadyToSignIn` 或 `SignedIn`）。
2. **completeSignIn 成功**：调用底层成功后，`state.value.connectionState` 变为 `SignedIn`，返回 `Result.success(Unit)`。
3. **completeSignIn 失败抛异常**：底层抛出异常时，捕获并返回 `Result.failure`，`connectionState` 维持原状不变。
4. **signOut 清空用户状态**：调用底层 `signOut()`，`state.value.connectionState` 同步为 `operations.connectionState`（如 `ReadyToSignIn`）。
5. **syncConnectionState 同步状态**：底层状态改变后，调用此方法更新 `state.value.connectionState`。

---

## 二、ContentStore

### 1. 要搬的东西清单

#### 状态与变量
- `shelvesByDestination: Map<Destination, List<ContentShelf>>`（原 `AtvApp.kt` 约 250–257 行）：按 Destination（Home / Browse / Library）划分的货架列表。
- `savedShelfDestinations: Set<Destination>`（原 `AtvApp.kt` 约 260 行）：当前展示的是磁盘快照、等待网络刷新覆盖的页面集合。
- `loadingDestinations: Set<Destination>`（原 `AtvApp.kt` 约 261–265 行）：当前正在执行网络加载的页面集合。
- `recentTracks: List<Track>`（原 `AtvApp.kt` 约 275–279 行）：最近播放单曲列表。
- `searchResults: List<Track>`（原 `AtvApp.kt` 约 266 行）：搜索结果单曲列表。
- `searchLoading: Boolean`（原 `AtvApp.kt` 约 273 行）：搜索是否正在加载中。
- `reloadGeneration: Int`（原 `AtvApp.kt` 约 248 行 `reloadNonce`）：重载纪元计数，三处清空操作时自增。
- 资料库分页内部状态：
  - `libraryNextPages: Map<String, String>`（约 267 行）
  - `libraryVisitedPages: Map<String, Set<String>>`（约 268 行）
  - `libraryLoadingShelves: Set<String>`（约 269 行）
  - `libraryFailedShelves: Set<String>`（约 270 行）
  - `libraryPagingGeneration: Long`（约 271 行）
  - `libraryPageJobs: MutableMap<String, Job>`（约 272 行）
- `shelvesLanguageTag: String`（约 249 行）：货架所使用的语言 Tag。

#### 内部机制与工具
- `ShelfLoads`（原 `AtvApp.kt` 约 2604–2621 行）：页面货架请求去重与并发控制类，迁入 Store 内部，key 保持为 `"$target-$connectionState-$reloadGeneration-$contentLanguageTag"`。
- `demoShelves()`（原 `AtvApp.kt` 约 129–140 行）：纯 Kotlin 构造的演示货架，搬入 `ContentStore.kt` 内部使用。
- `LibraryShelfPagination`：原定义在 `HomeScreen.kt`（无 Compose 依赖），移至 `dev.amatv.model.Models.kt` 供 core 与 UI 共享。

#### 消息事件通道
- `messages: SharedFlow<String?>`：替代原代码搬入 Store 后对 `serviceMessage` 的赋值（1611、1623、1843–1849 行），UI 通过 `LaunchedEffect(contentStore) { contentStore.messages.collect { serviceMessage = it } }` 消费。

#### 明确不搬入的清单及原因
- 详情页 12 个代理方法（`loadCollection`、`cachedCollection` 等）：保持原样直接通过 `services::xxx` 供给 UI，不增设中转层。
- 播放备选歌曲 `firstCandidate`（约 937–938 行）：属于播放控制逻辑，调用方直接读取 `contentStore.state.value.shelvesFor(destination)` 即可。
- `browseDetailRequest` 与 `stationHistory`：分别属于导航状态与播放会话持久化。

---

### 2. 三处清空操作的原样搬移（412、442、1829 行）

原代码三处所做的事情不相同，分别原样剪切为三个函数：

1. **`refreshAll(current: Destination)`（对应 408–417 行 `refreshAllContent` 内容部分）**：
   - 调用 `operations.clearDetailPages()`
   - 调用 `resetLibraryPagination()`
   - `shelvesByDestination = emptyMap()`
   - `loadingDestinations = setOf(current)`
   - `messages.emit(null)`
   - `reloadGeneration++`
   - （UI 端同时调用 `lyricsStore.onCacheCleared()` 与 `accountStore.syncConnectionState()`）

2. **`onSignedIn()`（对应 438–447 行内容部分）**：
   - 调用 `resetLibraryPagination()`
   - `shelvesByDestination = emptyMap()`
   - `loadingDestinations = setOf(Destination.Home)`
   - `reloadGeneration++`
   - **不调用** `clearDetailPages()`

3. **`onSignedOut()`（对应 1815–1834 行内容部分）**：
   - `shelvesByDestination = emptyMap()`
   - `recentTracks = emptyList()`
   - `reloadGeneration++`
   - **不调用** `resetLibraryPagination()`，也**不清空**搜索结果

---

### 3. 对外接口定义

```kotlin
package dev.amatv.core

import dev.amatv.integration.LibraryPage
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.LibraryShelfPagination
import dev.amatv.model.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
    /** 重载纪元计数（驱动副作用重新加载） */
    val reloadGeneration: Int = 0,
    /** 资料库各货架下一页分页 URL */
    val libraryNextPages: Map<String, String> = emptyMap(),
    /** 资料库正在加载分页的货架集合 */
    val libraryLoadingShelves: Set<String> = emptySet(),
    /** 资料库分页加载失败的货架集合 */
    val libraryFailedShelves: Set<String> = emptySet(),
) {
    fun shelvesFor(destination: Destination): List<ContentShelf> = shelvesByDestination[destination].orEmpty()
    fun isLoading(destination: Destination): Boolean = destination in loadingDestinations

    /** 计算属性：组装 UI 所需的 shelfPagination 映射 */
    val shelfPagination: Map<String, LibraryShelfPagination>
        get() = (libraryNextPages.keys + libraryLoadingShelves + libraryFailedShelves)
            .associateWith { id ->
                LibraryShelfPagination(
                    hasMore = id in libraryNextPages,
                    loading = id in libraryLoadingShelves,
                    failed = id in libraryFailedShelves,
                )
            }
}

/** ContentStore 数据源操作接口 */
interface ContentOperations {
    val isCatalogConfigured: Boolean
    suspend fun savedShelves(name: String, languageTag: String): List<ContentShelf>?
    suspend fun saveShelves(name: String, languageTag: String, shelves: List<ContentShelf>)
    suspend fun savedPlaylistIndex(): List<Track>?

    suspend fun recommendationShelves(): List<ContentShelf>
    suspend fun recentlyPlayedTracks(): List<Track>
    suspend fun recentlyPlayed(): List<Track>
    suspend fun charts(types: String): List<Track>
    suspend fun liveRadio(): List<Track>
    suspend fun recentlyAdded(): List<Track>

    suspend fun libraryArtistsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryAlbumsPage(nextPage: String? = null): LibraryPage
    suspend fun libraryPlaylistsPage(nextPage: String? = null): LibraryPage
    suspend fun librarySongsPage(nextPage: String? = null): LibraryPage

    suspend fun search(query: String): List<Track>
    fun clearDetailPages(dropSnapshots: Boolean = false)
}

class ContentStore(
    private val operations: ContentOperations,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    val state: StateFlow<ContentState>

    /** 提示消息事件通道（null 表示清除提示） */
    val messages: SharedFlow<String?>

    /** 驱动页面内容加载（对应原大 LaunchedEffect） */
    fun loadDestination(
        destination: Destination,
        connectionState: MusicConnectionState,
        reloadGeneration: Int,
        contentLanguageTag: String,
    )

    /** 资料库货架加载更多（分页） */
    fun loadMoreLibraryShelf(shelfId: String, connectionState: MusicConnectionState)

    /** 重置资料库所有分页状态 */
    fun resetLibraryPagination()

    /** 搜索页预拉取：原条件为 destination == Search && searchResults.isEmpty() && isCatalogConfigured */
    fun ensureDefaultSearch()

    /** 执行关键词搜索：先取消上一次在途的 searchJob */
    fun search(query: String)

    /** 清空搜索结果 */
    fun clearSearch()

    /** 对应 408–417 行 refreshAllContent 的内容部分 */
    fun refreshAll(current: Destination)

    /** 对应 438–447 行登录成功后的内容部分 */
    fun onSignedIn()

    /** 对应 1815–1834 行退出登录后的内容部分 */
    fun onSignedOut()

    /** 设置页清除缓存时的回调（使快照标记失效） */
    fun onCacheCleared()
}
```

---

### 4. 原 LaunchedEffect Key 对照表

| 原 LaunchedEffect 驱动代码 | 原 Key 集合 | 搬移后驱动方式 | 说明 |
|---|---|---|---|
| 约 1304 行（页面内容加载） | `destination`<br>`connectionState`<br>`reloadNonce`<br>`contentLanguageTag` | `LaunchedEffect(destination, connectionState, contentState.reloadGeneration, contentLanguageTag) { contentStore.loadDestination(destination, connectionState, contentState.reloadGeneration, contentLanguageTag) }` | Key 集合与原代码完全等价，`reloadNonce` 改为 `contentState.reloadGeneration`。 |
| 约 1293 行（歌词加载） | `currentTrack`<br>`connectionState`<br>`isCatalogConfigured`<br>`reloadNonce`<br>`lyricsLanguageTag`<br>`lyricsVariant` | 原 Key 集合不变，仅将 `reloadNonce` 换为 `contentState.reloadGeneration` | 保持歌词随刷新自增同步重载。 |
| 约 1618 行（搜索页空结果预加载） | `destination` | `LaunchedEffect(destination) { if (destination == Destination.Search) contentStore.ensureDefaultSearch() }` | 保持原有触发与拉取热歌榜逻辑。 |

---

### 5. 字段对照表（ContentStore）

| 字段名 | 原状态位置 | 原读写方 | 改后读写方 |
|---|---|---|---|
| `shelvesByDestination` | `AtvApp.kt` 行 250 remember | `AtvApp.kt` (行 250, 388, 391, 412, 442, 937, 1309, 1338, 1344, 1562, 1604, 1793, 1829) | `contentStore.state.collectAsState().value.shelvesByDestination`，读通过 `shelvesFor(dest)`，写全在 Store 内部。 |
| `savedShelfDestinations` | `AtvApp.kt` 行 260 remember | `AtvApp.kt` (行 260, 1322, 1339, 1347, 1602, 1605) | `contentStore.state.value.savedShelfDestinations`，由 Store 内部管理。 |
| `loadingDestinations` | `AtvApp.kt` 行 261 remember | `AtvApp.kt` (行 261, 413, 443, 1310, 1324, 1328, 1610, 1799) | `contentStore.state.collectAsState().value.loadingDestinations`，UI 通过 `contentStore.state.value.isLoading(dest)` 读取。 |
| `recentTracks` | `AtvApp.kt` 行 275 remember | `AtvApp.kt` (行 275, 1383, 1583, 1792, 1830) | `contentStore.state.collectAsState().value.recentTracks`。 |
| `searchResults` | `AtvApp.kt` 行 266 remember | `AtvApp.kt` (行 266, 1619, 1622, 1790, 1842, 1848) | `contentStore.state.collectAsState().value.searchResults`。 |
| `searchLoading` | `AtvApp.kt` 行 273 remember | `AtvApp.kt` (行 273, 1620, 1624, 1791, 1838, 1852) | `contentStore.state.collectAsState().value.searchLoading`。 |
| `reloadGeneration` (`reloadNonce`) | `AtvApp.kt` 行 248 remember | `AtvApp.kt` (行 248, 416, 446, 1293, 1304, 1350, 1832) | `contentStore.state.collectAsState().value.reloadGeneration`。自增由 `refreshAll()`、`onSignedIn()`、`onSignedOut()` 执行。 |
| `shelfPagination` / `libraryNextPages` 等 | `AtvApp.kt` 行 267–272 remember | `AtvApp.kt` (行 267-272, 345-404, 1522, 1533, 1547, 1569, 1794) | `contentStore.state.collectAsState().value.shelfPagination`。 |

---

### 6. 测试计划（ContentStore）

测试文件：`app/src/test/java/dev/amatv/core/ContentStoreTest.kt`（纯 JVM，`FakeContentOperations`）。

1. **切页面不重复加载已有内容**：
   - 首次切入 `Destination.Home`，触发网络加载，`operations.recommendationShelvesCallCount == 1`；
   - 切入 `Destination.Browse` 后再切回 `Destination.Home`，不发起重复网络请求，计数仍为 1。
2. **资料库快照先显示、网络结果再覆盖**：
   - 存在本地快照时，`loadDestination(Destination.Library)` 启动瞬间，`savedShelfDestinations` 包含 Library，且 `shelvesFor(Library)` 立即显示快照；
   - 网络响应返回后，`shelvesFor(Library)` 被网络数据覆盖，`savedShelfDestinations` 不再包含 Library。
3. **快照在屏、网络全失败时保留快照，messages 发出 null**（按 1602–1615 行 `keepSaved` 逻辑）：
   - 本地快照已在屏幕展示，后续网络请求全部失败抛出异常；
   - 断言 `shelvesFor(target)` 依然保留本地快照内容，`messages` 发出 `null`（不覆盖快照且清空错误提示）。
4. **搜索快速连续输入只保留最后一次结果**：
   - 连续快速调用 `search("query1")` 和 `search("query2")`；
   - 前一次请求被 cancel，最终 `searchResults` 严格等于 `"query2"` 的返回结果，`searchLoading` 恢复为 false。
5. **onSignedOut 与 refreshAll 的差异化断言**：
   - **onSignedOut**：断言货架清空、`recentTracks` 清空，但资料库分页状态（`libraryNextPages` 等）**不**变，搜索结果**不**清空，`reloadGeneration` 自增 1；
   - **refreshAll**：断言货架清空、调用底层 `clearDetailPages()`、资料库分页状态**重置**（`resetLibraryPagination()`）、`loadingDestinations = setOf(current)`、`reloadGeneration` 自增 1。
6. **onSignedIn 断言**：
   - 断言资料库分页重置、货架清空、`loadingDestinations = setOf(Destination.Home)`，**不**调用 `clearDetailPages()`，`reloadGeneration` 自增 1。
7. **reloadGeneration 计数自增**：
   - 分别调用 `refreshAll`、`onSignedIn`、`onSignedOut`，每次各增加 1。
8. **ShelfLoads 相同 Key 重复调用去重**：
   - 同一组 key 参数（target, connectionState, reloadGeneration, contentLanguageTag）并发多次调用 `loadDestination`，底层网络方法仅调用一次。
9. **资料库分页防重复死循环**：
   - 分页返回已出现过的 `requestedPage` 时，标记该 shelf 失败，不陷入死循环。

---

## 三、旧界面接入改造示例

### 1. Application 持有单例

```kotlin
// AmAtvApplication.kt
class AmAtvApplication : Application() {
    val accountStore by lazy {
        AccountStore(operations = musicServices)
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

    var serviceMessage by remember { mutableStateOf<String?>(null) }

    // 收集来自 ContentStore 的消息提示
    LaunchedEffect(contentStore) {
        contentStore.messages.collect { serviceMessage = it }
    }

    // 页面内容加载：Key 保持原来四个
    LaunchedEffect(destination, accountState.connectionState, contentState.reloadGeneration, contentLanguageTag) {
        contentStore.loadDestination(
            destination = destination,
            connectionState = accountState.connectionState,
            reloadGeneration = contentState.reloadGeneration,
            contentLanguageTag = contentLanguageTag,
        )
    }

    // 歌词加载：reloadNonce 替换为 contentState.reloadGeneration
    LaunchedEffect(currentTrack, accountState.connectionState, services.isCatalogConfigured, contentState.reloadGeneration, lyricsLanguageTag, lyricsVariant) {
        // ...
    }

    // 搜索页预加载
    LaunchedEffect(destination) {
        if (destination == Destination.Search) {
            contentStore.ensureDefaultSearch()
        }
    }

    // 授权返回
    val webAuthenticationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            accountStore.completeSignIn(result.data!!)
                .onSuccess {
                    serviceMessage = null
                    contentStore.onSignedIn()
                    destination = Destination.Home
                }
                .onFailure { error ->
                    serviceMessage = error.message
                }
        }
    }

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
        setupMessage = serviceMessage ?: services.setupMessage,
        onRetry = {
            accountStore.syncConnectionState()
            contentStore.refreshAll(destination)
            lyricsStore.onCacheCleared()
        },
        onSignOut = {
            accountStore.signOut()
            contentStore.onSignedOut()
            lyricsStore.onCacheCleared()
            // 播放与队列清理保留在原处
            queueController.reset()
            playbackPrefs.clearSession()
            serviceMessage = AppText.text(R.string.app_apple_signed_out)
        },
        onSearch = contentStore::search,
        // 详情页 12 个方法保持原有 services::xxx 直接传入
        loadCollection = services::collectionTracks,
        cachedCollection = services::cachedCollectionTracks,
        savedCollection = services::savedCollectionTracks,
        collectionPartials = services::collectionPartials,
        loadArtistAlbums = services::libraryArtistAlbumsPage,
        cachedArtistAlbums = services::cachedArtistAlbums,
        savedArtistAlbums = services::savedArtistAlbums,
        loadArtistCatalog = services::catalogArtistPage,
        cachedArtistCatalog = services::cachedArtistPage,
        savedArtistCatalog = services::savedArtistPage,
        loadArtistViewMore = services::catalogArtistViewMore,
        loadArtistOf = services::catalogArtistOf,
    )
}
```

---

## 四、风险与不确定点分析

### 1. 核心风险

1. **并发与可重入的货架加载覆写风险 (`ShelfLoads` + `savedShelfDestinations` + `keepSaved`)**
   - **风险描述**：原代码在网络加载慢或快速切 tab（Home -> Browse -> Library -> Home）时，使用 `keepSaved` 机制保护屏幕上已展示的本地快照。若网络失败或返回空货架，现有逻辑通过 `if (keepSaved && (current?.isNotEmpty() == true || savedShelfDestinations.contains(target)))` 避免冲掉快照造成白屏/闪屏。
   - **应对策略**：将 `ShelfLoads` 原样迁入 `ContentStore`，完全保留原有请求去重机制和 `keepSaved` 覆写保护逻辑，单测专项验证「网络失败保留快照」路径。

2. **多处清空操作的时序与副作用差异**
   - **风险描述**：原代码在刷新（412 行）、登录（442 行）和退出（1829 行）时的清理范围存在细微差异（例如仅刷新调用 `clearDetailPages`，仅登录和刷新重置资料库分页，仅退出清空 `recentTracks`）。若合并成一个函数会导致不期望的状态重置。
   - **应对策略**：严格原样拆分为 `refreshAll()`、`onSignedIn()` 和 `onSignedOut()` 三个函数，单测分别校验各自的状态变动。

3. **资料库分页的独立并发与死循环熔断 (`libraryVisitedPages` + `libraryFailedShelves`)**
   - **风险描述**：资料库包含 4 个独立分页货架（Artists、Albums、Playlists、Songs），各自维护独立的分页 URL、已访问集合和失败状态。若 API 返回异常循环的 `next` 链接，必须精准熔断单一货架并标记 `failed`，不引发死循环且不影响其他货架。
   - **应对策略**：状态机完整内聚于 `ContentStore`，单测注入重复页面 URL 确保死循环熔断机制生效。

### 2. 不确定点
经审查后，此前的详情页转发、搜索空结果区分、Storefront 读取等不确定点已全部厘清消除：
- 详情页不加转发层，继续直接使用 `services::xxx`。
- 搜索预加载严格按照原代码条件 `destination == Search && searchResults.isEmpty() && isCatalogConfigured` 触发，不加防抖。
- `serviceMessage` 留在 `AtvApp`，Store 仅通过 `messages: SharedFlow<String?>` 派发提示。
- 目前架构无遗留不确定点。

---

## 五、建议的提交顺序

拆分为 3 个逻辑清晰、可独立编译、单测全过的提交：

1. **Commit 1: `docs: revise A2 design according to review feedback`**
   - 提交本修订版设计文档 `docs/folio/a2-design.md`。

2. **Commit 2: `core: implement AccountStore, unit tests, and wire authentication callers`**
   - 新增 `dev.amatv.core.AccountStore` 与 `AccountOperations`；
   - `MusicServices` 实现 `AccountOperations`；
   - `AmAtvApplication` 持有 `accountStore` 单例；
   - 编写 `AccountStoreTest`（纯 JVM 单测）；
   - 改接 `AtvApp.kt` 中的登录、登出、以及所有使用 `connectionState` 的调用点；
   - 全量单测通过。

3. **Commit 3: `core: implement ContentStore, unit tests, and wire browse/search callers`**
   - `LibraryShelfPagination` 移至 `dev.amatv.model.Models.kt`；
   - 新增 `dev.amatv.core.ContentStore` 与 `ContentOperations`（内含 `ShelfLoads` 与 `demoShelves`）；
   - `MusicServices` 实现 `ContentOperations`；
   - `AmAtvApplication` 持有 `contentStore` 单例；
   - 编写 `ContentStoreTest`（纯 JVM 单测，覆盖全部断言）；
   - 改接 `AtvApp.kt` 中的 `shelvesByDestination`、大 `LaunchedEffect`、搜索、资料库分页及消息收集；
   - 删除 `AtvApp.kt` 中的 `ShelfLoads` 及冗余 remember 状态；
   - 全量单测通过。
