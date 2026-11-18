# B2 Folio 详情页交付报告

工作分支为 `b2-detail`，起点是 `b1-base`（`2e9d503`）。详情页接在 `dev.amatv.flyme` 的 Folio 图层插槽上。

## 任务 1–7

1. **入口。** `FolioApp.openItem` 的专辑、歌单和歌手分支调用 `openDetail`。歌曲和电台继续走原来的播放入口。
2. **专辑与歌单。** 页头显示取色背景、封面、标题、副标题、类型、曲数、总时长和两个播放按钮。专辑署名在解析到目录歌手后可点击，页尾显示该歌手目录中的其他专辑。窄档把按钮排到封面下方，矮档和中档把按钮留在封面右侧。曲目列表铺满内容区，宽档两栏，超宽档三栏，其余档位一栏。按原曲序从每栏顶部向下排列，点歌按原始索引启动整张专辑或歌单队列。当前曲目以强调色和均衡器标记显示。
3. **歌手。** 窄档和中档从页头向下排。矮档、宽档和超宽档左侧是头像、姓名和播放按钮，右侧可滚动查看热门歌曲、专辑、相似歌手及 Apple Music 返回的其他分区。默认显示最多八首热门歌曲，可展开接口已返回的其余热门歌曲。页头播放和点歌使用同一份热门歌曲队列。专辑、歌单及相似歌手卡片可以继续入栈。
4. **返回。** 左上角圆形按钮和系统返回键共用出栈逻辑。详情层覆盖在根页面上，根 pager 和根列表保留在组合中。返回根页面时沿用当前页码和滚动位置。首层进入和退出使用与 H5 相同的 400 毫秒横向过渡。
5. **数据。** 专辑和歌单按内存缓存、磁盘快照、`collectionTracks` 的顺序读取，歌手对应使用 `cachedArtistPage`、`savedArtistPage`、`catalogArtistPage`。完整的内存数据可直接显示并跳过磁盘读取，是否发起刷新由现有服务的缓存时效决定。磁盘快照在刷新期间可见，出错时保留已显示的数据并提供重试。无数据时区分加载中、失败和空列表。歌手分区按接口下发的续页地址调用 `catalogArtistViewMore`，在原分区追加并去重。现有 `MusicServices` 足以承接 B2 的详情数据读取。
6. **大小窗切换。** 详情栈使用进程级 Compose 状态，大小窗重新创建组合时读取同一份栈。进程冷启动为空栈。详情页的局部滚动和展开状态随当前详情组合重建。
7. **迷你播放器。** 窄档、矮档和中档沿用详情层上方的底部胶囊。宽档和超宽档在详情顶栏复用 Folio 封面圆点。两者都调用底座的 `openPlayer`。实际播放页呈现由 B1b 的图层实现提供。

## 文件清单

| 文件 | 用途 |
| --- | --- |
| `app/src/main/java/dev/amatv/flyme/FolioApp.kt` | 入口分支、进程详情栈引用与详情图层实参 |
| `app/src/main/java/dev/amatv/flyme/FolioDetail.kt` | 图层、返回按钮、取色背景和播放器圆点 |
| `app/src/main/java/dev/amatv/flyme/FolioDetailCollection.kt` | 专辑与歌单页 |
| `app/src/main/java/dev/amatv/flyme/FolioDetailArtist.kt` | 歌手页、歌曲展开和分区续页 |
| `app/src/main/java/dev/amatv/flyme/FolioDetailData.kt` | 进程内详情栈与缓存加载状态 |
| `app/src/main/java/dev/amatv/flyme/FolioDetailLayout.kt` | 五档详情参数、页头及播放按钮 |
| `app/src/main/java/dev/amatv/flyme/FolioDetailLists.kt` | 歌曲列、卡片列表与状态提示 |
| `app/src/main/res/values*/strings_folio_detail.xml` | 默认、简体中文、繁体中文详情资源 |
| `app/src/test/java/dev/amatv/flyme/FolioDetailTest.kt` | 分档、列顺序、出栈和缓存加载单测 |
| `B2-REPORT.md` | 交付与验证记录 |

继续使用底座的歌曲行、卡片、分区标题、圆按钮和胶囊组件。详情背景调用 `ui/Artwork.kt` 的 `rememberArtworkPalette` 提取封面颜色。

## 与 H5 的差异及待验证项

