# 灵犀客户端 shared 模块 Kotlin 代码审查报告

审查日期：2026-07-20
审查范围：commonMain（54 个文件）+ androidMain（14 个文件）+ desktopMain（14 个文件），共 82 个 Kotlin 文件，全部逐一审查。
（注：iosMain 存在对应 actual 实现，不在本次范围内，未审查。）

问题统计：CRITICAL 1 个，WARNING 17 个，INFO 22 个，共 40 项。

---

## 一、CRITICAL（必须修复）

### C1. ChatViewModel.sendMessage 流收集无异常保护——未处理协程异常
- 文件：`shared/src/commonMain/kotlin/top/mvpdark/lingxi/ui/chat/ChatViewModel.kt`
- 位置：第 287-290 行（`sendMessage` 内的 `.collect`）
- 类型：BUG / THREAD_SAFETY
- 描述：`viewModelScope.launch` 中直接调用 `chatRepository.sendMessageStream(...).collect { ... }`，没有任何 try/catch 或 `runCatching` 包裹。虽然 `ChatRepository.sendMessageStream` 内部 catch 了大部分异常并以 error 事件下发，但 Flow 仍然可能抛出异常（如 `handleAgentEvent` 内部出错、上游序列化/取消之外的异常、收藏集挂起函数抛错）。一旦抛出：viewModelScope 的未捕获异常在 Android 上会交给默认 `CoroutineExceptionHandler` 导致应用崩溃；即便不崩溃，`isSending` 也会永远停留在 true，输入框永久禁用（UI 卡死）。
- 建议修复：
```kotlin
val result = runCatchingCancellable {
    chatRepository.sendMessageStream(currentSessionId, pendingText, pendingImage)
        .collect { event -> handleAgentEvent(currentSessionId, event) }
}
_uiState.update { it.copy(isSending = false, agentStatus = null, ...) }
result.onFailure { e ->
    _uiState.update { it.copy(error = e.toUserMessage(), emojiState = EmojiState.IDLE) }
}
```

---

## 二、WARNING（应当修复）

### W1. selectSession 快速切换会话时历史消息错配（竞态）
- 文件：`ui/chat/ChatViewModel.kt`
- 位置：第 169-195 行（`selectSession`）
- 类型：THREAD_SAFETY / BUG
- 描述：进入函数时先置 `currentSessionId = sessionId`，再异步 `localMessageStore.getMessages(sessionId)`。若用户快速点击会话 A→B，A 的加载协程可能晚于 B 完成，第 189 行 `it.copy(messages = history)` 会把 A 的历史写进 B 的界面。
- 建议修复：写入前校验 `_uiState.value.currentSessionId == sessionId`，不 match 则丢弃结果；或保存 Job 并在切换时取消。

### W2. 发送消息过程中切换会话，消息追加到错误的会话界面
- 文件：`ui/chat/ChatViewModel.kt`
- 位置：第 277-282 行（追加 userMessage）、第 428-431 行（done 事件追加 aiMessage）、第 410-411 行（streamingText 更新）
- 类型：BUG / THREAD_SAFETY
- 描述：流式接收期间 `handleAgentEvent` 无条件向 `it.messages` 追加内容。若用户在 AI 回复途中切换到另一个会话，AI 消息/流式文本会出现在新会话的消息列表里（本地存储按原 sessionId 保存，存储层正确，仅 UI 层错乱；切回去再切回来可恢复）。
- 建议修复：`handleAgentEvent` 内所有 `_uiState.update` 前判断 `it.currentSessionId == sessionId`，不一致时只写本地存储、不更新可见消息列表。

### W3. WebSocket 认证包丢失时静默失败
- 文件：`data/repository/ChatRepository.kt`
- 位置：第 121-131 行（`authAck` 判断）
- 类型：BUG
- 描述：`incoming.receiveCatching().getOrNull()` 返回 null（连接在 auth ack 前被关闭）时，代码不 return、不 emit 错误事件，直接继续 `send(chatJson)`，随后遍历已关闭的 `incoming` 立即结束，flow 正常完成——用户看到"发送中"结束后无任何回复也无任何错误提示。
- 建议修复：为 `authAck == null` 增加 else 分支：emit `AgentEvent(type="error", error="连接已断开")` 后 return。

