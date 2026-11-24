# Flyme Auto 车机语音语义表

来源为本地 `apk_out/GeelyXSFMediaCenter_jadx` 的 `vr/semantics/SemanticParser.java`、`SemanticPriority.java`、`vr/channel/VrPluginChannelImpl.java` 和 `media/MediaCtrlByVrCallback.java`。用同一版固件 APK 的 JADX 指令视图复核默认反编译省略的 `handleSemanticResult` 和 `packSemantic`。App 能力以 `FlymeVoiceProtocol.declaration`、`parseSemantic` 和 `MusicServices.startFlymeVoice` 为准。例句由 intent 与槽位推测，识别器实际分配的 intent 可能随说法、媒体源或车机版本变化。

`subFeature`、`mediaCtrl`、`details_intent`、`mediaType`、`change`、`direction` 分别表示 `RECOG_SLOT_SubFeature`、`RECOG_SLOT_Ent_MediaCtrl`、`RECOG_SLOT_Details_Intent`、`RECOG_SLOT_Ent_MediaType`、`RECOG_SLOT_Ent_Change`、`RECOG_SLOT_Main_SkipDirection`。点播的名称最终通过回调 JSON 的 `titleName`、`artistName`、`albumName` 传入。

## 分发依据

- `parserSemantics` 以 `code >> 25` 分组，组内用按位或合并 App 上报的 `semanticsTypes`。`isSupportSemantic` 和 `isSupportForSemantic` 要求 `(mask & code) == code`。点播歌曲和「只搜索歌曲」各占一位。
- `calculatePoint` 先找同目标或未点名时的、支持该语义的焦点 App。其余候选包含在线注册及 LRU 里的历史能力。未点名时，焦点、前台、默认 App、最近使用、声明能力和语义级别共同计分，超过门槛才参与排序。显式点名分支可将该包列为候选。常规仲裁随后会重查渠道化 App 的掩码。表中的「能力」表示该 App 要收到常规语义回调需声明。
- `findSemanticInDatabase` 对点名的包查能力，缺少就回报不支持。未点名时先查默认 App，再找数据库里第一个支持的包，无命中就回报不支持。分发通常调用 `onAction(2, "vrSemanticDataListener", …)`。没有活动回调时可走恢复 Intent 或广播。蓝牙、USB、AM/FM 等部分语义还有车机内置媒体源切换分支。
- 未点名的通用控制和媒体信息查询直接看焦点源。若焦点 App 为渠道化媒体源（按协议注册的媒体 App），其掩码缺失时会直接回报该 App 不支持。焦点源未渠道化时走 `transformSemantic`，表中的「车机控制」列出该分支能执行的标准控制。无焦点时回报无焦点。
- App 列只统计语义回调里的声明和处理。改由车机发标准媒体控制时，是否生效还取决于媒体会话的能力。

## 音乐及通用控制

