package ai.local.voiceagent.ui.detail

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.local.voiceagent.App
import ai.local.voiceagent.data.AppDatabase
import ai.local.voiceagent.data.HubEngine
import ai.local.voiceagent.data.KIND_AUDIO
import ai.local.voiceagent.data.KIND_BUTTONS
import ai.local.voiceagent.data.KIND_CARD
import ai.local.voiceagent.data.KIND_FILE
import ai.local.voiceagent.data.KIND_IMAGE
import ai.local.voiceagent.data.KIND_VOICE
import ai.local.voiceagent.data.MessageEntity
import ai.local.voiceagent.data.ROLE_USER
import ai.local.voiceagent.data.SessionEntity
import ai.local.voiceagent.net.ConnState
import ai.local.voiceagent.net.HubEvent
import ai.local.voiceagent.ui.card.AgentCard
import ai.local.voiceagent.ui.card.AttachmentView
import ai.local.voiceagent.ui.card.PermissionButtons
import ai.local.voiceagent.ui.markdown.MarkdownText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ai.local.voiceagent.voice.DialogRecorder
import ai.local.voiceagent.voice.PcmStreamPlayer
import ai.local.voiceagent.voice.VoiceRecorder
import ai.local.voiceagent.voice.VoiceStore
import ai.local.voiceagent.voice.WavPlayer

data class ChatUi(
    val title: String = "",
    val messages: List<MessageEntity> = emptyList(),
    /** 正在流式增长的文本（未落库） */
    val streaming: String? = null,
    val busy: Boolean = false,
    val conn: ConnState? = null,
    val error: String? = null,
)

/**
 * 中文输入法（Gboard 中文、搜狗等）会把半角 "/" 打成全角 "／"，cc-connect 只认半角。
 * 一句话就是要发命令的时候（开头是斜杠），开头这个斜杠统一换成半角。
 */
internal fun normalizeCommand(text: String): String {
    if (text.isEmpty()) return text
    val t = text.trimStart()
    if (t.startsWith("／")) return "/" + t.removePrefix("／")
    return text
}