### W4. Bearer Token 泄露给第三方域名（GitHub）
- 文件：`core/network/ApiClient.kt`（第 86-92 行 `authPlugin.onRequest`）+ `data/repository/UpdateRepository.kt`（第 60 行）
- 类型：BUG（安全）
- 描述：`authPlugin.onRequest` 对**所有**出站请求无条件注入 `Authorization: Bearer <灵犀后端token>`，没有 host 白名单。`UpdateRepository.checkLatestVersion` 复用 `apiClient.httpClient` 请求 `https://api.github.com/...`，导致灵犀后端的访问令牌被发送给 GitHub 服务器（并可能进入 GitHub 访问日志）。
- 建议修复：在 `onRequest` 中判断 `request.url.host` 与 `baseUrl` host 一致时才注入 token；或 UpdateRepository 使用独立的 HttpClient。

### W5. Desktop 端 file:// 路径解析错误（潜在 bug）
- 文件：`desktopMain/.../data/local/LocalMessageStore.desktop.kt`
- 位置：第 88 行（saveImage 返回 `"file:///" + absolutePath`）vs 第 92-96 行（resolveImage `removePrefix("file://")`）
- 类型：BUG
- 描述：Windows 上 `saveImage` 产生 `file:///C:/Users/.../x.jpg`，`resolveImage` 去掉 `file://` 后剩 `/C:/Users/...`，`File("/C:/...")` 在 Windows 解析为 `<当前盘符>:\C:\...`，必然不存在，`resolveImage` 永远返回 null。当前 `resolveImage` 无任何调用方（见 R9），属潜在 bug；一旦启用立即踩雷。Android 端因为绝对路径以 `/` 开头，碰巧自洽。
- 建议修复：两端统一保存 file URI 并用 URI 解析（`File(URI(path))`），或保存原始绝对路径。

### W6. PanoramaViewer（Android）：超时后迟到成功，错误遮罩永久遮挡
- 文件：`androidMain/.../ui/components/PanoramaViewer.android.kt`
- 位置：第 254-256 行（`onRendered`）与第 183-192 行（8 秒超时看门狗）、第 330-345 行（错误遮罩）
- 类型：BUG
- 描述：看门狗超时后 `loadError` 非空、显示错误遮罩；若 JS 之后加载成功回调 `onPanoramaLoaded`，`onRendered` 只把 `renderState` 置 2，**不清除 `loadError`**，错误遮罩会一直盖在已正常渲染的全景上。
- 建议修复：`onRendered` 中同时执行 `loadError = null`。

### W7. PanoramaViewer（两端）：临时目录泄漏
- 文件：`androidMain/.../PanoramaViewer.android.kt`（第 109 行创建、第 304-319 行清理）；`desktopMain/.../PanoramaViewer.desktop.kt`（第 470-471 行创建、第 159-169 行清理）
- 类型：LEAK
- 描述：`LaunchedEffect(imageUrl)` 每次 imageUrl 变化都新建 `panorama_*` 临时目录；两端的 `DisposableEffect(Unit)` 只清理**最后一个**目录。同一会话内多次切换全景图时，之前的临时目录永远不会被删除（Android 依赖系统清 cacheDir，Desktop 依赖 JVM 退出钩子）。
- 建议修复：改为 `DisposableEffect(htmlPath/htmlFile)` 使 key 变化时清理旧目录；或复用固定目录先清空再写入。

### W8. PanoramaViewer（Desktop）：每次查看都注册一个 JVM Shutdown Hook
- 文件：`desktopMain/.../PanoramaViewer.desktop.kt`
- 位置：第 473-475 行（`preparePanoramaFiles` 内 `Runtime.getRuntime().addShutdownHook`）
- 类型：LEAK
- 描述：每打开一张全景图就注册一个新 shutdown hook（每个 hook 持有该次目录引用）。长期运行中 hook 数量单调增长，退出时逐个执行 `deleteRecursively()` 拖慢关闭。
- 建议修复：只注册一次（companion object 静态初始化），hook 内清理统一的 panorama 临时根目录。