| 十进制 | 十六进制 | intent 与槽位条件 | 推测说法 | 类别 | App 声明 | App 处理 | 未声明时 |
|---:|---|---|---|---|---|---|---|
| 33554433 | `0x02000001` | `INTENT_Ent_Media_Resume`；`INTENT_Ent_Radio_ResumeRadio` 同码；`INTENT_Ent_Media_Play`，`subFeature=Play_Music`、`details_intent=StrongPlay` 且焦点源类型不在 3/9/10/11/12 | 播放；恢复播放 | 音乐／通用 | 是 | 播放 | 车机控制 Play |
| 33554434 | `0x02000002` | `INTENT_Ent_Media_Pause`；`INTENT_Ent_Radio_PauseRadio` 同码 | 暂停音乐 | 音乐／通用 | 是 | 暂停 | 车机控制 Pause |
| 33554436 | `0x02000004` | `INTENT_Ent_Media_SkipTrack` 或 `INTENT_Ent_Radio_SkipStation`，`direction=SpeechSkipDirection_Next` | 下一首 | 音乐／通用 | 是 | 下一首 | 车机控制 Next |
| 33554440 | `0x02000008` | 同上，`direction=SpeechSkipDirection_Previous` | 上一首 | 音乐／通用 | 是 | 上一首 | 车机控制 Previous |
| 33554448 | `0x02000010` | `INTENT_Ent_Media_Save_Favorite`；`INTENT_Ent_Radio_Save_Favorite` 同码 | 收藏这首歌 | 音乐／通用 | 是 | 收藏 | 能力 |
| 33554464 | `0x02000020` | `INTENT_Ent_Media_Remove_Favorite`；`INTENT_Ent_Radio_Remove_Favorite` 同码 | 取消收藏这首歌 | 音乐／通用 | 否 | 否 | 能力 |
| 33554688 | `0x02000100` | `INTENT_Ent_Media_DownloadMusic` | 下载这首歌 | 音乐 | 否 | 否 | 能力 |
| 33555456 | `0x02000400` | `INTENT_Ent_Media_SingleRepeat`、`INTENT_Ent_Media_AllRepeat`、`INTENT_Ent_Media_ShuffleCurrentSelection_On` 共用一码，回调 `modeType` 分别为 1、0、2 | 单曲循环；列表循环；随机播放 | 音乐 | 否 | 否 | 能力；焦点 App 缺少声明时回报不支持 |
| 33556480 | `0x02000800` | `INTENT_Ent_Media_Stop`，`subFeature=Stop_Music_General` | 停止音乐 | 音乐／通用 | 否 | 否 | 车机控制 Stop |
| 33558528 | `0x02001000` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Start` | 开始播放 | 音乐／通用 | 否 | 否 | 车机控制 Play |
| 33562624 | `0x02002000` | `INTENT_Ent_Media_Continue`，`subFeature` 为空或未命中特定媒体源 | 继续播放 | 音乐／通用 | 否 | 否 | 车机控制 Play |
| 33619968 | `0x02010000` | `INTENT_Ent_Media_PlayAgain` | 重播这一首 | 音乐／通用 | 否 | 否 | 能力 |
| 33685504 | `0x02020000` | `INTENT_Ent_Media_Rewind`；解析语义码时未区分秒数，`RECOG_SLOT_TIME` 打包为 `ctrl_progress` | 快退；往回十秒 | 音乐／通用 | 否 | 否 | 车机控制 Rewind |
| 33816576 | `0x02040000` | `INTENT_Ent_Media_FastForward`；解析语义码时未区分秒数，`RECOG_SLOT_TIME` 打包为 `ctrl_progress` | 快进；快进十秒 | 音乐／通用 | 否 | 否 | 车机控制 FastForward |
| 34078720 | `0x02080000` | `INTENT_APP_Open` | 打开音乐 App | 系统 | 否 | 否 | 目标取 `RECOG_SLOT_Apps_PackageName`；点名时进入仲裁 |
| 34603008 | `0x02100000` | `INTENT_System_SpecifiedPage_Open`，`RECOG_SLOT_Page_Type=play` | 打开播放页 | 系统 | 否 | 否 | 能力 |
| 35651584 | `0x02200000` | `INTENT_System_SpecifiedPage_Open`，`RECOG_SLOT_Page_Type=lyric` | 打开歌词 | 系统 | 否 | 否 | 能力 |
| 37748736 | `0x02400000` | `INTENT_Ent_Media_Seek`，`RECOG_SLOT_TIME` 打包为 `ctrl_progress` | 跳到一分三十秒 | 音乐／通用 | 否 | 否 | 能力 |
| 41943040 | `0x02800000` | `INTENT_System_OnPageControl`，`RECOG_SLOT_PageName` 非空、`RECOG_SLOT_Action=OPEN` | 打开收藏页 | 系统 | 否 | 否 | 系统页面语义；未点名且 `position > 0` 时可回退给 QQ 音乐 |
| 50331648 | `0x03000000` | 同上，`RECOG_SLOT_Action=CLOSE` | 关闭当前指定页面 | 系统 | 否 | 否 | 系统页面语义；未点名且 `position > 0` 时可回退给 QQ 音乐 |
| 67108865 | `0x04000001` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music`；普通播放，或 `StrongPlay` 时没有焦点／焦点源在 3/9/10/11/12 | 放点音乐 | 音乐 | 否 | 否 | 能力 |
| 67108866 | `0x04000002` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Name`，`mediaCtrl` 非 `search` | 播放《晴天》 | 音乐 | 是 | 播放匹配歌曲 | 能力 |
| 67108868 | `0x04000004` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Singer`，`mediaCtrl` 非 `search` | 播放张惠妹的歌 | 音乐 | 是 | 播放艺人热门歌曲队列 | 能力 |
| 67108872 | `0x04000008` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Singer_Name`，`mediaCtrl` 非 `search` | 播放周杰伦的《晴天》 | 音乐 | 是 | 播放匹配歌曲 | 能力 |
| 67108880 | `0x04000010` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Album` | 播放专辑《叶惠美》 | 音乐 | 是 | 整张专辑 | 能力 |
| 67108896 | `0x04000020` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Singer_Album` | 播放周杰伦的专辑《叶惠美》 | 音乐 | 是 | 匹配艺人后播放整张专辑 | 能力 |
| 67108928 | `0x04000040` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Category` | 播放爵士；来点轻松的歌 | 音乐 | 否 | 否 | 能力；`RECOG_SLOT_Ent_Style` 打包为 `search_slot_style`；心情说法需实车核对 |
| 67108992 | `0x04000080` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Range_Sort` | 播放本周最热门的歌 | 音乐 | 否 | 否 | 能力 |
| 67109120 | `0x04000100` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_By_Query_Favourite` | 播放收藏的歌；播放我喜欢的歌 | 音乐 | 否 | 否 | 能力 |
| 67109376 | `0x04000200` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_By_Query_Local` | 播放本地曲库里的歌 | 音乐 | 否 | 否 | 能力 |
| 67109888 | `0x04000400` | `INTENT_Ent_Media_Play`，`subFeature=Change_Music_Query`、`change=singer` | 换成这位歌手的歌 | 音乐 | 否 | 否 | 能力 |
| 67110912 | `0x04000800` | 同上，`change=singer_song` | 换成某歌手唱的这首 | 音乐 | 否 | 否 | 能力 |
| 67112960 | `0x04001000` | 同上，`change=version` | 换一个版本 | 音乐 | 否 | 否 | 能力 |
| 67117056 | `0x04002000` | 同上，`change=singer_album` | 换成某歌手的专辑 | 音乐 | 否 | 否 | 能力 |
| 67125248 | `0x04004000` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Name`、`mediaCtrl=search` | 搜索《晴天》 | 音乐 | 否 | 否 | 能力 |
| 67141632 | `0x04008000` | 同上，`subFeature=Play_Music_Query_By_Singer`、`mediaCtrl=search` | 搜索张惠妹 | 音乐 | 否 | 否 | 能力 |
| 67174400 | `0x04010000` | 同上，`subFeature=Play_Music_Query_By_Singer_Name`、`mediaCtrl=search` | 搜索周杰伦的《晴天》 | 音乐 | 否 | 否 | 能力 |
| 67239936 | `0x04020000` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_All_Params`、`mediaType=SpeechEntertainmentSource_Recommend` | 播放每日推荐 | 音乐 | 否 | 否 | 能力 |
| 67371008 | `0x04040000` | 同上，`mediaType` 为其他值或为空 | 找适合开车的中文流行歌 | 音乐 | 否 | 否 | 能力；回调含风格、场景、语言等 `search_slot_*`；心情槽位需实车核对 |
| 67633152 | `0x04080000` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_By_Query_History` | 播放最近听过的歌 | 音乐 | 否 | 否 | 能力 |
| 68157440 | `0x04100000` | `INTENT_Ent_Media_Play`，`subFeature=Change_Music_Query`、`change=album_song` | 换成这张专辑里的歌 | 音乐 | 否 | 否 | 能力 |
| 69206016 | `0x04200000` | `INTENT_Ent_Media_Continue`，`subFeature=Play_Music` | 继续放音乐 | 音乐 | 否 | 否 | 能力；与通用 Continue 有不同的码 |
| 71303168 | `0x04400000` | `INTENT_Ent_Media_On_Demand` | 在某 App 点一首歌 | 音乐 | 否 | 否 | 例外；点名登记过的包且 `position > 0` 时可直接分发，无该码掩码检查 |
| 75497472 | `0x04800000` | `INTENT_Ent_Media_Play`，`subFeature=Play_Local_Music` | 播放本地音乐 | 音乐 | 否 | 否 | 本地音乐语义 |
| 100663297 | `0x06000001` | `INTENT_Ent_Media_Play`，`subFeature=Open_Bluetooth_Music` | 打开蓝牙音乐 | 系统 | 否 | 否 | 内置媒体源 |
| 100663298 | `0x06000002` | `INTENT_Ent_Media_Stop`，`subFeature=Stop_Music_BT` | 停止蓝牙音乐 | 系统 | 否 | 否 | 蓝牙源语义 |
| 100663300 | `0x06000004` | `INTENT_Ent_Media_Play`，`subFeature=Play_Bluetooth_Music` | 播放蓝牙音乐 | 系统 | 否 | 否 | 内置媒体源 |
| 100663360 | `0x06000040` | `INTENT_Ent_Media_Continue`，`subFeature=Play_Bluetooth_Music` | 继续播蓝牙音乐 | 系统 | 否 | 否 | 蓝牙源语义 |
| 134217729 | `0x08000001` | `INTENT_Ent_Media_Play`，`subFeature=Open_Usb_Music` | 打开 U 盘音乐 | 系统 | 否 | 否 | 内置媒体源 |
| 134217730 | `0x08000002` | `INTENT_Ent_Media_Stop`，`subFeature=Stop_Music_USB` | 停止 U 盘音乐 | 系统 | 否 | 否 | USB 源语义 |
| 134217732 | `0x08000004` | `INTENT_Ent_Media_Play`，`subFeature=Play_Usb_Music` | 播放 U 盘音乐 | 系统 | 否 | 否 | 内置媒体源 |
| 134217792 | `0x08000040` | `INTENT_Ent_Media_Continue`，`subFeature=Play_Usb_Music` | 继续播 U 盘音乐 | 系统 | 否 | 否 | USB 源语义 |
| 268435457 | `0x10000001` | `INTENT_Ent_Media_QueryMusic` | 这首歌叫什么 | 音乐 | 否 | 否 | 能力；未点名时看焦点源，无查询动作的标准回退 |
| 268435458 | `0x10000002` | `INTENT_Ent_Media_QueryArtist` | 这首歌是谁唱的 | 音乐 | 否 | 否 | 能力；未点名时看焦点源，无查询动作的标准回退 |
| 268435488 | `0x10000020` | `INTENT_Ent_Media_QueryAlbum` | 这首歌出自哪张专辑 | 音乐 | 否 | 否 | 能力；未点名时看焦点源，无查询动作的标准回退 |
| 301989889 | `0x12000001` | `INTENT_Ent_Media_Play`，`subFeature=Play_Music_Query_By_Module` | 播放某个音乐栏目或歌单 | 音乐 | 否 | 否 | 能力；歌单说法与槽位需实车核对 |
| 369098753 | `0x16000001` | `INTENT_System_SoundQuality_Set`，`RECOG_SLOT_System_SoundQualityName` 非空且未命中以下值 | 设置音质 | 系统 | 否 | 否 | 音质语义 |
| 369098754 | `0x16000002` | 同上，`更高音质` | 调高音质 | 系统 | 否 | 否 | 音质语义 |
| 369098756 | `0x16000004` | 同上，`更低音质` | 调低音质 | 系统 | 否 | 否 | 音质语义 |
| 369098760 | `0x16000008` | 同上，`最低音质` | 最低音质 | 系统 | 否 | 否 | 音质语义 |
| 369098768 | `0x16000010` | 同上，`最高音质` | 最高音质 | 系统 | 否 | 否 | 音质语义 |
| 402653185 | `0x18000001` | `INTENT_Ent_SoundEffects_Control`，`RECOG_SLOT_Action=CHANGE` | 换个音效 | 系统 | 否 | 否 | 音效语义 |
| 402653186 | `0x18000002` | 同上，`RECOG_SLOT_Action=OPEN` | 打开音效 | 系统 | 否 | 否 | 音效语义 |
| 402653188 | `0x18000004` | 同上，`RECOG_SLOT_Action=CLOSE` | 关闭音效 | 系统 | 否 | 否 | 音效语义 |