class ChatViewModel(
    private val engine: HubEngine,
    private val db: AppDatabase,
    private val sessionId: Long,
) : ViewModel() {

    private data class Base(val session: SessionEntity?, val messages: List<MessageEntity>)
    private data class Live(val streaming: String?, val busy: Boolean, val connMap: Map<String, ConnState>, val errors: Map<String, String?>)

    private val streaming = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private var throttleJob: Job? = null
    private var lastEmitAt = 0L
    private var session: SessionEntity? = null

    private val base = combine(
        db.sessionDao().observe(sessionId),
        db.messageDao().observe(sessionId),
    ) { s, m -> Base(s, m) }

    private val live = combine(streaming, busy, engine.conn, engine.errors) { st, b, c, errs ->
        Live(st, b, c, errs)
    }

    val state: StateFlow<ChatUi> = combine(base, live) { b, l ->
        session = b.session
        ChatUi(
            title = b.session?.title ?: "会话",
            messages = b.messages,
            streaming = l.streaming,
            busy = l.busy,
            conn = b.session?.let { l.connMap[it.agentId] },
            error = b.session?.let { l.errors[it.agentId] },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    init {
        viewModelScope.launch {
            engine.events.collect { e -> onEvent(e) }
        }
    }

    private fun onEvent(e: HubEvent) {
        val s = session ?: return
        val name = when (e) {
            is HubEvent.Typing -> e.sessionName
            is HubEvent.Stream -> e.sessionName
            is HubEvent.Done -> e.sessionName
            is HubEvent.Push -> e.sessionName
            else -> return
        }
        if (e.agentId != s.agentId || (name ?: "") != s.name) return   // 别的会话的事件不许串进来

        when (e) {
            is HubEvent.Typing -> busy.value = e.on

            is HubEvent.Stream -> {
                if (e.done) {
                    streaming.value = e.text
                } else {
                    busy.value = true
                    throttleStream(e.text)
                }
            }

            is HubEvent.Done -> {
                // 引擎已先落库，这里只负责撤掉流式临时气泡
                streaming.value = null
                busy.value = false
            }

            is HubEvent.Push -> {
                streaming.value = null
                busy.value = false
            }

            else -> Unit
        }
    }

    /** 节流：最多每 100ms 刷新一次 UI（delta 可能每几十毫秒来一片） */
    private fun throttleStream(text: String) {
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastEmitAt
        if (gap >= 100) {
            streaming.value = text
            lastEmitAt = now
            throttleJob?.cancel()
            throttleJob = null
        } else {
            throttleJob?.cancel()
            throttleJob = viewModelScope.launch {
                delay(100 - gap)
                streaming.value = text
                lastEmitAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun send(text: String, fromVoice: Boolean = false) {
        val t = normalizeCommand(text.trim())
        if (t.isEmpty()) return
        viewModelScope.launch {
            engine.send(sessionId, t, fromVoice)
        }
    }

    /** 调试用：把一条"Agent 发来的附件"直接写进当前会话 */
    fun injectAgentAttachment(kind: String, payload: String) {
        viewModelScope.launch {
            engine.injectAgentMessage(sessionId, kind, payload)
        }
    }

    /** 卡片按钮 / 权限按钮回传 */
    fun sendCardAction(action: String, replyCtx: String?) {
        if (action.isBlank()) return
        viewModelScope.launch {
            engine.sendCardAction(sessionId, action, replyCtx)
        }
    }

    // ---------------- 语音（M4） ----------------

    data class VoiceUi(
        val recording: Boolean = false,
        val recordMs: Long = 0,
        val peak: Int = 0,
        val transcribing: Boolean = false,
        val sending: Boolean = false,
        val playingId: Long? = null,
    )

    private val recorder = VoiceRecorder()
    private val player = WavPlayer()
    private val speakPlayer = PcmStreamPlayer()
    private val ctx: android.content.Context = App.appCtx
    private val _voice = MutableStateFlow(VoiceUi())
    val voice: StateFlow<VoiceUi> = _voice.asStateFlow()

    /** 转写结果：填进输入框让用户改，不自动发 */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    private var ticker: Job? = null

    fun startRecord() {
        if (_voice.value.recording) return
        try {
            recorder.start(viewModelScope)
        } catch (e: Exception) {
            _voice.value = _voice.value.copy(recording = false)
            viewModelScope.launch { _transcripts.emit("") }   // 触发一次，UI 会显示错误
            lastVoiceError = "录音启动失败：${e.message}"
            return
        }
        _voice.value = _voice.value.copy(recording = true, recordMs = 0, peak = 0)
        startLiveTranscribe()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (_voice.value.recording) {
                delay(120)
                val ms = System.currentTimeMillis() - recorder.startedAt
                _voice.value = _voice.value.copy(recordMs = ms, peak = recorder.peak)
                // 兜底：万一松手事件丢了，60 秒自动收口，不会一直录下去
                if (ms > 60_000) {
                    android.util.Log.w("ChatScreen", "录音超过 60 秒，自动停止")
                    stopRecordAndHold()
                }
            }
        }
    }

    @Volatile
    var lastVoiceError: String? = null
        private set

    fun stopRecordAndTranscribe() {
        if (!_voice.value.recording) return
        ticker?.cancel()
        viewModelScope.launch {
            val wav = try {
                recorder.stop()
            } catch (e: Exception) {
                null
            }
            if (wav == null) {
                _voice.value = _voice.value.copy(recording = false, recordMs = 0, peak = 0)
                _transcripts.tryEmit("")   // 太短：UI 提示"太短"
                return@launch
            }
            _voice.value = _voice.value.copy(recording = false, transcribing = true, recordMs = 0, peak = 0)
            engine.transcribe(wav)
                .onSuccess { t -> if (t.isNotBlank()) _transcripts.tryEmit(t) }
            _voice.value = _voice.value.copy(transcribing = false)
        }
    }

    /** 边说边转：录音中每 1.2 秒把"到目前为止"的音频送 ASR，结果实时刷到浮窗里 */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText
    private var liveJob: Job? = null
    // 边说边转：已经"定稿"的文字和对应的音频字节位置（分段增量，不重转整段）
    private val committedText = StringBuilder()
    private var committedBytes = 0
    private val LIVE_MAX_TAIL = 10 * VoiceRecorder.SAMPLE_RATE * 2   // 尾巴最多 10 秒
    private val LIVE_COMMIT_MIN = 6 * VoiceRecorder.SAMPLE_RATE * 2   // 找不到停顿就至少切 6 秒

    private fun startLiveTranscribe() {
        liveJob?.cancel()
        _liveText.value = ""
        committedText.setLength(0)
        committedBytes = 0
        // 公网线路：不边说边转。CF 每条请求首字节 1~3 秒，分段上传会把延迟叠成十几秒；
        // 改成松手后整段只转一次（committedBytes 保持 0，stopRecordAndHold 就会整段转）。
        if (!App.prefs.onLanRoute) return
        liveJob = viewModelScope.launch {
            var first = true
            while (_voice.value.recording) {
                delay(if (first) 800 else 1000)
                first = false
                if (!_voice.value.recording) break
                val pcm = recorder.snapshotPcm() ?: continue
                if (pcm.size <= committedBytes) continue
                val bps = VoiceRecorder.SAMPLE_RATE * 2          // 每秒字节数

                // 累积太长的部分先切一句"定下来"：否则每次都要重转整段，越说越慢
                while (pcm.size - committedBytes > LIVE_MAX_TAIL) {
                    val tailLen = pcm.size - committedBytes
                    val cut = findSilenceCut(pcm, committedBytes, minOf(tailLen, LIVE_MAX_TAIL))
                        ?: (committedBytes + LIVE_COMMIT_MIN)
                    if (cut - committedBytes < bps / 2) break
                    val seg = pcm.copyOfRange(committedBytes, cut)
                    val t = runCatching { engine.transcribe(recorder.wavOf(seg)) }.getOrNull()?.getOrNull()
                    committedText.append(t.orEmpty())
                    committedBytes = cut
                    _liveText.value = committedText.toString()
                }

                // 剩下的尾巴转一次，拼在已定稿后面实时显示
                val rest = pcm.copyOfRange(committedBytes, pcm.size)
                if (rest.size < bps / 2) continue
                val t = runCatching { engine.transcribe(recorder.wavOf(rest)) }.getOrNull()?.getOrNull()
                if (!t.isNullOrBlank()) _liveText.value = committedText.toString() + t
            }
        }
    }

    /**
     * 在 [from, from+len) 范围内从后往前找一个"安静点"当切分位置，
     * 优先切在停顿处，避免把一句话从中间切断导致断句错乱。找不到就返回 null。
     */
    private fun findSilenceCut(pcm: ByteArray, from: Int, len: Int): Int? {
        val bps = VoiceRecorder.SAMPLE_RATE * 2
        val winBytes = bps / 5                    // 200ms 一窗
        val end = (from + len).coerceAtMost(pcm.size)
        var p = end - winBytes
        val floor = from + bps / 2
        while (p > floor) {
            var peak = 0
            var i = p
            while (i + 1 < p + winBytes && i + 1 < pcm.size) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            if (peak < 700) return p + winBytes     // 这一窗很安静：切在它后面
            p -= winBytes
        }
        return null
    }

    /** 松手后先把这段录音"拿住"，弹窗让用户决定发语音还是转文字 */
    data class HeldVoice(val wav: ByteArray, val durMs: Long)

    private val _held = MutableStateFlow<HeldVoice?>(null)
    val held: StateFlow<HeldVoice?> = _held

    fun stopRecordAndHold() {
        if (!_voice.value.recording) return
        ticker?.cancel()
        liveJob?.cancel()
        viewModelScope.launch {
            val durMs = System.currentTimeMillis() - recorder.startedAt
            val wav = try {
                recorder.stop()
            } catch (e: Exception) {
                null
            }
            _voice.value = _voice.value.copy(recording = false, recordMs = 0, peak = 0)
            if (wav == null) {
                _transcripts.tryEmit("")      // 太短，UI 会提示
                return@launch
            }
            // 公网线路：committedBytes 一直是 0（没开边说边转），所以下面这一步就是"整段只转一次"。
            // 别提前弹浮窗——浮窗里的 ✓ 是"发送"按钮，输入框没字它点不动。
            // 定稿：已定稿的部分 + 剩下尾巴再转一次（局域网下省 3~5 秒）
            _voice.value = _voice.value.copy(transcribing = true)
            val pcmAll = recorder.lastPcm() ?: ByteArray(0)
            if (pcmAll.size > committedBytes) {
                val rest = pcmAll.copyOfRange(committedBytes, pcmAll.size)
                val t = runCatching { engine.transcribe(recorder.wavOf(rest)) }.getOrNull()?.getOrNull()
                if (!t.isNullOrBlank()) committedText.append(t)
            }
            if (committedText.isNotBlank()) _liveText.value = committedText.toString()
            _voice.value = _voice.value.copy(transcribing = false)
            _held.value = HeldVoice(wav, durMs)
        }
    }

    /** 选"发语音"：原样发语音条 */
    fun sendHeldAsVoice() {
        val h = _held.value ?: return
        _held.value = null
        viewModelScope.launch {
            _voice.value = _voice.value.copy(sending = true)
            engine.sendVoice(sessionId, h.wav, h.durMs)
            _voice.value = _voice.value.copy(sending = false)
        }
    }

    /** 选"转文字"：转写后填进输入框（可以说错了再改） */
    fun transcribeHeld() {
        val h = _held.value ?: return
        _held.value = null
        viewModelScope.launch {
            _voice.value = _voice.value.copy(transcribing = true)
            engine.transcribe(h.wav).onSuccess { t -> if (t.isNotBlank()) _transcripts.tryEmit(t) }
            _voice.value = _voice.value.copy(transcribing = false)
        }
    }

    fun discardHeld() {
        _held.value = null
        _liveText.value = ""
        committedText.setLength(0)
        committedBytes = 0
    }

    fun cancelRecord() {
        ticker?.cancel()
        recorder.cancel()
        _voice.value = _voice.value.copy(recording = false, recordMs = 0, peak = 0)
    }

    /** 语音条：松手直接发，不等转写（转写由 cc-connect 侧做，回执里会带文本） */
    fun stopRecordAndSendVoice() {
        if (!_voice.value.recording) return
        ticker?.cancel()
        viewModelScope.launch {
            val durMs = System.currentTimeMillis() - recorder.startedAt
            val wav = try {
                recorder.stop()
            } catch (e: Exception) {
                null
            }
            if (wav == null) {
                _voice.value = _voice.value.copy(recording = false, recordMs = 0, peak = 0)
                return@launch
            }
            _voice.value = _voice.value.copy(recording = false, recordMs = 0, peak = 0, sending = true)
            engine.sendVoice(sessionId, wav, durMs)
            _voice.value = _voice.value.copy(sending = false)
        }
    }

    /** 播放语音条（自己发的或 Agent 回的） */
    fun playVoice(msgId: Long, fileName: String) {
        if (_voice.value.playingId == msgId) {
            player.stop()
            _voice.value = _voice.value.copy(playingId = null)
            return
        }
        val bytes = VoiceStore.read(ctx, fileName)
        if (bytes == null) {
            _voice.value = _voice.value.copy(playingId = null)
            return
        }
        _voice.value = _voice.value.copy(playingId = msgId)
        player.play(bytes) {
            _voice.value = _voice.value.copy(playingId = null)
        }
    }

    /** 朗读一条回复：走流式 TTS（首包 ~0.5s），同一条再点一次 = 停止 */
    fun speak(msgId: Long, text: String) {
        if (text.isBlank()) return
        if (_voice.value.playingId == msgId) {
            speakPlayer.stop()
            _voice.value = _voice.value.copy(playingId = null)
            return
        }
        viewModelScope.launch {
            _voice.value = _voice.value.copy(playingId = msgId)
            speakPlayer.start()
            val r = engine.speakStream(text) { chunk -> speakPlayer.write(chunk) }
            speakPlayer.finish()
            if (r.isFailure) {
                _voice.value = _voice.value.copy(playingId = null)
            } else if (_voice.value.playingId == msgId) {
                _voice.value = _voice.value.copy(playingId = null)
            }
        }
    }

    fun stopSpeaking() {
        player.stop()
        speakPlayer.stop()
        _voice.value = _voice.value.copy(playingId = null)
    }

    /** 调试用：把一段 wav 直接当成"用户刚说完的一句"塞进对话流程（真机没有声源时验链路） */
    fun debugInjectSegment(wav: ByteArray) {
        if (_dialog.value.state == DialogState.OFF) startDialog()
        onDialogSegment(wav, 0)
    }

    // ---------------- 全双工对话（M5） ----------------

    enum class DialogState { OFF, LISTENING, THINKING, SPEAKING }

    data class DialogUi(
        val state: DialogState = DialogState.OFF,
        val level: Int = 0,
        val note: String = "",
        val heard: String = "",
        val said: String = "",
    )

    private val dialogRecorder = DialogRecorder()
    private val streamPlayer = PcmStreamPlayer()
    private val _dialog = MutableStateFlow(DialogUi())
    val dialog: StateFlow<DialogUi> = _dialog.asStateFlow()
    private var turnJob: Job? = null
    private var audioMgr: android.media.AudioManager? = null
    private var lastHeard: String = ""
    private var lastHeardAt: Long = 0

    fun toggleDialog() {
        if (_dialog.value.state == DialogState.OFF) startDialog() else stopDialog()
    }

    fun startDialog(speakFirst: String? = null, speakIt: Boolean = true) {
        if (_dialog.value.state != DialogState.OFF) {
            // 已经在通话里：再"接听"一次就补说这句
            if (!speakFirst.isNullOrBlank()) {
                turnJob?.cancel()
                turnJob = viewModelScope.launch { speakTurn(speakFirst) }
            }
            return
        }
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        audioMgr = am
        try {
            am?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            am?.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
        // 播报期用更高门限：避免自己的喇叭声被当成"用户开口"而打断
        dialogRecorder.thresholdProvider = {
            if (_dialog.value.state == DialogState.SPEAKING) DialogRecorder.BARGE_THRESHOLD
            else DialogRecorder.DEFAULT_THRESHOLD
        }
        _dialog.value = DialogUi(DialogState.LISTENING, note = "请说话…")
        try {
            dialogRecorder.start(
                onSegment = { wav, dur -> onDialogSegment(wav, dur) },
                onLevel = { lv -> _dialog.value = _dialog.value.copy(level = lv) },
                onSpeechStart = {
                    if (_dialog.value.state == DialogState.SPEAKING) {
                        // 插话：立刻停播、取消这条 TTS，并解除播报期（否则这句会被当成回声丢掉）
                        android.util.Log.i("ChatScreen", "检测到插话，停止播报（level=${dialogRecorder.level}）")
                        turnJob?.cancel()
                        streamPlayer.stop()
                        dialogRecorder.exitPlayingPhase()
                        _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "请说话…")
                    }
                },
            )
        } catch (e: Exception) {
            restoreAudioRoute()
            _dialog.value = DialogUi(DialogState.OFF, note = "麦克风不可用：${e.message}")
            return
        }
        android.util.Log.i("ChatScreen", "通话已开始 state=${_dialog.value.state} 开场白=${speakFirst?.take(20)} 要念=$speakIt")
        if (!speakFirst.isNullOrBlank()) {
            if (speakIt) {
                // 手动进通话/需要它先开口：念出来
                turnJob = viewModelScope.launch { speakTurn(speakFirst) }
            } else {
                // 来电通报已经念过了：只把来意显示在面板上，不重复出声
                _dialog.value = _dialog.value.copy(said = speakFirst.take(80))
            }
        }
    }

    fun stopDialog() {
        turnJob?.cancel()
        turnJob = null
        streamPlayer.stop()
        dialogRecorder.stop()
        restoreAudioRoute()
        _dialog.value = DialogUi(DialogState.OFF)
    }

    private fun restoreAudioRoute() {
        try {
            audioMgr?.mode = android.media.AudioManager.MODE_NORMAL
            audioMgr?.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        audioMgr = null
    }

    /** 一句说完了：转写 → 发消息 → 等回答 → 流式播报 → 回到聆听 */
    private fun onDialogSegment(wav: ByteArray, durMs: Long) {
        val st = _dialog.value.state
        if (st == DialogState.THINKING) return          // 等回答期间不再接新句子，避免叠着发
        if (st == DialogState.SPEAKING) {
            turnJob?.cancel()
            streamPlayer.stop()
        }
        turnJob = viewModelScope.launch {
            _dialog.value = _dialog.value.copy(state = DialogState.THINKING, note = "听懂中…", heard = "", said = "", level = 0)
            val text = engine.transcribe(wav).getOrNull()?.trim().orEmpty()
            if (text.isBlank()) {
                _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "没听清，再说一次")
                return@launch
            }
            _dialog.value = _dialog.value.copy(heard = text, note = "等回答…")
            // 防自激兜底：20 秒内重复同一句当回声忽略
            if (text == lastHeard && System.currentTimeMillis() - lastHeardAt < 20_000) {
                _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "（重复，忽略）")
                return@launch
            }
            lastHeard = text
            lastHeardAt = System.currentTimeMillis()
            val reply = engine.askAndWait(sessionId, text)
            if (reply.isNullOrBlank()) {
                _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "没等到回答，继续说吧")
                return@launch
            }
            speakTurn(reply)
        }
    }

    /** 说一段话（流式播报），说完回到聆听。播放被打断/取消时也要把播报期关掉 */
    private suspend fun speakTurn(text: String) {
        _dialog.value = _dialog.value.copy(
            state = DialogState.SPEAKING,
            said = text.take(80),
            note = "回答中…（说话可打断）",
        )
        dialogRecorder.enterPlayingPhase()
        android.util.Log.i("ChatScreen", "开口说话：${text.take(24)}")
        try {
            streamPlayer.start()
            val r = engine.speakStream(text) { chunk -> streamPlayer.write(chunk) }
            android.util.Log.i("ChatScreen", "说完：${r.getOrNull() ?: -1}B 异常=${r.exceptionOrNull()?.javaClass?.simpleName}:${r.exceptionOrNull()?.message}")
            streamPlayer.finish()
        } finally {
            dialogRecorder.exitPlayingPhase()
        }
        if (_dialog.value.state == DialogState.SPEAKING) {
            _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "请说话…")
        }
    }

    /**
     * 通话中发文字：和说话等价（发出去 → 等回答 → 念出来）。
     * 用户的要求是"我可以语音回，也可以文字回"，所以两条路必须都能走。
     */
    fun sendInCall(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        turnJob?.cancel()
        streamPlayer.stop()
        turnJob = viewModelScope.launch {
            _dialog.value = _dialog.value.copy(state = DialogState.THINKING, note = "等回答…", heard = t, said = "", level = 0)
            val reply = engine.askAndWait(sessionId, t)
            if (reply.isNullOrBlank()) {
                _dialog.value = _dialog.value.copy(state = DialogState.LISTENING, note = "没等到回答，继续说吧")
                return@launch
            }
            speakTurn(reply)
        }
    }

    /**
     * 选好的附件先挂在这里（可多个，最多 10 个），等用户把"要它干什么"写进来再一起发。
     */
    data class PendingFile(
        val uri: android.net.Uri,
        val name: String,
        val size: Long,
        val mime: String,
    )

    private val _pendingFiles = MutableStateFlow<List<PendingFile>>(emptyList())
    val pendingFiles: StateFlow<List<PendingFile>> = _pendingFiles

    /** 📎 选中一个或多个文件：只取名字/大小/类型，不上传 */
    fun pickFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val add = ArrayList<PendingFile>()
            for (u in uris) {
                val m = runCatching { engine.describeFile(u) }.getOrNull() ?: continue
                add += PendingFile(u, m.name, m.size, m.mime)
            }
            _pendingFiles.update { cur ->
                (cur + add).distinctBy { it.uri.toString() }.take(HubEngine.MAX_FILES)
            }
            if (_pendingFiles.value.size >= HubEngine.MAX_FILES) {
                _dialog.value = _dialog.value.copy(
                    note = "最多带 ${HubEngine.MAX_FILES} 个文件（超出的没加进来）"
                )
            }
        }
    }

    fun removePendingFile(index: Int) {
        _pendingFiles.update { it.filterIndexed { i, _ -> i != index } }
    }

    fun clearPendingFiles() {
        _pendingFiles.value = emptyList()
    }

    /** 附件 + 这句话当一条发出去（hub 侧 content 放文字，附件跟在后面） */
    fun sendWithAttachments(text: String) {
        val files = _pendingFiles.value
        if (files.isEmpty()) return
        _pendingFiles.value = emptyList()
        viewModelScope.launch {
            val items = files.map { HubEngine.AttachItem(it.uri, it.name, it.mime, it.size) }
            runCatching { engine.sendFiles(sessionId, items, text.ifBlank { null }) }
                .onFailure { android.util.Log.w("ChatScreen", "附件发送失败：${it.message}") }
        }
    }

    /** 发文件/图片（不带说明的直发，保留给调试注入用） */
    fun sendFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val r = engine.sendFile(sessionId, uri)
            if (r.isFailure) {
                // 失败时给一条可读的错误，不用去翻服务端日志
                _dialog.value = _dialog.value.copy(note = "")
            }
        }
    }

    fun clearDialogNote() {
        _dialog.value = _dialog.value.copy(note = "")
    }

    fun attach() {
        engine.setViewing(sessionId)
        App.prefs.lastSessionId = sessionId
        android.util.Log.i("ChatScreen", "进入会话 id=$sessionId（已记住，划掉任务后点图标会回到这里）")
        // 进这条会话就把该 Agent 的通知撤掉、振铃停掉（"我来了，别响了"）
        ai.local.voiceagent.service.HubService.stopRing(ctx)
        viewModelScope.launch {
            App.db.sessionDao().get(sessionId)?.let {
                ai.local.voiceagent.notify.Notify.clear(ctx, "agent-${it.agentId}")
            }
        }
    }

    fun detach() {
        if (engine.viewingSessionId == sessionId) engine.setViewing(null)
    }

    override fun onCleared() {
        cancelRecord()
        stopSpeaking()
        stopDialog()
        detach()
        super.onCleared()
    }

    companion object {
        fun factory(sessionId: Long) = viewModelFactory {
            initializer { ChatViewModel(App.engine, App.db, sessionId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(sessionId: Long, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$sessionId", factory = ChatViewModel.factory(sessionId))
    val ui by vm.state.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()

    // 来电"接听"：进到这条会话就自动开始通话，Agent 先开口
    val pendingCall by App.pendingCall.collectAsState()
    LaunchedEffect(pendingCall, sessionId) {
        val pc = pendingCall ?: return@LaunchedEffect
        // 不是这条会话就不要消费掉：接听时要先等导航切到目标会话（否则会被旧屏幕吞掉）
        if (pc.sessionId != sessionId) return@LaunchedEffect
        App.pendingCall.value = null
        android.util.Log.i("ChatScreen", "收到接听 pc=${pc.sessionId} 本屏=${sessionId} speak=${pc.speak.take(20)}")
        // 通报已经在外面念过了，接听后不再重复念，直接把来意显示在面板上、进入聆听
        vm.startDialog(pc.speak.ifBlank { null }, speakIt = false)
    }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    // 发完就收起输入法：不然键盘占掉半个屏幕，消息只剩一小条能看
    fun hideInputBar() {
        runCatching { focusMgr.clearFocus() }
        runCatching { keyboard?.hide() }
    }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            vm.pickFiles(uris)
            // 选完直接把光标丢进输入框，方便马上写要求
            runCatching { inputFocus.requestFocus() }
        }
    }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val pendingFiles by vm.pendingFiles.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 调试用：adb broadcast 注入一段「假的流式进度」，用来验收滚动跟随与折叠行为。
    // 只有 debug 包会响应，正式包没有出口。
    var debugStream by remember { mutableStateOf<String?>(null) }
    val debugScope = androidx.compose.runtime.rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var inputFromVoice by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val configured = App.prefs.configured

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
    }

    // 调试钩子（仅 debuggable 包）：真机旁边没有声源时，用 adb 把一句 wav 注入对话流程
    val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    DisposableEffect(debuggable) {
        if (!debuggable) return@DisposableEffect onDispose { }
        val recv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                val wav: ByteArray? = try {
                    val b64 = intent?.getStringExtra("b64")
                    val path = intent?.getStringExtra("path")
                    when {
                        !b64.isNullOrBlank() -> android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        !path.isNullOrBlank() -> java.io.File(path).readBytes()
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
                if (wav != null && wav.isNotEmpty()) vm.debugInjectSegment(wav)
            }
        }
        // 调试注入：假装 Agent 发来一个附件（测"点开图片/文件"用）
        val attachRecv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val path = i?.getStringExtra("path") ?: return
                val kind = i.getStringExtra("kind") ?: "file"
                val f = java.io.File(path)
                if (!f.exists()) { android.util.Log.w("ChatScreen", "注入附件不存在：$path"); return }
                val payload = org.json.JSONObject()
                    .put("path", f.absolutePath)
                    .put("file_name", f.name)
                    .put("size", f.length())
                    .put("mime_type", i.getStringExtra("mime") ?: ai.local.voiceagent.ui.mimeOf(f.name))
                vm.injectAgentAttachment(kind, payload.toString())
                android.util.Log.i("ChatScreen", "注入 Agent 附件 $kind ${f.name}")
            }
        }
        // 调试注入：adb broadcast 若干路径（逗号分隔）→ 走真实的"多选附件"链路
        val pickRecv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val raw = i?.getStringExtra("paths") ?: return
                val uris = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    .map { android.net.Uri.fromFile(java.io.File(it)) }
                if (uris.isEmpty()) return
                android.util.Log.i("ChatScreen", "注入附件 ${uris.size} 个")
                vm.pickFiles(uris)
            }
        }
        // 调试注入：adb broadcast 一个本地文件路径 → 走真实的"发文件"链路
        val fileRecv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val path = i?.getStringExtra("path") ?: return
                val f = java.io.File(path)
                if (!f.exists()) {
                    android.util.Log.w("ChatScreen", "注入文件不存在：$path")
                    return
                }
                if (i.getBooleanExtra("debug", false) != true && !ai.local.voiceagent.BuildConfig.DEBUG) return
                android.util.Log.i("ChatScreen", "注入文件：${f.name} ${f.length()}B")
                vm.sendFile(android.net.Uri.fromFile(f))
            }
        }
        ContextCompat.registerReceiver(
            ctx, recv,
            android.content.IntentFilter("ai.local.voiceagent.DEBUG_SEGMENT"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            ctx, fileRecv,
            android.content.IntentFilter("ai.local.voiceagent.DEBUG_SEND_FILE"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            ctx, attachRecv,
            android.content.IntentFilter("ai.local.voiceagent.DEBUG_ADD_ATTACH"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            ctx, pickRecv,
            android.content.IntentFilter("ai.local.voiceagent.DEBUG_PICK_FILES"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        val streamRecv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                if (!ai.local.voiceagent.BuildConfig.DEBUG) return
                val lines = i?.getIntExtra("lines", 40) ?: 40
                val gap = i?.getLongExtra("gapMs", 150L) ?: 150L
                debugScope.launch {
                    debugStream = ""
                    for (n in 1..lines) {
                        debugStream = buildString {
                            append("⏳ Progress\n")
                            for (k in 1..n) append("🔧 工具 #$k: Bash\n")
                            append("第 $n 段：这是流式追加的进度文本，用来验证屏幕会不会自己往上拱。")
                        }
                        kotlinx.coroutines.delay(gap)
                    }
                    kotlinx.coroutines.delay(1500)
                    debugStream = null
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, streamRecv,
            android.content.IntentFilter("ai.local.voiceagent.DEBUG_INJECT_STREAM"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            try { ctx.unregisterReceiver(recv) } catch (_: Exception) {}
            try { ctx.unregisterReceiver(fileRecv) } catch (_: Exception) {}
            try { ctx.unregisterReceiver(pickRecv) } catch (_: Exception) {}
            try { ctx.unregisterReceiver(attachRecv) } catch (_: Exception) {}
            try { ctx.unregisterReceiver(streamRecv) } catch (_: Exception) {}
        }
    }

    // 转写结果直接填进输入框（可编辑、不自动发）
    LaunchedEffect(Unit) {
        vm.transcripts.collect { t ->
            if (t.isNotBlank()) {
                input = if (input.isBlank()) t else "$input $t"
                inputFromVoice = true
            }
        }
    }

    DisposableEffect(sessionId) {
        vm.attach()
        onDispose { vm.detach() }
    }

    val total = ui.messages.size + if (ui.streaming != null || debugStream != null) 1 else 0

    // ---- 滚动跟随（这一版改的就是这里）----
    // 旧写法：每来一段流式文本就 animateScrollToItem(last)，且无条件执行：
    //   · animate 每次都被下一段 chunk 打断 → 内容根本不往前走
    //   · 用户往上翻也会立刻被拽回底部
    // 新写法：贴底才跟随、瞬时滚动（不被打断）、往上翻就彻底不动，另给"回到最新"按钮
    var follow by remember { mutableStateOf(true) }
    var forceFollow by remember { mutableStateOf(0) }      // 自己发消息时 +1，强制回到底部
    // 只在"用户自己滚完/惯性停下"时更新跟随状态：
    // 内容变长会让 layoutInfo 短暂满足"贴底"，那样判断会把用户正在看的历史拽走
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(total, ui.streaming?.length, debugStream?.length, follow, forceFollow) {
        if (!follow) return@LaunchedEffect
        // 等新内容完成测量，再决定滚多少
        withFrameNanos { }
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        // scrollToItem 只把这条的顶边对齐视口顶 —— 最后一条比一屏还高时，
        // 新追加的文字就落在视口下面（用户看到的“藏在屏幕下角”）。先把它带进来：
        listState.scrollToItem(last)
        withFrameNanos { }
        // 再把视口下方还藏着的那部分补滚上，最终停在真正的底部
        val info = listState.layoutInfo
        val vi = info.visibleItemsInfo.firstOrNull { it.index == last } ?: return@LaunchedEffect
        val hidden = (vi.offset + vi.size) - info.viewportEndOffset
        if (hidden > 0) listState.scrollBy(hidden.toFloat())
    }

    // 松手之后：把最终转写填进输入框（可改），同时出现「取消 / ✓ / 发语音条」这条栏
    val heldVoice by vm.held.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    LaunchedEffect(heldVoice) {
        if (heldVoice != null && liveText.isNotBlank() && input.isBlank()) {
            input = liveText
            inputFromVoice = true
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                ui.conn == ConnState.OPEN && ui.busy -> "运行中…"
                                ui.conn == ConnState.OPEN -> "已连接"
                                ui.conn == ConnState.CONNECTING -> "连接中…"
                                ui.conn == ConnState.RETRYING -> "重连中…"
                                ui.conn == null -> "未连接"
                                else -> ui.conn?.name ?: ""
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    // 全双工通话：点一下进入连续对话，再点挂断
                    // 做成一枚彩色胶囊（原来只是一行 12sp 灰字，拇指很难点中、也看不见）
                    val inCall = dialog.state != ChatViewModel.DialogState.OFF
                    Surface(
                        onClick = { if (configured) vm.toggleDialog() },
                        enabled = configured,
                        shape = RoundedCornerShape(50),
                        color = if (inCall) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (inCall) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(end = 10.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = "通话",
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (inCall) "通话中" else "通话",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                if (dialog.state != ChatViewModel.DialogState.OFF) {
                    DialogPanel(dialog = dialog, onHangup = { vm.stopDialog() }, onSend = { vm.sendInCall(it) })
                } else {
                Column {
                // 选好但还没发的附件（可多选）：先挂这儿，写完要求再一起发
                if (pendingFiles.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pendingFiles.forEachIndexed { idx, pf ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (pf.mime.startsWith("image/")) "🖼" else "📄", fontSize = 13.sp)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (pf.name.length > 12) pf.name.take(11) + "…" else pf.name,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "✕",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { vm.removePendingFile(idx) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "${pendingFiles.size} 个待发送 · 写一句要求再一起发（最多 ${HubEngine.MAX_FILES} 个）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
                    )
                }
                // 刚说完这段：文字已经落在下面输入框里（改就在那儿改，别处不再有第二个输入框），
                // 这里只留两个动作 —— 丢掉这段录音 / 改发语音条。发送统一由下面的发送键负责。
                if (heldVoice != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🎤 已转成文字，可在下面改", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            input = ""
                            inputFromVoice = false
                            vm.discardHeld()
                            hideInputBar()
                        }) { Text("取消", fontSize = 12.sp) }
                        TextButton(onClick = {
                            input = ""
                            inputFromVoice = false
                            vm.sendHeldAsVoice()
                            hideInputBar()
                        }) { Text("🎤 发语音条", fontSize = 12.sp) }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 按住这块就录音（不再有麦克风按钮），松手后再选发语音/转文字
                    Box(
                        Modifier
                            .weight(1f)
                            // 别指望 awaitLongPressOrCancellation：输入框会把 DOWN 吃掉，它直接返回 null。
                            // 这里自己计时（协程 delay，静态手指也能触发），抬手在 Initial 阶段看，吃掉也能收到。
                            .pointerInput(configured) {
                                if (!configured) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                    )
                                    // 用 Handler 计时（协程 delay 在受限作用域里不被允许），450ms 后开始录音
                                    var started = false
                                    val hold = Runnable {
                                        started = true
                                        if (micGranted) vm.startRecord()
                                        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                        android.util.Log.i("ChatScreen", "长按成立，开始录音")
                                    }
                                    handler.postDelayed(hold, 450)
                                    while (true) {
                                        val ev = awaitPointerEvent(
                                            androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                        )
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (ch.changedToUpIgnoreConsumed()) break
                                    }
                                    handler.removeCallbacks(hold)
                                    if (started) {
                                        android.util.Log.i("ChatScreen", "松手，停止录音")
                                        vm.stopRecordAndHold()
                                    }
                                }
                            }
                    ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        placeholder = {
                            Text(
                                when {
                                    voice.recording -> "🎤 正在听… ${"%.1f".format(voice.recordMs / 1000f)}s，松手结束"
                                    voice.transcribing -> "正在转文字…"
                                    pendingFiles.isNotEmpty() -> "给这些文件说一句要做什么…"
                                    else -> "按住这里说话，或直接打字"
                                },
                                fontSize = 14.sp,
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                    )
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    IconButton(
                        onClick = {
                            val t = input
                            input = ""
                            val wasVoice = inputFromVoice
                            inputFromVoice = false
                            vm.discardHeld()          // 文字已经在输入框里发走了，这段录音和上面那条提示条一起清掉
                            follow = true
                            forceFollow += 1
                            if (pendingFiles.isNotEmpty()) vm.sendWithAttachments(t)   // 文字在前、附件在后，一条发出去
                            else vm.send(t, fromVoice = wasVoice)
                            hideInputBar()                                             // 发完收起键盘
                        },
                        enabled = input.isNotBlank() || pendingFiles.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "发送",
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    // 附件：照片、文档、音乐都给 Agent 发
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = configured,
                    ) {
                        Text("📎", fontSize = 18.sp)
                    }
                }
                }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ui.error?.let {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(ui.messages.size) { i ->
                    val m = ui.messages[i]
                    MessageRow(
                        msg = m,
                        playing = voice.playingId == m.id,
                        isLast = i == ui.messages.lastIndex,
                        onAction = { action -> vm.sendCardAction(action, m.replyCtx) },
                        onSpeak = { vm.speak(m.id, m.text) },
                        onPlayVoice = { file -> vm.playVoice(m.id, file) },
                    )
                }
                (ui.streaming ?: debugStream)?.let { text ->
                    item { AgentStreamingBubble(text.ifEmpty { "…" }) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
                // 用户往上翻（不跟随）时给一个明确的返回入口
                if (!follow) {
                    Text(
                        "↓ 回到最新",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                follow = true
                                forceFollow += 1
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
        // 录音浮层：必须留在同一个窗口里，否则松手的 UP 事件会被新窗口吃掉
        if (voice.recording) {
            // 气泡样式：跟输入框同一套底色 + 描边，尺寸跟着内容走（不铺满屏），底下带个小尾巴指向输入框
            val bubbleBg = MaterialTheme.colorScheme.surface
            val bubbleLine = MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
                    .wrapContentSize()
                    .drawBehind {
                        val shape = speechBubblePath(
                            w = size.width,
                            h = size.height,
                            radius = 16.dp.toPx(),
                            tailW = 16.dp.toPx(),
                            tailH = 9.dp.toPx(),
                        )
                        drawPath(shape, bubbleBg)
                        drawPath(shape, bubbleLine, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                    }
                    .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 14.dp + 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎤 正在听…", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    LevelBars(level = voice.peak, active = true, width = 140.dp)
                    Spacer(Modifier.height(8.dp))
                    // 边说边出字：这句话就是刚才说的话（说完松手可以改）
                    if (liveText.isNotBlank()) {
                        val liveScroll = rememberScrollState()
                        LaunchedEffect(liveText) {
                            // 越说越长：自动滚到底，始终看得到最新那句
                            liveScroll.scrollTo(liveScroll.maxValue)
                        }
                        Text(
                            liveText,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .heightIn(max = 200.dp)
                                .verticalScroll(liveScroll)
                                .padding(vertical = 4.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "%.1f 秒 · 松手结束".format(voice.recordMs / 1000f),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 圆角矩形 + 底部中间一个小三角，合成一个气泡轮廓（一次描边，接缝处不会多出一条线） */
private fun speechBubblePath(w: Float, h: Float, radius: Float, tailW: Float, tailH: Float): Path {
    val bodyH = (h - tailH).coerceAtLeast(radius * 2)
    val body = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f, top = 0f, right = w, bottom = bodyH,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
        )
    }
    val cx = w / 2f
    val tail = Path().apply {
        moveTo(cx - tailW / 2f, bodyH - 1f)
        lineTo(cx + tailW / 2f, bodyH - 1f)
        lineTo(cx, h)
        close()
    }
    return Path().apply { op(body, tail, PathOperation.Union) }
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    playing: Boolean,
    isLast: Boolean = false,
    onAction: (String) -> Unit,
    onSpeak: () -> Unit,
    onPlayVoice: (String) -> Unit,
) {
    if (msg.role == ROLE_USER) {
        if (msg.kind == KIND_VOICE && jsonField(msg.payload, "file") != null) {
            VoiceBubble(
                fileName = jsonField(msg.payload, "file"),
                durMs = jsonInt(msg.payload, "durMs"),
                own = true,
                playing = playing,
                onClick = { jsonField(msg.payload, "file")?.let(onPlayVoice) },
            )
        } else if (msg.kind == KIND_VOICE) {
            // 老数据：语音转出来的文字当初被存成了 voice（没有音频文件）→ 现在按文字显示
            UserBubble(msg.text, fromVoice = true)
        } else if (msg.kind == KIND_FILE || msg.kind == KIND_IMAGE) {
            // 我发出去的文件/图片：同一套附件条 + 发送状态（发送中/已发送/失败）
            Column {
                if (msg.text.isNotBlank()) UserBubble(msg.text)
                AttachmentView(msg.kind, msg.payload ?: "{}")
            }
        } else {
            UserBubble(msg.text, fromVoice = jsonBool(msg.payload, "fromVoice"))
        }
        return
    }
    when (msg.kind) {
        KIND_CARD -> AgentCard(msg.payload ?: "{}", onAction)
        KIND_BUTTONS -> PermissionButtons(
            buttonsJson = msg.payload ?: "[]",
            content = msg.text.takeIf { it.isNotBlank() && !it.startsWith("[") },
            onAction = onAction,
        )
        // Agent 回的语音（cc-connect TTS 推回来的 audio 帧）
        KIND_AUDIO -> {
            val file = jsonField(msg.payload, "file")
            if (file != null) {
                VoiceBubble(
                    fileName = file,
                    durMs = jsonInt(msg.payload, "durMs"),
                    own = false,
                    playing = playing,
                    onClick = { onPlayVoice(file) },
                )
            } else {
                AttachmentView(msg.kind, msg.payload ?: "{}")
            }
        }
        KIND_IMAGE, KIND_FILE -> AttachmentView(msg.kind, msg.payload ?: "{}")
        else -> Column {
            AgentBubble(msg.text, msg.id, liveDefault = isLast)
            SpeakRow(playing = playing, onClick = onSpeak)
        }
    }
}

private fun jsonField(payload: String?, key: String): String? = try {
    if (payload.isNullOrBlank()) null
    else org.json.JSONObject(payload).optString(key).ifBlank { null }
} catch (e: Exception) {
    null
}

private fun jsonInt(payload: String?, key: String): Int = try {
    if (payload.isNullOrBlank()) 0 else org.json.JSONObject(payload).optInt(key, 0)
} catch (e: Exception) {
    0
}

/** 语音条：时长条 + 播放/暂停（自己的靠右、Agent 的靠左） */
@Composable
private fun VoiceBubble(
    fileName: String?,
    durMs: Int,
    own: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val widthDp = (72 + (durMs / 1000) * 8).coerceAtMost(180).dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            Modifier
                .widthIn(min = widthDp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (own) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = fileName != null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (playing) "⏸" else "▶",
                fontSize = 15.sp,
                color = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (playing) "播放中…" else "语音",
                fontSize = 14.sp,
                color = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(durMs / 1000.0).let { "%.0f".format(it) }}s",
                fontSize = 11.sp,
                color = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单条朗读：点一下念这一条，再点一下停 */
@Composable
private fun SpeakRow(playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 2.dp, start = 4.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (playing) "⏹ 停止" else "🔊 朗读",
            fontSize = 11.sp,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 全双工通话面板：状态 + 电平条 + 文字输入 + 挂断 */
@Composable
private fun DialogPanel(dialog: ChatViewModel.DialogUi, onHangup: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val label = when (dialog.state) {
        ChatViewModel.DialogState.LISTENING -> "🎧 在听，直接说话"
        ChatViewModel.DialogState.THINKING -> "🧠 处理中…"
        ChatViewModel.DialogState.SPEAKING -> "🔊 回答中（说话可打断）"
        ChatViewModel.DialogState.OFF -> ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "挂断",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable(onClick = onHangup)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        LevelBars(level = dialog.level, active = dialog.state != ChatViewModel.DialogState.THINKING)
        if (dialog.heard.isNotBlank()) {
            Text("你说：${dialog.heard}", fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dialog.said.isNotBlank()) {
            Text("它说：${dialog.said}", fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dialog.note.isNotBlank() && dialog.note != label) {
            Text(dialog.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        // 文字回复通道：不想说话时直接打字，发出去后照样念回答
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("打字回复（也可以直接说话）", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        text = ""
                        onSend(t)
                    }
                },
                enabled = text.isNotBlank(),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "发送文字",
                    tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 电平条：level 是麦克风 RMS（0~8000 左右） */
@Composable
private fun LevelBars(level: Int, active: Boolean, width: androidx.compose.ui.unit.Dp? = null) {
    val norm = if (active) (level / 5000f).coerceIn(0f, 1f) else 0f
    Row(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(32) { i ->
            // 中间高、两边低，再叠加当前电平，看起来像语音波形
            val shape = 0.35f + 0.65f * kotlin.math.sin((i + 1) / 33.0 * Math.PI).toFloat()
            val h = (4 + 26 * shape * norm).dp
            Box(
                Modifier
                    .width(4.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (norm > 0.02f) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

/** 按住说话：按下开始录，松手结束并转写 */
@Composable
private fun MicButton(
    recording: Boolean,
    transcribing: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when {
                    recording -> MaterialTheme.colorScheme.error
                    transcribing -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                recording -> "●"
                transcribing -> "…"
                else -> "🎤"
            },
            fontSize = 18.sp,
            color = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun jsonBool(json: String?, key: String): Boolean = runCatching {
    org.json.JSONObject(json ?: "{}").optBoolean(key, false)
}.getOrDefault(false)

@Composable
private fun UserBubble(text: String, fromVoice: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = if (fromVoice) "🎤 $text" else text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AgentBubble(text: String, foldKey: Any, liveDefault: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        AgentContent(text, foldKey, liveDefault)
    }
}

/** 流式临时气泡：一条消息原地替换（M2 的好行为，M3 保留） */
@Composable
private fun AgentStreamingBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Column {
                // 正在跑的这条始终展开：用户要看着它长，而不是先点“展开”
                AgentContent(text, foldKey = "stream", liveDefault = true)
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.height(12.dp).widthIn(max = 12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Agent 正文：进度快照折叠成"工具调用"，最终回复走 Markdown。
 * codex 会把工具调用塞在进度快照里（"⏳ Progress / 🔧 工具 #1: Bash"），
 * 不折叠就会刷屏；cc-connect 的 bridge 协议本身没有独立的 tool 帧（已确认）。
 */
@Composable
private fun AgentContent(text: String, foldKey: Any, liveDefault: Boolean = false) {
    if (isProgressSnapshot(text)) ToolFold(text, foldKey, liveDefault) else MarkdownText(text)
}

private fun isProgressSnapshot(text: String): Boolean =
    text.contains("⏳") || Regex("工具\\s*#\\d+").containsMatchIn(text)

@Composable
private fun ToolFold(text: String, foldKey: Any, liveDefault: Boolean) {
    // 原来 remember(text)：流式每追加一段文本 key 就变，expanded 被重置回 false
    // → 展开看两眼又自己折起来。改成按消息身份记，默认值只在首次出现时生效。
    var expanded by remember(foldKey) { mutableStateOf(liveDefault) }
    val tools = remember(text) {
        Regex("工具\\s*#\\d+:\\s*([\\w./-]+)").findAll(text).map { it.groupValues[1] }.distinct().toList()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔧 工具调用", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                if (tools.isEmpty()) "进行中" else tools.joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            MarkdownText(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