### W9. SAM resolveMaskDims 与 resolveMaskChannelCount 对 4D 输出理解互相矛盾
- 文件：`androidMain/.../sam/SamService.android.kt`（第 240、243、453-474、497-503 行）；`desktopMain/.../sam/SamService.desktop.kt`（同名函数，完全复制）
- 类型：BUG
- 描述：`resolveMaskDims` 注释把 4D 解释为 `[1, N, H, W]`（无通道维，stride=H*W）；`resolveMaskChannelCount` 注释却把 4D 解释为 `[N, C, H, W]`（shape[1]=通道数 C）。第 243 行 `maskStride = hLow * wLow * resolveMaskChannelCount(shape)`——若模型输出真是 `[1, N, H, W]`，channelCount 返回 N，stride 变成 H*W*N：第 0 个物体把 N 张 mask 混合读取，第 1 个起 `maskBuffer.position(i*stride)` 越界抛 `IndexOutOfBoundsException`。当前 EdgeTAM 输出为 5D `[1, N, 1, H, W]`（两端一致）所以未爆发，但代码宣称支持 4D，属埋雷。
- 建议修复：统一 4D 语义（推荐按 `[1, N, H, W]`，channelCount 对 4D 返回 1），并为 4D 情形加单元测试。

### W10. AnimatedEmoji 忽略 APNG 帧合成规则
- 文件：`commonMain/.../ui/emoji/AnimatedEmoji.kt`（第 69-77 行加载、第 100-108 行播放）
- 类型：BUG（功能缺失）
- 描述：`ApngParser` 已解析出每帧 `xOffset/yOffset/width/height/disposeOp/blendOp`，但 AnimatedEmoji 把每帧 pngBytes 当作完整画面直接 `decodePngBytes` 显示，不做画布合成。对"局部更新帧"的 APNG（常见于优化过的表情）会渲染错位/残缺。另外 `numPlays` 被忽略，`while(true)` 永远无限循环，一次性播放的 APNG 行为不符规范。
- 建议修复：按 fcTL 参数把每帧绘制到累计画布（处理 BLEND_SOURCE/OVER 与 DISPOSE_BACKGROUND/PREVIOUS）；按 `numPlays` 控制循环次数（0=无限）。

### W11. 消息时间显示为 UTC，未转换到本地时区
- 文件：`commonMain/.../core/util/DateTimeUtil.kt`
- 位置：第 86-88 行（`parseIso8601` 剥离时区）、第 123-142 行（`getCurrentIso8601` 生成 UTC）
- 类型：BUG
- 描述：解析时 `substringBefore("+").substringBefore("Z")` 直接丢弃时区偏移而非换算，显示的是 UTC 时间——中国用户看到的聊天时间比本地慢 8 小时。另外 `substringBefore("+")` 不能处理负偏移（`-05:00`），此时秒字段解析失败静默退化为 0。
- 建议修复：用 `java.time`（JVM 两端可用，可下沉到 JVM 公共源集）或 kotlinx-datetime 解析 Instant 后转本地时区显示。

### W12. 下载不校验 HTTP 状态码
- 文件：`androidMain/.../core/util/ImageSaver.android.kt`（第 64-74 行）、`desktopMain/.../core/util/ImageSaver.desktop.kt`（第 70-80 行）、`commonMain/.../data/local/ImageCacheManager.kt`（第 71-74 行）、`androidMain/.../ui/update/ApkInstaller.android.kt`（第 50-51 行，INFO 级）
- 类型：BUG
- 描述：`HttpURLConnection`/`connection.inputStream`（Java/Ktor）在 404/500 时拿到的错误页字节会被原样保存为 .jpg/.apk。Android `ImageSaver.download` 用的是 `url.openStream()`，404 时 `getInputStream` 会抛 IOException 尚可兜底，但 3xx 到错误页、200 错误页等情况仍可能写入脏数据；ImageCacheManager 用 Ktor 不抛异常的默认配置，`bodyAsBytes()` 直接写入缓存文件，此后该 URL 命中坏缓存永远无法自愈（没有失败缓存清除机制）。
- 建议修复：校验 `response.status.isSuccess()` / `connection.responseCode == 200` 再写文件；ImageCacheManager 写缓存失败时删除半成品文件。

### W13. SamService 两端约 250 行纯复制
- 文件：`androidMain/.../sam/SamService.android.kt` 与 `desktopMain/.../sam/SamService.desktop.kt`
- 位置：`segment()`、`discoverVisionIO`、`discoverDecoderIO`、`resolveMaskDims`、`resolveMaskChannelCount`、`resolveOrigSizeShape`、候选名常量表
- 类型：DUPLICATE
- 描述：除图片解码（BitmapFactory vs ImageIO）与模型目录解析外，推理编排、IO 发现、维度解析、资源逆序释放逻辑逐字相同。W9 的矛盾已在两份拷贝中同时存在——这正是复制代码的危害实例。
- 建议修复：建立 `jvmCommon`（或 `nonAppleMain`）源集，或把推理编排抽成 commonMain 内部类，平台只注入"图片解码→ARGB IntArray"与"模型目录"两个函数。