解析器没有看到独立「顺序播放」码。`modeType=0` 对应 AllRepeat，应按列表循环理解。指定歌单名、心情也未看到独立码，建议用实车捕获口语命中的语义与槽位。

## 其他媒体

电台独有码（与音乐同码的动作已列入上表）、新闻、视频和 KTV 对本 App 不适用。

| 十进制 | 十六进制 | 类别 | 适用性 |
|---:|---|---|---|
| 167772161 | `0x0A000001` | 网络电台 | 不适用 |
| 167772162 | `0x0A000002` | 网络电台 | 不适用 |
| 167772164 | `0x0A000004` | 网络电台 | 不适用 |
| 167772168 | `0x0A000008` | 网络电台 | 不适用 |
| 167772176 | `0x0A000010` | 网络电台 | 不适用 |
| 167772192 | `0x0A000020` | 网络电台 | 不适用 |
| 167772224 | `0x0A000040` | 网络电台 | 不适用 |
| 167772288 | `0x0A000080` | 网络电台 | 不适用 |
| 167774208 | `0x0A000800` | 网络电台 | 不适用 |
| 167776256 | `0x0A001000` | 网络电台 | 不适用 |
| 167780352 | `0x0A002000` | 网络电台 | 不适用 |
| 167788544 | `0x0A004000` | 网络电台 | 不适用 |
| 167804928 | `0x0A008000` | 网络电台 | 不适用 |
| 201326593 | `0x0C000001` | 新闻 | 不适用 |
| 201326594 | `0x0C000002` | 新闻 | 不适用 |
| 201326596 | `0x0C000004` | 新闻 | 不适用 |
| 201326600 | `0x0C000008` | 新闻 | 不适用 |
| 201326608 | `0x0C000010` | 新闻 | 不适用 |
| 234881025 | `0x0E000001` | AM/FM 电台 | 不适用 |
| 234881026 | `0x0E000002` | AM/FM 电台 | 不适用 |
| 234881028 | `0x0E000004` | AM/FM 电台 | 不适用 |
| 234881032 | `0x0E000008` | AM/FM 电台 | 不适用 |
| 234881040 | `0x0E000010` | AM/FM 电台 | 不适用 |
| 335544321 | `0x14000001` | KTV | 不适用 |
| 335544322 | `0x14000002` | KTV | 不适用 |
| 335544324 | `0x14000004` | KTV | 不适用 |
| 335544328 | `0x14000008` | KTV | 不适用 |
| 335544336 | `0x14000010` | KTV | 不适用 |
| 436207617 | `0x1A000001` | 视频 | 不适用 |
| 436207618 | `0x1A000002` | 视频 | 不适用 |
| 436207620 | `0x1A000004` | 视频 | 不适用 |
