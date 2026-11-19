# M1 浏览页和 App 级动画验收报告

工作区为 `am_atv_b1_gpt`。分支 `m1-browse` 从 `c3-merge` 的 Alpha 6 提交 `3cf0860` 创建。

## 实现

1. **详情页封面与滚动。** Compose BOM 为 2026.06.01，项目解析到的 Compose Animation 为 1.11.4。首页、最新发行、资料库、搜索及详情页推荐区的专辑、歌单和歌手卡片记录被点封面的实例和位置。详情层用 `SharedTransitionLayout` 与 `sharedBounds` 在该位置和详情大封面之间过渡，其余详情内容淡入淡出。叠开的详情保留上一层列表的滚动位置。详情标题区滚动时，封面按滚动距离缩至 0.90，透明度最低 0.60。来源封面已滚出视口、页签已切走、详情大封面已滚出视口或入口未提供封面时，详情页使用侧滑。
2. **播放页进出。** 窄屏胶囊和宽屏圆钮使用各自封面的中心作为播放页容器缩放支点。打开时从 0.76 放大到 1 并淡入，关闭时反向。App 层接管进出动效，继续使用播放页现有的窄屏下拉关闭手势及关闭态歌词预热。播放页内部封面位置未接入过渡，整页使用容器动效。媒体事件等无可用入口的位置使用淡入淡出。
3. **迷你播放器玻璃。** Android 12+ 用 Backdrop 1.0.6 从浏览内容层采样并做 16 dp 模糊。迷你播放器、播放页和账号浮层位于采样层之上。Android 11 及更早版本和低性能模式使用高遮蔽度玻璃，深色不透明度为 0.82，浅色为 0.86，带 1 dp 描边与顶部渐隐高光。胶囊封面和文字共用整颗胶囊的点击区域，右侧播放键有独立的按下反馈。
4. **卡片、行与页签。** 浏览和详情使用的公共卡片、列表行、歌曲行去掉了水波纹，以按下缩放和亮度变化提供反馈。卡片及迷你播放器按下缩到 0.97，列表行缩到 0.985，最亮时叠加 10% 白色。点顶部标题时，出入页面在最多 12 dp 的水平位移中交叉淡化，标题继续使用原有的高亮插值，手指横滑继续由 Pager 处理。
5. **设置文案。** 简体中文、繁体中文和英文的淡化开关只显示类别名，由开关控件显示状态。

## 动效与退化

| 场景 | 时长 | 缓动和退化 |
| --- | --- | --- |
| 详情封面、内容淡入淡出、侧滑 | 300 ms | `FolioMotion.PageEasing`。低性能模式只做淡入淡出 |
| 播放页容器 | 320 ms | 同一缓动。低性能模式或无入口坐标时只做淡入淡出 |
| 点顶部标题切换内容 | 180 ms | 同一缓动。标题沿用既有位置与高亮计算 |
| 按下与释放 | 180 ms | 同一缓动。低性能模式只改变亮度 |
| 详情滚动视差 | 随滚动距离变化 | 位移由列表自身滚动，缩放及透明度随进度线性变化 |

`FolioMotion.PageEasing` 为 `CubicBezierEasing(0.2, 0.8, 0.2, 1)`。系统关闭动画时直接切换页面，按下亮度即时变化，详情封面视差停用。

## 文件

| 文件 | 变更 |
| --- | --- |
| `FolioApp.kt` | 接入详情和播放页的导航过渡、标题切页与玻璃采样层 |
| `FolioBrowseArtwork.kt`、`FolioDetail.kt`、`FolioDetailData.kt` | 定位点击封面、管理详情栈和共享边界 |
| `FolioDetailArtist.kt`、`FolioDetailCollection.kt`、`FolioDetailLayout.kt` | 传递滚动状态、应用封面视差以及详情内的按下反馈 |
| `FolioBrowsePlayerEntrance.kt`、`FolioBrowseGlass.kt`、`FolioComponents.kt` | 播放页容器动效、迷你播放器玻璃和公共浏览组件 |
| `FolioBrowseMotion.kt`、`FolioBrowsePress.kt` | 系统动画状态、低性能动效策略和无水波纹按下效果 |
| `FolioBrowseTabTransition.kt`、`FolioPivot.kt` | 标题触发的内容过渡与标题按下反馈 |
| `FolioSettingsPage.kt`、`values*/strings_diagnostics.xml` | 淡化开关及其三语标签 |

## 单测和打包

- 按 `B1A-TASK.md` 第 4 节的 Windows 等价命令，带指定的 token 路径、`flyme.uiVerification=true` 和 `flyme.previewAbi=arm64-v8a`，运行 `:app:testFlymeGeckoUnitTest :app:assembleFlymeGecko`。最终输出 `BUILD SUCCESSFUL in 13s`。Kotlin daemon 临时目录写入被拒后，Gradle 自动改用无 daemon 编译。
- JUnit XML 包含 72 个套件、468 项测试，失败、错误和跳过均为 0。B1A 文档中的 506 项是旧阶段基线，Alpha 6 前的 `795fc8c` 已移除旧 UI 测试，本轮没有更改 `app/src/test` 或测试构建配置。
- APK 为 `app/build/outputs/apk/flymeGecko/app-flymeGecko.apk`，归档内原生库的 ABI 为 `arm64-v8a`。本地构建日志为 `build/m1-browse-verification.log`。
- 不确定项为实机上的转场观感、Android 12+ 玻璃取样和窄屏下拉关闭的交互表现。本轮验收为编译、单测、打包和代码路径检查，未运行模拟器或实机。

实现提交为 `8be1b17`（三语文案）、`9b33232`（按下反馈）、`eb8aef5`（浏览与播放页动效和玻璃）。分支未推送。
