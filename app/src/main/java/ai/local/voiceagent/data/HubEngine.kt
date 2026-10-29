package ai.local.voiceagent.data

import ai.local.voiceagent.net.AgentInfo
import ai.local.voiceagent.net.ConnState
import ai.local.voiceagent.net.HubClient
import ai.local.voiceagent.net.HubEvent
import ai.local.voiceagent.net.Prefs
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级中枢：每个 Agent 一条 SSE 长连接，事件入库 + 广播给 UI。
 *
 * 为什么放在进程级而不是 ViewModel 里：
 *   · 会话列表页也要收「运行中 / 等你回话 / 未读」，不能只在详情页连
 *   · 一条 SSE 多处消费 → 用 SharedFlow 分发，避免一个 Agent 开多条连接
 */
class HubEngine(
    private val client: HubClient,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val appCtx: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<HubEvent>(replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents

    private val _conn = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val conn: StateFlow<Map<String, ConnState>> = _conn

    private val _errors = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** 每个 Agent 各报各的错，不要把别人的错误显示到当前会话 */
    val errors: StateFlow<Map<String, String?>> = _errors.asStateFlow()

    private val _globalError = MutableStateFlow<String?>(null)
    val globalError: StateFlow<String?> = _globalError.asStateFlow()

    private val subs = mutableMapOf<String, Job>()
    private val subLock = Any()
    private var started = false

    /** 当前正在看的会话：用于决定完成时算「已读」还是「等你回话 +1」 */
    @Volatile
    var viewingSessionId: Long? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { refresh() }
    }

    /** 改了地址/令牌之后重建全部订阅 */
    fun restart() {
        synchronized(subLock) {
            subs.values.forEach { it.cancel() }
            subs.clear()
        }
        _conn.value = emptyMap()
        _errors.value = emptyMap()
        started = true
        scope.launch { refresh() }
    }

    suspend fun refresh(): Result<List<AgentInfo>> = withContext(Dispatchers.IO) {
        runCatching { client.agents() }
            .onSuccess { list ->
                _agents.value = list
                _globalError.value = null
                ensureMainSessions(list.map { it.agentId })
                list.forEach { subscribe(it.agentId) }
            }
            .onFailure { _globalError.value = "列 Agent 失败：${it.message}" }
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = client.health()
            val agents = client.agents().joinToString(", ") { "${it.agentId}${if (it.ready) "" else "(未就绪)"}" }
            "连接正常 · Agent：$agents · ${body.length} 字节 health"
        }
    }

    /** 每个 Agent 自动建一条主线会话（name 为空） */
    private suspend fun ensureMainSessions(agentIds: List<String>) {
        val dao = db.sessionDao()
        for (agentId in agentIds) {
            if (dao.find(agentId, "") == null) {
                dao.insert(
                    SessionEntity(
                        agentId = agentId,
                        name = "",
                        title = agentId,
                        status = STATUS_IDLE,
                    )
                )
            }
        }
    }

    suspend fun ensureSession(agentId: String, name: String): Long =
        withContext(Dispatchers.IO) {
            val clean = name.trim()
            db.sessionDao().find(agentId, clean)?.id ?: db.sessionDao().insert(
                SessionEntity(
                    agentId = agentId,
                    name = clean,
                    title = if (clean.isEmpty()) agentId else "$agentId · $clean",
                )
            )
        }

    /**
     * 新建一条会话：不用起名字（用户说了算 —— 第一句话就是会话名）。
     * name 是跟 cc-connect 约定的会话标识，这里自动生成一个不会撞的。
     */
    suspend fun newSession(agentId: String): Long = withContext(Dispatchers.IO) {
        val name = "c" + System.currentTimeMillis().toString(36)
        db.sessionDao().insert(
            SessionEntity(agentId = agentId, name = name, title = DEFAULT_SESSION_TITLE)
        )
    }

    /**
     * 用第一句话给会话命名。只对"还没正经命名"的会话生效：
     * 新会话 / 空标题 / 标题就等于 agentId（旧版本建的会话），已有正经名字的不动。
     */
    private suspend fun nameFromFirstMessage(s: SessionEntity, text: String) {
        val t = s.title.trim()
        if (t.isNotEmpty() && t != DEFAULT_SESSION_TITLE && t != s.agentId) return
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return
        db.sessionDao().setTitle(s.id, line.take(24))
    }

    private fun subscribe(agentId: String) {
        // refresh() 可能被并发调用（启动 + 下拉刷新），不加锁会起两条 SSE，
        // 一条正常一条卡死，界面状态就被后写的那条覆盖。
        synchronized(subLock) {
            if (subs[agentId]?.isActive == true) return
            subs[agentId] = scope.launch {
                client.events(agentId).collect {
                    // 一个事件处理失败（比如入库撞约束）不能让整条订阅死掉，
                    // 否则后面这个 Agent 的所有消息都收不到，且界面上毫无痕迹
                    runCatching { handle(it) }.onFailure { ex ->
                        android.util.Log.e("HubEngine", "handle ${it::class.simpleName} 失败", ex)
                    }
                }
            }
        }
    }

    private suspend fun handle(e: HubEvent) {
        when (e) {
            is HubEvent.Conn -> {
                // 必须用 update{} 原子 CAS：每个 Agent 一条协程，各自 read-modify-write
                // 同一个 StateFlow，用 value = value + x 会互相覆盖（表现为随机一个 Agent 永远"离线"）
                _conn.update { it + (e.agentId to e.state) }
                _errors.update {
                    it + (e.agentId to when (e.state) {
                        ConnState.RETRYING -> e.detail?.let { d -> "连接中断：$d" } ?: "连接中断"
                        ConnState.ERROR -> "连接失败"
                        else -> null
                    })
                }
            }

            is HubEvent.Typing -> {
                sessionOf(e.agentId, e.sessionName)?.let { s ->
                    db.sessionDao().setStatus(
                        s.id,
                        if (e.on) STATUS_RUNNING else if (s.status == STATUS_RUNNING) STATUS_IDLE else s.status,
                    )
                }
            }

            is HubEvent.Stream -> {
                if (!e.done) {
                    sessionOf(e.agentId, e.sessionName)?.let { db.sessionDao().setStatus(it.id, STATUS_RUNNING) }
                }
            }

            is HubEvent.Done -> onReply(e.agentId, e.sessionName, e.replyCtx, e.text)

            is HubEvent.Push -> onReply(e.agentId, e.sessionName, e.replyCtx, e.text)

            is HubEvent.Card -> onRich(
                agentId = e.agentId, name = e.sessionName, kind = KIND_CARD,
                payload = e.cardJson, replyCtx = e.replyCtx,
                preview = "[卡片] " + cardTitle(e.cardJson),
            )

            is HubEvent.Buttons -> onRich(
                agentId = e.agentId, name = e.sessionName, kind = KIND_BUTTONS,
                payload = e.buttonsJson, replyCtx = e.replyCtx,
                preview = e.content?.take(80) ?: "[需要你点一下]",
            )

            is HubEvent.Attachment -> {
                // 语音附件：data 是 base64，一条就近 200KB，落盘后再入库，
                // Room 里只留 {"file":"v…wav","durMs":…}，否则列表查询背着几百 KB 会卡
                var payload = e.payloadJson
                if (e.kind == "audio") {
                    payload = saveIncomingAudio(e.payloadJson) ?: payload
                } else if (e.kind == "image" || e.kind == "file") {
                    // 图片/文件同理：base64 落盘，Room 里只留路径，免得列表查询背着几 MB
                    payload = saveIncomingFile(e.payloadJson) ?: payload
                }
                onRich(
                    agentId = e.agentId, name = e.sessionName, kind = e.kind,
                    payload = payload, replyCtx = null,
                    preview = when (e.kind) {
                        "image" -> "[图片]"
                        "file" -> "[文件]"
                        "audio" -> "[语音]"
                        else -> "[${e.kind}]"
                    },
                )
            }

            is HubEvent.Notice -> Unit   // 过程提示（正在转录 / 已排队），只进 events 给 UI 做轻提示

            is HubEvent.ClearPreview -> Unit   // M4 语音预览气泡才需要，M3 无临时气泡

            else -> Unit
        }
        _events.emit(e)
    }

    /** 把上游推来的语音落盘，返回给 Room 存的小 JSON；失败返回 null（保留原始 payload 兜底） */
    private fun saveIncomingAudio(payloadJson: String): String? = try {
        val o = org.json.JSONObject(payloadJson)
        val b64 = o.optString("data")
        if (b64.isBlank()) {
            null
        } else {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val name = ai.local.voiceagent.voice.VoiceStore.save(appCtx, bytes)
            val durMs = estimateMs(bytes)
            val out = org.json.JSONObject()
                .put("file", name)
                .put("durMs", durMs)
                .put("format", o.optString("format", "wav"))
            out.toString()
        }
    } catch (e: Exception) {
        null
    }

    /** 调试用：直接往会话里塞一条 Agent 附件消息 */
    suspend fun injectAgentMessage(sessionId: Long, kind: String, payload: String) = withContext(Dispatchers.IO) {
        db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                role = "agent",
                text = "",
                at = System.currentTimeMillis(),
                kind = kind,
                payload = payload,
            )
        )
        db.sessionDao().touch(sessionId, if (kind == "image") "[图片]" else "[文件]",
            System.currentTimeMillis(), STATUS_RUNNING, 0)
    }

    /** Agent 发来的图片/文件：base64 写进 filesDir/incoming/，payload 只剩路径+元信息 */
    private fun saveIncomingFile(payloadJson: String): String? = try {
        val o = org.json.JSONObject(payloadJson)
        val b64 = o.optString("data")
        if (b64.isBlank()) {
            null
        } else {
            val bytes = android.util.Base64.decode(b64.substringAfter("base64,"), android.util.Base64.DEFAULT)
            val name = o.optString("file_name")
                .ifBlank { o.optString("name") }
                .ifBlank { "file" }
                .replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            val dir = java.io.File(appCtx.filesDir, "incoming").apply { mkdirs() }
            val f = java.io.File(dir, System.currentTimeMillis().toString(36) + "_" + name)
            f.writeBytes(bytes)
            org.json.JSONObject()
                .put("path", f.absolutePath)
                .put("file_name", name)
                .put("mime_type", o.optString("mime_type").ifBlank { o.optString("mime") })
                .put("size", bytes.size.toLong())
                .toString()
        }
    } catch (e: Exception) {
        null
    }

    /** wav 头里算时长：16bit 单声道 → 字节数 / (采样率×2) */
    private fun estimateMs(wav: ByteArray): Int = try {
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        if (rate <= 0) 0 else ((wav.size - 44) * 1000 / (rate * 2))
    } catch (e: Exception) {
        0
    }

    /** 卡片标题，用于会话列表的最后一条摘要 */
    private fun cardTitle(cardJson: String): String = try {
        org.json.JSONObject(cardJson).optJSONObject("header")?.optString("title").orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** 通知用：这个 (agentId, 会话名) 对应的本地 sessionId */
    suspend fun sessionIdOf(agentId: String, name: String?): Long? =
        db.sessionDao().find(agentId, name ?: "")?.id

    /** 通知用：用户此刻是不是正看着这条会话（看着就别弹通知） */
    suspend fun isViewing(agentId: String, name: String?): Boolean {
        val id = viewingSessionId ?: return false
        val s = db.sessionDao().get(id) ?: return false
        return s.agentId == agentId && s.name == (name ?: "")
    }

    /** 通知用：卡片标题（拿不到就退回空串） */
    fun cardTitleOf(cardJson: String): String = cardTitle(cardJson)

    /** 卡片 / 权限按钮 / 附件：原样入库，交给 UI 按 kind 渲染 */
    private suspend fun onRich(
        agentId: String,
        name: String?,
        kind: String,
        payload: String,
        replyCtx: String?,
        preview: String,
    ) {
        val at = System.currentTimeMillis()
        val s = sessionOf(agentId, name, create = true) ?: return
        db.messageDao().insert(
            MessageEntity(
                sessionId = s.id, role = ROLE_AGENT, text = preview, at = at,
                msgId = replyCtx, kind = kind, payload = payload, replyCtx = replyCtx,
            )
        )
        val viewing = viewingSessionId == s.id
        db.sessionDao().touch(
            id = s.id,
            text = preview.take(200),
            at = at,
            status = if (viewing) STATUS_IDLE else STATUS_WAITING,
            unreadInc = if (viewing) 0 else 1,
        )
    }

    /**
     * 这条事件是不是「我发的问题」引来的？
     * 多台手机共用同一个会话（同一个设备标识）时，hub 是广播的 —— 两台都会收到，
     * 但只有发问的那台该响铃。replyCtx 为空的是公共推送（定时任务之类），照旧提醒。
     */
    suspend fun isMyReply(replyCtx: String?): Boolean {
        if (replyCtx.isNullOrBlank()) return true
        return withContext(Dispatchers.IO) {
            runCatching { db.messageDao().countMyMsg(replyCtx) > 0 }.getOrDefault(true)
        }
    }

    private suspend fun onReply(agentId: String, name: String?, replyCtx: String?, text: String) {
        if (text.isBlank()) return
        val at = System.currentTimeMillis()
        // hub 重连补投时会把同一条答案再发一次：只有 replyCtx 和文本都相同才算重复，
        // 否则同一个 replyCtx 下的第二条（正文）会被第一条（提示）挡掉
        if (!replyCtx.isNullOrBlank() && db.messageDao().countAgentText(replyCtx, text) > 0) {
            sessionOf(agentId, name)?.let { s ->
                db.sessionDao().setStatus(s.id, if (viewingSessionId == s.id) STATUS_IDLE else STATUS_WAITING)
            }
            return
        }
        val s = sessionOf(agentId, name, create = true) ?: return
        db.messageDao().insert(
            MessageEntity(sessionId = s.id, role = ROLE_AGENT, text = text, at = at, msgId = replyCtx)
        )
        val viewing = viewingSessionId == s.id
        db.sessionDao().touch(
            id = s.id,
            text = text.take(200),
            at = at,
            status = if (viewing) STATUS_IDLE else STATUS_WAITING,
            unreadInc = if (viewing) 0 else 1,
        )
    }

    private suspend fun sessionOf(agentId: String, name: String?, create: Boolean = false): SessionEntity? {
        val key = name ?: ""
        db.sessionDao().find(agentId, key)?.let { return it }
        if (!create) return null
        val id = db.sessionDao().insert(
            SessionEntity(
                agentId = agentId,
                name = key,
                title = if (key.isEmpty()) agentId else "$agentId · $key",
            )
        )
        return db.sessionDao().get(id)
    }

    /** 发一条文本；用户消息先落库，再走网络。fromVoice=true 表示这条是语音转写发来的 */
    suspend fun send(sessionId: Long, text: String, fromVoice: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val s = db.sessionDao().get(sessionId) ?: return@withContext Result.failure(IllegalStateException("会话不存在"))
        val at = System.currentTimeMillis()
        // 文字就是文字：哪怕这句话是语音转出来的，气泡也要回显文字。
        // （曾经把 fromVoice 的文本存成 KIND_VOICE，结果气泡按语音条渲染 → "▶ 语音 0s"、文字看不见）
        val rowId = db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                role = ROLE_USER,
                text = text,
                at = at,
                kind = KIND_TEXT,
                payload = if (fromVoice) "{\"fromVoice\":true}" else null,
            )
        )
        db.sessionDao().touch(sessionId, text.take(200), at, STATUS_RUNNING, 0)
        nameFromFirstMessage(s, text)          // 第一句话 = 会话名
        val r = runCatching { client.send(s.agentId, text, s.name) }
        r.onSuccess { ack ->
            _errors.update { it + (s.agentId to null) }
            // 回执 msgId 补回到用户消息上，便于将来按 replyCtx 精确归属
            db.messageDao().setMsgId(rowId, ack.msgId)
        }.onFailure { err ->
            db.sessionDao().setStatus(sessionId, STATUS_IDLE)
            _errors.update { it + (s.agentId to "发送失败：${err.message}") }
        }
        r.map { it.msgId }
    }

    /** 发一条语音条：本地先落盘存档，再上传给 cc-connect（它会自己转写并回语音） */
    suspend fun sendVoice(sessionId: Long, wav: ByteArray, durationMs: Long): Result<String> = withContext(Dispatchers.IO) {
        val s = db.sessionDao().get(sessionId) ?: return@withContext Result.failure(IllegalStateException("会话不存在"))
        val at = System.currentTimeMillis()
        val fileName = ai.local.voiceagent.voice.VoiceStore.save(appCtx, wav)
        val payload = org.json.JSONObject()
            .put("file", fileName)
            .put("durMs", durationMs)
            .put("format", "wav")
            .toString()
        val rowId = db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                role = ROLE_USER,
                text = "",                 // 转写文本由 Agent 侧回执给出，这里不重复展示
                at = at,
                kind = KIND_VOICE,
                payload = payload,
            )
        )
        db.sessionDao().touch(sessionId, "[语音] ${durationMs / 1000}s", at, STATUS_RUNNING, 0)
        val r = runCatching { client.sendVoice(s.agentId, s.name, wav, durationMs) }
        r.onSuccess { msgId -> db.messageDao().setMsgId(rowId, msgId) }
            .onFailure {
                db.sessionDao().setStatus(sessionId, STATUS_IDLE)
                _errors.update { m -> m + (s.agentId to "语音发送失败：${it.message}") }
            }
        r
    }

    /** 选完文件先看一眼名字/大小/类型（不读内容、不上传）—— 挂到输入框上方让用户先写一句话 */
    data class FileMeta(val name: String, val size: Long, val mime: String)

    fun describeFile(uri: android.net.Uri): FileMeta {
        val cr = appCtx.contentResolver
        val name = queryDisplayName(cr, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
        val mime = cr.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMime(name)
        val size = runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) c.getLong(i) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        return FileMeta(name, size, mime)
    }

    /** 待发附件（一个或多个） */
    data class AttachItem(val uri: android.net.Uri, val name: String, val mime: String, val size: Long)

    /**
     * 发附件给 Agent（支持多选，一次一条消息）。
     * 先在本地落一条气泡（sending），上传成功改 sent、失败改 failed。
     * 上传是流式的：帧拼接写进 socket，不在手机内存里堆整包。
     */
    suspend fun sendFiles(sessionId: Long, items: List<AttachItem>, caption: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val s = db.sessionDao().get(sessionId)
                ?: return@withContext Result.failure(IllegalStateException("会话不存在"))
            if (items.isEmpty()) return@withContext Result.failure(IllegalStateException("没有附件"))
            if (items.size > MAX_FILES) {
                return@withContext Result.failure(IllegalStateException("一次最多 ${MAX_FILES} 个文件"))
            }
            val total = items.sumOf { it.size }
            if (total > MAX_ATTACH_TOTAL) {
                return@withContext Result.failure(IllegalStateException("文件总大小超过 40MB"))
            }
            // 大小未知的（个别 provider 不给 SIZE）先量一下
            val sized = items.map { it ->
                if (it.size > 0) it else it.copy(size = runCatching { openStream(it.uri)?.use { st -> st.available().toLong() } ?: 0L }.getOrDefault(0L))
            }

            val at = System.currentTimeMillis()
            val kind = if (sized.size == 1 && sized[0].mime.startsWith("image/")) KIND_IMAGE else KIND_FILE
            val arr = org.json.JSONArray()
            for (f in sized) {
                arr.put(
                    org.json.JSONObject()
                        .put("file_name", f.name)
                        .put("size", f.size)
                        .put("mime", f.mime)
                )
            }
            val payload = org.json.JSONObject()
                .put("files", arr)
                .put("status", "sending")
                .toString()
            val rowId = db.messageDao().insert(
                MessageEntity(
                    sessionId = sessionId,
                    role = ROLE_USER,
                    text = caption.orEmpty(),
                    at = at,
                    kind = kind,
                    payload = payload,
                )
            )
            val brief = if (sized.size == 1) sized[0].name else "${sized.size} 个文件"
            db.sessionDao().touch(sessionId, "[附件] " + brief, at, STATUS_RUNNING, 0)
            nameFromFirstMessage(s, caption?.takeIf { it.isNotBlank() } ?: "[附件] $brief")

            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                // 长度未知 → OkHttp 用 chunked，hub 端一次读完再解析帧
                override fun contentLength(): Long = -1L
                override fun writeTo(sink: okio.BufferedSink) {
                    for (f in sized) {
                        val nm = f.name.toByteArray()
                        val mm = f.mime.toByteArray()
                        sink.writeInt(nm.size); sink.write(nm)
                        sink.writeInt(mm.size); sink.write(mm)
                        sink.writeLong(f.size)
                        val ins = openStream(f.uri) ?: throw java.io.IOException("读不到 ${f.name}")
                        ins.use {
                            val buf = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val n = it.read(buf)
                                if (n <= 0) break
                                sink.write(buf, 0, n)
                                written += n
                            }
                            if (written != f.size) throw java.io.IOException("${f.name} 读到的字节数不对")
                        }
                    }
                }
            }
            val r = runCatching { client.attachMulti(s.agentId, s.name, caption, sized.size, body) }
            r.onSuccess { msgId ->
                db.messageDao().setPayload(
                    rowId,
                    org.json.JSONObject(payload).put("status", "sent").put("msgId", msgId).toString(),
                )
            }.onFailure {
                db.messageDao().setPayload(
                    rowId,
                    org.json.JSONObject(payload).put("status", "failed").put("error", it.message ?: "").toString(),
                )
                db.sessionDao().setStatus(sessionId, STATUS_IDLE)
                _errors.update { m -> m + (s.agentId to "附件发送失败：${it.message}") }
            }
            r
        }

    /** 单个附件（调试注入、旧调用） */
    suspend fun sendFile(sessionId: Long, uri: android.net.Uri, caption: String? = null): Result<String> {
        val meta = describeFile(uri)
        return sendFiles(sessionId, listOf(AttachItem(uri, meta.name, meta.mime, meta.size)), caption)
    }

    private fun openStream(uri: android.net.Uri): java.io.InputStream? =
        runCatching { appCtx.contentResolver.openInputStream(uri) }.getOrNull()

    private fun queryDisplayName(cr: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    private fun saveLocal(bytes: ByteArray, name: String): String {
        val dir = java.io.File(appCtx.filesDir, "attach").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\w\u4e00-\u9fa5.\\-]"), "_").take(60)
        val f = java.io.File(dir, System.currentTimeMillis().toString() + "_" + safe)
        runCatching { f.writeBytes(bytes) }
        return f.absolutePath
    }

    companion object {
        const val DEFAULT_SESSION_TITLE = "新会话"
        const val MAX_ATTACH = 20 * 1024 * 1024
        const val MAX_FILES = 10                                  // 一次最多 10 个附件
        const val MAX_ATTACH_TOTAL = 40L * 1024 * 1024            // 合计上限 40MB
    }

    /** 全双工：流式朗读，chunk 到达就写 AudioTrack；onChunk 返回 false = 被打断 */
    suspend fun speakStream(text: String, onChunk: (ByteArray) -> Boolean): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var bytes = 0
            client.ttsStream(text).collect { chunk ->
                if (!onChunk(chunk)) throw kotlinx.coroutines.CancellationException("播放被中断")
                bytes += chunk.size
            }
            bytes
        }
    }

    /**
     * 对话模式：发一句并等这条会话的下一条回答。
     * 必须先挂监听再发 —— events 是 replay=0 的 SharedFlow，发完再订阅会漏掉快速回复。
     * 超时给到 10 分钟：让长任务有说完的机会；真超时了也不丢——答案回来时会走 SSE 补投入库。
     */
    suspend fun askAndWait(sessionId: Long, text: String, timeoutMs: Long = 600_000): String? =
        withContext(Dispatchers.IO) {
            val s = db.sessionDao().get(sessionId) ?: return@withContext null
            val agentId = s.agentId
            val name = s.name
            val done = kotlinx.coroutines.CompletableDeferred<String>()
            val job = scope.launch {
                events.collect { ev ->
                    val t = when (ev) {
                        is HubEvent.Done -> if (ev.agentId == agentId && (ev.sessionName ?: "") == name) ev.text else null
                        is HubEvent.Push -> if (ev.agentId == agentId && (ev.sessionName ?: "") == name) ev.text else null
                        else -> null
                    }
                    if (!t.isNullOrBlank() && !done.isCompleted) done.complete(t)
                }
            }
            try {
                if (send(sessionId, text, fromVoice = true).isFailure) return@withContext null
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() }
            } finally {
                job.cancel()
            }
        }

    /** 语音 → 文本（按住说话）。失败原因直接进错误桶，UI 会看到 */
    suspend fun transcribe(wav: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching { client.asr(wav).text }
            .onFailure { logTranscribeFail(it) }
    }

    private fun logTranscribeFail(e: Throwable) {
        _globalError.value = "语音转文字失败：${e.message}"
    }

    /** 文本 → wav（朗读一条回复） */
    suspend fun speak(text: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching { client.tts(text) }
            .onFailure { _globalError.value = "朗读失败：${it.message}" }
    }

    fun clearGlobalError() {
        _globalError.value = null
    }

    fun markRead(sessionId: Long) {
        scope.launch { db.sessionDao().clearUnread(sessionId) }
    }

    /** 卡片按钮 / 权限按钮回传 */
    suspend fun sendCardAction(sessionId: Long, action: String, replyCtx: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            val s = db.sessionDao().get(sessionId) ?: return@withContext Result.failure(IllegalStateException("会话不存在"))
            val r = runCatching { client.cardAction(s.agentId, s.name, action, replyCtx) }
            r.onSuccess { _errors.update { it + (s.agentId to null) } }
                .onFailure { err -> _errors.update { it + (s.agentId to "回传失败：${err.message}") } }
            r
        }

    fun setViewing(sessionId: Long?) {
        viewingSessionId = sessionId
        if (sessionId != null) markRead(sessionId)
    }
}
