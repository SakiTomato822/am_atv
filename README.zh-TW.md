# AM for Flyme Auto

**車機上的 Apple Music：為 Flyme Auto（領克 06 等）適配的非官方客戶端**

基於 [AM for TV](https://github.com/Sam20060720/am_atv) 的 `flyme-auto` 分支，把 Apple TV 風格的 Apple Music 體驗搬到車機觸控螢幕上。

[English](README.md) · 繁體中文 · [简体中文](README.zh-CN.md)

> 非官方專案，與 Apple、吉利、領克、ECARX、魅族無關。需要 Apple Music 訂閱。實車行為以車主自測為準。

## 下載

到 [Releases](https://github.com/SakiTomato822/am_atv/releases) 下載最新的 **Flyme Auto 測試版**，檔案為 `AMATV-FlymeAuto-Gecko-arm64.apk`。

- ARM64，Android 9+；套件名稱 `dev.amatv.flyme`。
- 所有測試版使用同一簽名，可直接覆蓋安裝並保留登入與設定。
- 首次開啟後用 Apple ID 登入（內建 GeckoView 網頁登入）。

## 功能

**播放**
- Apple TV／Cider 風格播放頁：左側封面與控制，右側逐字歌詞；暫停時封面縮小。
- 播放頁從膠囊播放器展開、收回時縮回膠囊；在封面上往上滑即可關閉。
- 歌名、歌手與專輯過長時以跑馬燈捲動；露骨內容歌曲標示 (E)。
- 點歌手或專輯名稱可直接打開該頁。
- 點星星可加入「喜好歌曲」。Apple 只允許自家 App 取消喜好，因此已是喜好的歌曲再點星星，會提示到 Apple Music 中取消。
- 無縫播放：下一首會提前在原生播放器中排好。
- 若因 Apple ID 內容限制（露骨內容）無法播放，會直接說明原因，而不是顯示一般錯誤。

**歌詞**
- 逐字歌詞（和聲、長音、間奏、時間偏移），歌詞開關會被記住。
- 可用手指上下拖動查看歌詞，放開後回到正在唱的那一行；點有時間軸的歌詞行可跳到該處。
- 翻譯語言為中文時，中文歌詞不再多出一行簡繁互轉的「翻譯」；日文、韓文、英文照常翻譯。

**佇列與電台**
- 播放佇列由左到右：已播放、正在播放、接下來。
- 電台：個人電台、歌曲電台與 Apple Music 1 等直播，由內建 GeckoView 中的 MusicKit JS 播放；直播使用獨立頁面。
- 電台歷史：保留最近 50 首電台歌曲（重啟後仍在），顯示在播放佇列中，點一下即可播放。
- 電台在換歌時遇到解碼錯誤會自動恢復，並能偵測 Apple 改為播放 30 秒試聽的情況。

**瀏覽**
- 頂部置中導覽，右上角膠囊播放器在每一頁都會顯示（含專輯頁、歌手頁）；首頁背景取自正在播放的封面色彩。
- 首頁顯示「為你精選」與 Apple Music 推薦。
- 搜尋：結果按歌曲、歌手、專輯、播放列表分組；以搜尋紀錄取代固定建議；最近播放為可上下翻頁的直向列表；橫屏時版面會避開螢幕鍵盤。
- 資料庫一頁呈現：播放列表／歌手／專輯／歌曲，網格或列表。專輯與歌曲預設按加入時間排序（最新在前）；每種排序再點一次切換正序／倒序。
- 歌手頁對齊 Apple Music 網頁版：熱門歌曲（最多 30 首）、最新發行、專輯、單曲與 EP、歌手歌單、現場專輯、合輯、參與作品、相似歌手。
- 資料庫專輯可「顯示完整專輯」，專輯頁可前往歌手頁。

**車機**
- 以 1920×968 為設計尺寸縮放，保留狀態列與底欄；觸控優先，按鍵導覽仍可用。各頁返回鍵樣式一致。
- 發布標準 Android MediaSession（方向盤按鍵、系統媒體卡片）。
- 嘗試註冊為 Flyme MediaCenter 線上音樂來源（僅限已核對的版本，實車尚未確認出現）。
- 向 EVCC 輸出目前歌詞（主歌詞）。

## 權限

不需要特殊權限，只使用網路。早期版本曾為頻譜顯示請求麥克風權限；Flyme.9 起已移除頻譜與該權限。

## 已知限制

- 喜好歌曲只能加入，無法在本應用中取消（Apple Music 公開 API 不允許第三方應用取消喜好）。
- 無法顯示 Apple Music 個人頭像，公開 API 不提供。
- Apple Music API 不提供資料庫排序參數：選擇非預設排序時會在背景載入全部項目，資料庫很大時需要等一下。
- 原生 MediaCenter 媒體來源僅對已核對的版本啟用，其他版本只使用標準 MediaSession。
- 尚未適配直向（豎屏）版面。

## 建置

需要 JDK 17、Android SDK 36，以及自行從 Apple 下載的官方 [MusicKit Android SDK](https://developer.apple.com/musickit/android/)（不可再散布，儲存庫中不包含）。設定見 [MusicKit 設定](docs/musickit-setup.md)。

```sh
./gradlew :app:assembleFlymeGecko
```

### GitHub Actions 發布

工作流程 [`flyme-auto.yml`](.github/workflows/flyme-auto.yml) 手動觸發，建置、測試、驗證簽名後發布預發布版，標題使用 `app/build.gradle.kts` 中的 Flyme 版本號。需要的儲存庫 Secrets：

| Secret | 內容 |
| --- | --- |
| `MUSICKIT_SDK_TOKEN` | 唯讀私有儲存庫 `am_atv-sdk` 的 fine-grained token（Contents: Read-only）；SDK ZIP 存放於該儲存庫的 release |
| `AM_DEVELOPER_TOKEN` | Apple Music 開發者 Token |
| `FLYME_SIGNING_KEYSTORE_BASE64` | 固定簽名金鑰（Base64） |

## 文件

- [Flyme Auto 適配說明](docs/flyme-auto.md)：螢幕、互動與媒體來源協定
- [適配進度](docs/flyme-auto-progress.md) · [迭代計畫](docs/flyme-roadmap.md)
- [無縫播放](docs/flyme-gapless.md) · [電台播放狀態](docs/flyme-radio-playback-status.md) · [EVCC 歌詞](docs/evcc-lyrics.md) · [字型](docs/flyme-fonts.md)

## 授權

[MIT](LICENSE) · [第三方授權](app/src/main/assets/licenses/NOTICE.txt)。上游專案：[AM for TV](https://github.com/Sam20060720/am_atv)。
