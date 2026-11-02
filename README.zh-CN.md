# AM for Flyme Auto

**车机上的 Apple Music：为 Flyme Auto（领克 06 等）适配的非官方客户端**

基于 [AM for TV](https://github.com/Sam20060720/am_atv) 的 `flyme-auto` 分支，把 Apple TV 风格的 Apple Music 体验搬到车机触屏上。

[English](README.md) · [繁體中文](README.zh-TW.md) · 简体中文

> 非官方项目，与 Apple、吉利、领克、ECARX、魅族无关。需要 Apple Music 订阅。实车行为以车主自测为准。

## 下载

到 [Releases](https://github.com/SakiTomato822/am_atv/releases) 下载最新的 **Flyme Auto 测试版**，文件为 `AMATV-FlymeAuto-Gecko-arm64.apk`。

- ARM64，Android 9+；包名 `dev.amatv.flyme`。
- 所有测试版使用同一签名，可直接覆盖安装并保留登录与设置。
- 首次打开后用 Apple ID 登录（内置 GeckoView 网页登录）。

## 功能

**播放**
- Apple TV / Cider 风格播放页：左侧封面与控制，右侧逐字歌词；暂停时封面缩小。
- 逐字歌词（和声、长音、间奏、时间偏移），歌词开关会被记住。
- 播放队列从左到右：正在播放 → 接下来，向左滑看已播放歌曲。
- 电台：个人电台、歌曲电台与 Apple Music 1 等直播，由内置 GeckoView 中的 MusicKit JS 播放；直播使用独立页面。

**浏览**
- 顶部居中导航，右上角常驻迷你播放器；首页背景取自正在播放的封面色彩。
- 资料库一页呈现：播放列表／艺人／专辑／歌曲，网格或列表。
- 专辑与歌曲默认按加入时间排序（最新在前）；每种排序再点一次切换正序／倒序。
- 艺人页：资料库中的专辑，加上 Apple Music 的热门歌曲、专辑、单曲与 EP。
- 资料库专辑可「显示完整专辑」。

**车机**
- 以 1920×968 为设计尺寸缩放，保留状态栏与底栏；触屏优先，按键导航仍可用。
- 发布标准 Android MediaSession（方向盘按键、系统媒体卡片）。
- 尝试注册为 Flyme MediaCenter 在线音乐源（仅限已核对的版本，实车尚未确认出现）。
- 向 EVCC 输出当前歌词（主歌词）。

## 权限

| 权限 | 用途 |
| --- | --- |
| 录音（`RECORD_AUDIO`） | 第一次播放时询问。只用来分析**本应用正在播放的声音**以显示频谱，不使用麦克风。拒绝也不影响播放，队列频谱会改为装饰性律动。 |

## 已知限制

- 电台由 GeckoView 播放，无法读取频谱，固定显示装饰性律动。
- Apple Music API 不提供资料库排序参数：选择非默认排序时会在后台载入全部项目，资料库很大时需要等一会儿。
- 原生 MediaCenter 媒体源仅对已核对的版本启用，其他版本只使用标准 MediaSession。

## 构建

需要 JDK 17、Android SDK 36，以及自行从 Apple 下载的官方 [MusicKit Android SDK](https://developer.apple.com/musickit/android/)（不可再分发，仓库中不包含）。设置见 [MusicKit 设置](docs/musickit-setup.md)。

```sh
./gradlew :app:assembleFlymeGecko
```

### GitHub Actions 发布

工作流 [`flyme-auto.yml`](.github/workflows/flyme-auto.yml) 手动触发，构建、测试、校验签名后发布预发布版，标题使用 `app/build.gradle.kts` 中的 Flyme 版本号。需要的仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `MUSICKIT_SDK_TOKEN` | 只读私有仓库 `am_atv-sdk` 的 fine-grained token（Contents: Read-only）；SDK ZIP 存放于该仓库的 release |
| `AM_DEVELOPER_TOKEN` | Apple Music 开发者 Token |
| `FLYME_SIGNING_KEYSTORE_BASE64` | 固定签名密钥（Base64） |

## 文档

- [Flyme Auto 适配说明](docs/flyme-auto.md)：屏幕、交互与媒体源协议
- [适配进度](docs/flyme-auto-progress.md) · [迭代计划](docs/flyme-roadmap.md)
- [电台播放状态](docs/flyme-radio-playback-status.md) · [EVCC 歌词](docs/evcc-lyrics.md) · [字体](docs/flyme-fonts.md)

## 许可

[MIT](LICENSE) · [第三方许可](app/src/main/assets/licenses/NOTICE.txt)。上游项目：[AM for TV](https://github.com/Sam20060720/am_atv)。