- 当前 H5 源码将歌手页头和内容竖向排列，而横屏和超宽参考截图展示了不同版本的分栏。按 B2 明文要求对横屏矮档、宽档、超宽档统一实现左右两栏。手机沿用当前源码的圆头像和播放、随机播放按钮排法，参考截图中的横幅另属一个版本。
- 背景以封面提取色渐隐，H5 则在封面原图上做模糊和饱和度处理。Android 详情过渡会带着详情顶栏一同移动，H5 顶栏独立定位。
- 现有 `Track` 未提供发行年份，专辑信息行省略年份。歌手页的曲数和专辑数按接口已返回的热门曲目和专辑计数，可能少于完整目录。没有目录关联的资料库歌手会显示空状态。
- 专辑页的更多专辑取自同歌手的实际专辑分区。歌单页未加入 H5 的「你可能也喜欢」，原型此处取固定示例专辑，现有歌单详情接口只返回曲目。
- 歌手接口若成功返回部分数据，页面会保留内容并提示重试。现有服务也会短暂缓存这种响应，在其 30 秒缓存有效期内立即重试，可能再次得到同一份数据。
- 不确定车机、小窗、手机竖屏及横屏、带鱼屏上的最终截图差异，以及大小窗来回切换时的设备端表现。依任务安排，模拟器截图对比由 Claude 执行。B2 未启动模拟器。

## 验收

- 使用 B1A 指定的 JDK 17、Android SDK、`flyme.uiVerification=true` 和 `arm64-v8a` 参数运行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`。Gradle 返回 `BUILD SUCCESSFUL`。
- 从 JUnit XML 汇总得到 **530 项单测通过**，其中 B2 新增 **11 项**，原有 **519 项**。失败、错误、跳过均为 0。
- APK 生成于 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。

## 返工

- **宽档 e（车机）**：以 `car-artist.png` 为准，去掉左侧栏。圆头像在页头左侧，姓名、歌曲和专辑数量、播放和随机播放在右侧；页头下方是全宽滚动区，带封面的热门歌曲分两栏，随后是专辑、相似歌手等目录分区。
- **矮档 m（手机横屏）**：以 `landscape-artist.png` 为准，保留左侧头像、姓名、数量和按钮，右侧独立滚动，热门歌曲为一栏。
- **超宽档 u**：以 `uw-artist.png` 为准，左侧为歌手信息，中间为两栏热门歌曲和专辑等分区，右侧单独显示相似歌手的圆形卡片；其他分区不会被移到右栏。
- **窄档 c、中档 t**：用 H5 的 `dev=pp` 和 `dev=pt` 打开 `artist:a2`，实截 `b2-fix-shots/pp-artist.png` 和 `b2-fix-shots/pt-artist.png`。两档均为顶部横排页头；窄档按钮另起一行，热门歌曲一栏；中档按钮留在头像右侧，热门歌曲两栏。H5 原文件未修改。
- **姓名排版**：歌手页 c/m/t/e/u 分别使用 32/26/46/60/38 sp，全部单行，超出可用宽度以省略号截断。c/t/e 取当前 H5 的字号；m/u 按指定截图估算字号。专辑和歌单的字号与排版未改。
- **范围与差异**：改动仅涉及 `FolioDetailArtist.kt`、`FolioDetailLayout.kt`、`FolioDetailTest.kt`、本报告和两张参考截图。保留真实目录的分区、查看更多、播放队列和出栈逻辑，因此曲目数量、分区和展开按钮可能与 H5 示例不同。未操作 Android 模拟器，设备端的最终像素差异仍不确定。
- **验收**：五档布局、头像尺寸、字号和歌曲列数已有断言，新增一项覆盖仅超宽档将相似歌手移到右栏。按 B1A 参数运行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`，见 `BUILD SUCCESSFUL`。JUnit 汇总 **531 项通过**（其中 B2 为 12 项），失败、错误、跳过均为 0；已生成 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。
- **返工 2｜矮档 m 胶囊避让**：歌手页左栏原把底部留白放在滚动内容末尾，首屏仍可能被底部居中的胶囊挡住。现将同一份留白（导航栏底部安全区 + `28.dp + 84.dp`）移到左栏滚动视口外；左栏内容超高时仍可独立滚动，「播放」「随机播放」可滚到胶囊上方。右栏和其他档位不变。已检查 m 档专辑、歌单：封面、操作按钮和曲目共用一个带底部留白的滚动区，没有独立左栏，无需改动。按 B1A 参数重跑单测和打包，`BUILD SUCCESSFUL`，531 项通过（B2 为 12 项），失败、错误、跳过均为 0。按约定未操作模拟器，2400×1080@420 的实画面待复核。
- **返工 3｜矮档 m 详情页首屏**：歌手圆头像与专辑、歌单方封面改用 `104.dp`。当前 H5 的 `.t-m .player` 定义 `--cw2:104px`，详情页 `.colhead`（含 `.aside`）使用 `--bc:112px`，本次按任务指定的 104 取值。歌手左栏缩小了区块间距，去掉页头底部留白。该次验收为 `BUILD SUCCESSFUL`，531 项单测通过（B2 为 12 项）。Claude 随后在 2400×1080@420 模拟器上观察到两个按钮的下半截仍被左栏底边裁掉，原报告的首屏结论有误。