### W14. ImageSaver 两端工具函数纯复制
- 文件：`androidMain/.../core/util/ImageSaver.android.kt`（第 51-59、64-74、79-84、144-147 行）与 `desktopMain/.../core/util/ImageSaver.desktop.kt`（第 57-65、70-80、85-90、95-98 行）
- 类型：DUPLICATE
- 描述：`readBytes`、`download`、`decodeDataUrl`、`sanitizeFileName` 四个函数逐字相同，且都是纯 JVM 代码。
- 建议修复：抽到 JVM 公共源集或 commonMain 内部 expect/actual 单点实现。

### W15. LocalMessageStore 两端几乎完全复制
- 文件：`androidMain/.../data/local/LocalMessageStore.android.kt` 与 `desktopMain/.../data/local/LocalMessageStore.desktop.kt`
- 类型：DUPLICATE
- 描述：除 `baseDir` 和 `saveImage` 的 file:// 前缀外，约 150 行（Json 配置、读写、重命名、删除、Mutex、md5）完全一致。W5 的 platform 差异 bug 正是复制导致的分叉。
- 建议修复：同 W13，公共逻辑下沉，平台只提供 `baseDir`。

### W16. PanoramaViewModel.encodeBase64 与 EncodeUtils 完全重复
- 文件：`commonMain/.../ui/panorama/PanoramaViewModel.kt`（第 148-166 行）vs `commonMain/.../core/util/EncodeUtils.kt`（第 10-37 行）
- 类型：DUPLICATE
- 描述：两份逐字相同的私有 Base64 实现（EncodeUtils 的 KDoc 甚至注明"与 PanoramaViewModel 中的算法保持一致"）。
- 建议修复：删除 PanoramaViewModel 私有版本，改用 `EncodeUtils.encodeBase64`。

### W17. SamService.loadModel 并发竞态（两端）
- 文件：`androidMain/.../sam/SamService.android.kt`（第 57-58 行）、`desktopMain/.../sam/SamService.desktop.kt`（第 69-70 行）
- 类型：THREAD_SAFETY
- 描述：`if (isReady) return` 非原子检查。两个协程并发调用 `loadModel` 时都能通过检查，各自创建 OrtSession——后写覆盖先写，先创建的一对 session 泄漏（native 内存可达数百 MB），`discover*IO` 字段也存在写写竞争。当前调用方只有 ImageEditViewModel 且有 isProcessing 保护，属低风险，但 API 本身不安全。
- 建议修复：加 `Mutex().withLock`，锁内再查 isReady（双检）。

---

## 三、INFO（建议改进 / 死代码清理）

### R1. 未使用的 import
- 文件：`commonMain/.../data/local/ImageCacheManager.kt` 第 4 行 `import io.ktor.client.call.body`（只用到 `bodyAsBytes`）；`ui/theme/Theme.kt` 第 3 行 `import androidx.compose.foundation.isSystemInDarkTheme`。
- 类型：REDUNDANT

### R2. ImageCacheManager.cacheMessagesImages 死代码
- 文件：`commonMain/.../data/local/ImageCacheManager.kt` 第 56-61 行。全工程无调用（缓存逻辑已内联在 ChatViewModel）。建议删除或接线。

### R3. 服务端历史相关死代码
- `data/repository/ChatRepository.kt` 第 77-81 行 `getHistory`（无调用）；`data/model/Models.kt` 第 143 行 `HistoryResponse`、第 73 行 `CreateSessionRequest`、第 149 行 `ApiResult`（均无引用）。建议删除或确认后续需求保留。

### R4. DefaultDispatcherProvider 死代码
- 文件：`core/util/DispatcherProvider.kt` 第 31-36 行。整个文件（含 `AppDispatchers` 接口）无任何引用。

### R5. AgentEmojiState.resourcePath 恒为 null 且无人读取
- 文件：`ui/emoji/EmojiState.kt` 第 38-50 行。`getAgentEmojiPath` 完全不使用该字段。建议删除字段。

### R6. ChatScreen 小问题
- 第 464 行：`var dragging` 状态只写不读（第 543/545/565 行赋值后从未消费）——删除。
- 第 216 行：`chatViewModel.createNewSession { id -> }` 空回调 lambda——直接调 `createNewSession()`。
- 第 250-254 行：LazyColumn key 回退 `"msg_${timestamp}_${content.take(10)}"`，两条同内容同空时间戳消息会产生重复 key 崩溃风险（当前 id 均有值，属边缘）。

