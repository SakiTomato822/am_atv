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
