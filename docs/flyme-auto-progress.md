# Flyme Auto 当前进度

更新：2026-11-02（Gecko 电台引擎已在 ARM64 模拟器实测）。

## 完成与诊断

- Flyme.4 已完成车机布局、触控、歌词层级和资料库分类；EVCC 通过 MediaSession `lyrics` 输出原文主歌词，并修正中英文空格。EVCC 真车联动仍待验收。
- 原生 MediaCenter Binder 桥接按已核对版本注册在线源类型 6，并保留 MediaSession 回退；实车曾反馈 AM 源未出现，需查车端配置/日志。
- 原生 MusicKit 普通歌曲曾在 ARM64 模拟器播放；个人电台与 Apple Music 1 在队列层失败。SDK 1.1.1 provider 缺 station URL/响应映射；官方 1.1.2 AAR 与本地哈希相同，升级不能证明修复。详见 [radio 诊断](flyme-radio-playback-status.md)。

## Gecko 电台引擎

电台（`CatalogKind.Station`）改由 `GeckoStationEngine` 播放：无界面的 GeckoSession 加载本地 loopback 页面，用 MusicKit JS v3 `setQueue({station})`；普通歌曲仍走原生 SDK，`MusicServices` 保证同一时间只有一个引擎发声。由 `flyme.geckoStations`（默认 `true`）控制。

- 授权：MusicKit JS 在 `configure()` 时读取 `localStorage["music.<teamId>.media-user-token"]`。页面在 configure 前写入 App 现有的 Music User Token，configure 后删除，车上无需再次网页登录。旧 probe 把 token 作为 `musicUserToken` 配置项传入，该项不影响授权，所以当时是 `authorized=false`。
- 直播：GeckoView 在 Android 上用内置 ExoPlayer 原生播放 HLS，没有 EME。MusicKit 检测到原生 HLS 后会让直播走 `<audio src=m3u8>` 并卡在 `none`。页面隐藏 `canPlayType` 的 HLS 支持，强制走与桌面相同的 MSE + Widevine 路径。
- 音频焦点由引擎自行申请（GeckoView 不处理）；直播无 seek、无上下首；歌曲电台内每首歌带 catalog id，沿用现有歌词流程。
- 日志只记录阶段名、状态和 MusicKit 错误原因，不记录 token、电台 ID 或请求 URL。

实测（emulator-5582，ARM64 预览包）：个人电台出声、歌名/封面/歌词随曲切换、下一首正常；Apple Music 1 依次 loading → seeking → playing，MediaSession 为 `PLAYING`。`testFlymeGeckoUnitTest` 414 项全部通过。真车未测。

## 构建注意

本地构建必须传 `-PAM_DEVELOPER_TOKEN_FILE=<绝对路径>`（相对路径按 `app/` 解析），否则包内无开发者 token，首页与搜索全部失败并显示“请检查网络”。预览包另加 `-Pflyme.uiVerification=true -Pflyme.previewAbi=arm64-v8a`；正式包需 `FLYME_SIGNING_KEYSTORE_FILE`。

## 本地状态与待办

正式 `dev.amatv.flyme` 为 versionCode 18，Flyme.3 起 CI 固定签名并校验 SHA-256 `91f7b9…78bab8d`。含开发者 token 的预览 APK 不可外发。`GeckoRadioProbeActivity` 仅在预览包启用，可在确认不再需要后移除。

Flyme voice research 与 full-chain Debug 已暂停，勿触碰。仍待：真车电台与 EVCC 歌词、Flyme 实车媒体源/方向盘、地区/订阅差异；`0001-cjk-spacing-motion.patch`（CJK 空格与 M3 动画）尚未应用。[Flyme 说明](flyme-auto.md) 与 [EVCC 说明](evcc-lyrics.md)记录历史验收。