### R7. 空 onCleared 覆写
- `ui/panorama/PanoramaViewModel.kt` 第 143-145 行；`ui/imageedit/ImageEditViewModel.kt` 第 353-355 行。删除或实现资源释放（后者可在 onCleared 调 `samService.close()`）。

### R8. ImageEditViewModel.continueEdit() 与 resetAll() 完全相同
- 文件：`ui/imageedit/ImageEditViewModel.kt` 第 343-345 行 vs 第 331-333 行。建议让 `continueEdit()` 直接调用 `resetAll()` 或删除其一（UI 中"重新开始"与"换一张图"语义重合）。

### R9. LocalMessageStore.resolveImage 三端公开 API 无人调用
- `commonMain/.../LocalMessageStore.kt` 第 48 行声明 + 两个 actual。死 API（且 desktop 实现有 W5 bug）。建议删除，或接线修复 W5。

### R10. 旧主题系统死分支
- `ui/theme/Theme.kt` 第 40-59 行兼容垫片（`LingxiThemeStyle`/`LocalThemeStyle`）使 8 个文件中的 `isNoirAurum` 判断永远为 true：`ChatBubble.kt:82`、`ChatScreen.kt:120/624/728/782`、`HomeScreen.kt:93/269/360`、`LoginScreen.kt:83`、`LoadingIndicator.kt:39`、`PlaceholderScreen.kt:33`。QUIET_MATERIALITY 分支全部为不可达代码。建议删除垫片与各死分支（数百行），或恢复双主题。

### R11. formatDouble 使用默认 Locale
- `androidMain/.../core/util/PlatformUtils.android.kt` 第 11-13 行、`desktopMain/.../PlatformUtils.desktop.kt` 第 8-10 行。`String.format("%.2f", ...)` 在德/法等 Locale 下输出 `1,50`（逗号）。余额显示 `¥1,50` 属显示 bug。建议 `String.format(Locale.US, ...)`。同类：`DateTimeUtil.kt` 第 140 行的 `%04d` 等在阿拉伯 Locale 下会输出非 ASCII 数字。

### R12. PanoramaUiState 持有 ByteArray（data class equals 失效）
- 文件：`ui/panorama/PanoramaViewModel.kt` 第 19 行。data class 中 ByteArray 按引用比较，`copy(imageBytes = 同引用)` 时 Compose 认为"未变化"可能跳过重绘/或相反。ImageEditUiState 已用 `@Suppress("ArrayInDataClass")` 正视该问题，此处建议同样处理并注释说明。

### R13. WebView 调试开关未按 Build 类型门控
- 文件：`androidMain/.../ui/components/PanoramaViewer.android.kt` 第 246 行 `WebView.setWebContentsDebuggingEnabled(true)`。Release 包也开启调试。建议 `if (BuildConfig.DEBUG)` 门控。同文件第 240-241 行 `allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs` 权限偏宽，当前内容经 AssetLoader 走 https 可收窄为 false。

### R14. ChatBubble 纯图片消息渲染空气泡
- 文件：`ui/components/ChatBubble.kt`（`ChatBubble` 内 Column 结构）。text 为 blank 且仅有 images 时，带背景 padding 的空气泡仍渲染在图片上方。建议 text blank 时跳过文本气泡 Box。

### R15. APNG 规范偏差
- `ui/emoji/ApngParser.kt` 第 241-249 行：注释自述"默认图像（非动画第一帧）"，但 `frames.add(0, defaultFrame)` 仍把它插入动画序列并以默认 100ms 播放——按 APNG 规范（fcTL 在 IDAT 之后时 IDAT 仅为静态 fallback）不该计入动画。第 117 行 acTL 的 `numFrames` 解析后从未校验。另 `parseFctl` 直接读 `data[24]/data[25]`，畸形短 fcTL 会抛 `IndexOutOfBoundsException`（第 676 行 CRC 常量 `-0x12477CE0` 已核实正确，等价 0xEDB88320）。

### R16. ApiClient 401 自动刷新后不重放原请求
- 文件：`core/network/ApiClient.kt` 第 95-111 行。插件在 401 时刷新了 token 但原请求仍以 401 失败返回（注释已承认）；`ImageEditRepository` 自建了 `requestWithAuthRetry` 重试，其余 Repository 无重试——token 过期瞬间的非改图请求会失败一次。半成品特性，建议统一重试或移除插件内刷新。另 `close()`（第 224-227 行）无人调用，Koin 单例生命周期内 client 不释放（注释已说明，可接受）。