### 返工 4｜矮档 m 歌手左栏

- 头像取 104 dp。姓名按 H5 的 `--h2f` 和 `.colhead h2` 使用 32 sp、字重 300、1.05 倍行高，单行省略。信息沿用 H5 的 13.5 sp 字号，在 Android 上指定 18 sp 单行行高。播放与随机播放同排，按 `.pbtn` 各高 48 dp，圆角半径为 24 dp，横向间距 10 dp。
- H5 的详情内容区从状态栏下方 12 dp、44 dp 返回按钮及 10 dp 间距之后开始，`.colhead` 另有顶部 8 dp、底部 24 dp、6 dp 行距，按钮还带 10 dp 顶部外边距。竖排左栏需要把头像与其余项目累加，因此让头像从返回按钮下 4 dp 开始，项目之间各留 4 dp，列内首尾无额外留白。右栏沿用原来的起始位置。左栏视口底部保留实际导航栏安全区及 84 dp 胶囊空间，较短窗口或放大字号时仍可滚动。
- **高度核算。** 在 914×411 dp、默认字号下，左栏内容高 **215.6 dp**（头像 104 + 姓名 33.6 + 信息 18 + 按钮行 48 + 三处间距 12）。纯函数单测采用 H5 横屏设备预设的顶部安全区 24 dp、底部安全区 16 dp。左栏从 `24 + 12 + 44 + 4 = 84 dp` 开始，底部预留 `16 + 84 = 100 dp`，可用高度 **227 dp**，余量 **11.4 dp**。底部安全区按 24 dp 再算一次，可用 **219 dp**，余量 **3.4 dp**。
- 本次修改 `FolioDetail.kt`、`FolioDetailArtist.kt`、`FolioDetailLayout.kt`、`FolioDetailTest.kt` 和 `FolioApp.kt` 的详情层实参。按 B1A 参数运行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`，结果为 `BUILD SUCCESSFUL`，**532 项单测通过**（含 B2 的 13 项），失败、错误、跳过均为 0。APK 位于 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。模拟器实画面待 Claude 复核。

### 返工 5｜横屏与超宽歌手页恢复顶部页头

- **歌手页。** 矮档 m、宽档 e 和超宽 u 共用顶部横排页头：左侧圆头像，右侧姓名、歌曲与专辑数量、播放和随机播放；热门歌曲、专辑和相似歌手都在下方同一个全宽滚动区。按 H5 的 `--bc` / `--h2f`，m/e/u 分别使用 112 dp / 32 sp、220 dp / 60 sp、170 dp / 52 sp。删除 m 的独立左栏、u 的相似歌手侧栏及专用尺寸、留白逻辑。窄档 c 的按钮仍另起一行，中档 t 的按钮仍在头像右侧。
- **热门歌曲。** 曲目带封面，c 一栏、t/e/u 两栏。重拍的 `landscape-artist.png` 和当前 H5 的 `.top2` 在 m 档实际只有一栏；这里以本轮明确要求为准，m 也使用两栏。播放顺序、展开和分区续页保持不变。
- **专辑与歌单。** 对照 `car-album.png`、`car-playlist.png`、`phone-album.png`：保留车机两栏曲目和手机单栏曲目，手机页头按钮与封面的间距调至 H5 的 22 dp；专辑、歌单的 m 档封面也统一到 H5 的 112 dp。歌单页尾的「你可能也喜欢」使用首页已加载的 Apple Music 推荐，最多显示三张不同专辑，无推荐专辑时不显示。不新增请求，不使用 H5 示例数据。专辑仍不显示发行年份，因为现有数据模型未提供该字段。
- **验收。** 删除旧的 m 左栏高度和 u 侧栏单测，新增顶部页头按钮位置及热门歌曲列数的分档回归测试、歌单推荐选取测试。按 B1A 参数运行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`，得到 `BUILD SUCCESSFUL`；532 项通过（B2 为 13 项），失败、错误、跳过均为 0。APK：`app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`。未运行模拟器，Android 实画面仍待比对。