### R17. ImageSaver 强制 .jpg / image-jpeg
- `androidMain/.../ImageSaver.android.kt` 第 92、113 行；`desktopMain/.../ImageSaver.desktop.kt` 第 48 行。PNG/WebP 字节以 .jpg 扩展名与 image/jpeg MIME 保存，依赖查看器嗅探。建议按 magic bytes 推断真实格式。

### R18. LocalMessageStore 细节
- 两端 `messagesDir` getter 每次访问都 `mkdirs()`（android 第 38-39 行 / desktop 第 37-38 行）——IO 副作用放进 getter；单一全局 Mutex 串行化所有会话读写；`saveMessage` 整文件读-改-写 O(n²) 累积；删除会话不清理 `images/` 目录文件。均为可接受的实现取舍，建议注释或后续优化。

### R19. MaskPng 两端行为不一致
- `androidMain/.../sam/MaskPng.android.kt` 第 25 行 `require` 在 runCatching 之外（超限抛异常），第 48 行编码失败静默返回 ""；`desktopMain/.../MaskPng.desktop.kt` 失败直接抛异常。与 KDoc"失败返回空字符串"不一致，两端失败语义不同。

### R20. copyAssetIfNeeded 半截文件不重拷
- `androidMain/.../sam/SamService.android.kt` 第 367 行：`destFile.exists() && length() > 0` 即跳过。上次崩溃留下的截断模型文件会导致 ONNX 加载报难解错误。建议拷到临时文件再原子重命名，或比对 assets 大小。

### R21. SamService.close() 死代码 + 与 segment 并发风险
- 两端 `close()`（android 第 309 行 / desktop 第 325 行）无任何调用方；若将来调用，与运行中的 `segment()` 并发会关闭使用中的 OrtSession（native 崩溃风险）。单例生命周期内不释放 session 可接受，建议删除 close() 或在 ImageEditViewModel.onCleared 接线并加锁。

### R22. 其他 UI 交互小项
- `ui/screens/ImageEditScreen.kt` 第 400-408 行：重叠物体 `firstOrNull` 只命中第一个，下层物体无法点选（可加循环切换）。
- `ui/components/FullScreenImagePreview.kt` 第 102-112 行：平移 offset 无边界限制，图片可被拖出屏幕（单击任意处关闭可兜底恢复）；第 71 行整背景 clickable 使"单击图片本身"也触发关闭，与缩放手势并存时体验可接受但建议确认是预期行为。
- `ui/navigation/NavGraph.kt` 第 44-49 行：`startDestination` 依赖 `authState.isLoggedIn`，登录/登出状态翻转会重建整个 NavHost 图（当前行为巧合正确，较脆弱）。
- `ui/auth/AuthViewModel.kt` 第 140 行：注册成功提示"注册成功，请登录"写入 `error` 字段，将以错误样式（红色）展示。建议独立 message 字段。

---

## 四、交叉核对结果（无问题项）

1. **expect/actual 一致性**：11 组 expect 声明（PlatformContext/currentPlatform/createEngine、TokenStore、PlatformUtils x4、PlatformLogger x3、ImageSaver、LocalMessageStore、PanoramaViewer、ImmersiveSystemBarsEffect、rememberImagePickerLauncher、maskToPngBase64、decodePngBytes、SamService、ApkInstaller）共 77 处声明逐一核对，androidMain 与 desktopMain actual 签名、可见性、默认参数位置全部匹配。
2. **DI 注册完整性**：`AppModule`（common）所需 `TokenStore`、`PlatformContext`（供 ImageSaver/LocalMessageStore/SamService）均由两端 `PlatformModule` 提供；`ChatViewModel(get,get,get)`、`ImageEditViewModel(get,get)`、`PanoramaViewModel(get)`、`UpdateViewModel(get)` 构造参数与注册一一对应。Android 端 `AndroidAppContextHolder` 初始化顺序要求已在 KDoc 注明。
3. **资源管理亮点**：SamService 推理资源逆序释放、bitmap 双保险 recycle、JS bridge 回调 post 到主线程、rememberUpdatedState 避免手势检测器重启、TokenStore 双端加密/私有目录、MaskPostProcessor 坐标钳制与缩放兜底等实现质量良好。
