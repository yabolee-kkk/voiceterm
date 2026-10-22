package ai.local.voiceagent.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AgentInfo(
    val agentId: String,
    val ready: Boolean,
    val sent: Int = 0,
    val received: Int = 0,
)

data class SendAck(val msgId: String, val sessionKey: String)

data class AsrResult(val text: String, val engine: String)

class HubHttpException(val code: Int, msg: String) : IOException(msg)

/**
 * /v1 协议客户端（M1 定下的手机侧入口）。
 *
 *   GET  /v1/sessions                      Agent 列表与状态
 *   POST /v1/sessions/{agent}/message      发文本，202 返回 msgId
 *   GET  /v1/sessions/{agent}/events       SSE：typing / stream / done / push / card / buttons
 *
 * SSE 自带指数退避重连（1s→2s→4s→…→30s），协程取消即断开。
 */
class HubClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // SSE 是长连接，但绝不能无限等：hub 每 15s 发一次 ": hb" 心跳，
        // 45s 收不到任何字节就判定这条连接已死（否则拔网后半开连接会永久卡住，
        // 表现为界面一直"连接中…"、其实早就断了）。
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun path(p: String) = prefs.activeBaseUrl + p

    private fun req(p: String): Request.Builder =
        Request.Builder().url(path(p)).header("Authorization", "Bearer ${prefs.token}")

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    suspend fun agents(): List<AgentInfo> = withContext(Dispatchers.IO) {
        http.newCall(req("/v1/sessions").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "GET /v1/sessions → ${r.code} ${body.take(200)}")
            val arr: JSONArray = JSONObject(body).optJSONArray("sessions") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AgentInfo(
                    agentId = o.optString("agentId"),
                    ready = o.optBoolean("ready"),
                    sent = o.optInt("sent"),
                    received = o.optInt("received"),
                )
            }
        }
    }

    suspend fun health(): String = withContext(Dispatchers.IO) {
        http.newCall(req("/health").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "GET /health → ${r.code} ${body.take(200)}")
            body
        }
    }

    suspend fun send(agentId: String, text: String, name: String): SendAck = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("text", text)
            .put("name", name)
            .put("deviceId", prefs.deviceId)
        val body = payload.toString().toRequestBody(JSON)
        http.newCall(req("/v1/sessions/${enc(agentId)}/message").post(body).build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "POST message → ${r.code} ${s.take(200)}")
            val o = JSONObject(s)
            SendAck(o.optString("msgId"), o.optString("sessionKey"))
        }
    }

    /** 卡片/权限按钮回传。action 就上游给的原值（act: / cmd: / nav: / perm: 前缀） */
    suspend fun cardAction(agentId: String, name: String, action: String, replyCtx: String?): Unit =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("action", action)
                .put("replyCtx", replyCtx ?: "")
                .put("name", name)
            val body = payload.toString().toRequestBody(JSON)
            http.newCall(req("/v1/sessions/${enc(agentId)}/card_action").post(body).build()).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HubHttpException(r.code, "POST card_action → ${r.code} ${s.take(200)}")
            }
        }

    /** 语音条：wav 直接当 body，时长与命名走 query（cc-connect 会自己转写并回语音） */
    suspend fun sendVoice(agentId: String, name: String, wav: ByteArray, durationMs: Long): String =
        withContext(Dispatchers.IO) {
            val q = buildString {
                append("/v1/sessions/").append(urlEnc(agentId)).append("/voice?durationMs=").append(durationMs)
                if (name.isNotBlank()) append("&name=").append(urlEnc(name))
            }
            val body = wav.toRequestBody(WAV)
            http.newCall(req(q).post(body).build()).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HubHttpException(r.code, "POST /voice → ${r.code} ${s.take(200)}")
                JSONObject(s).optString("msgId")
            }
        }

    private fun urlEnc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** 语音 → 文本（按住说话）。wav 直接当 body 传，hub 侧走 MiMo ASR，失败回落本地 whisper */
    suspend fun asr(wav: ByteArray): AsrResult = withContext(Dispatchers.IO) {
        val body = wav.toRequestBody(WAV)
        http.newCall(req("/v1/asr").post(body).build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "POST /v1/asr → ${r.code} ${s.take(200)}")
            val o = JSONObject(s)
            AsrResult(o.optString("text"), o.optString("engine", "?"))
        }
    }

    /** 文本 → wav（朗读一条回复） */
    suspend fun tts(text: String, voice: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("text", text)
        if (!voice.isNullOrBlank()) payload.put("voice", voice)
        val body = payload.toString().toRequestBody(JSON)
        http.newCall(req("/v1/tts").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) {
                val s = r.body?.string().orEmpty()
                throw HubHttpException(r.code, "POST /v1/tts → ${r.code} ${s.take(200)}")
            }
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 发文件/图片：body 是裸字节，query 带 filename / mime / 会话名 / 说明 */
    suspend fun attach(
        agentId: String,
        name: String?,
        filename: String,
        mime: String,
        bytes: ByteArray,
        text: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val q = buildString {
            append("/v1/sessions/").append(enc(agentId)).append("/attach?filename=").append(enc(filename))
            append("&mime=").append(enc(mime))
            if (!name.isNullOrBlank()) append("&name=").append(enc(name))
            if (!text.isNullOrBlank()) append("&text=").append(enc(text))
        }
        val mt = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        val body = bytes.toRequestBody(mt)
        http.newCall(req(q).post(body).build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "POST attach → ${r.code} ${s.take(200)}")
            JSONObject(s).optString("msgId")
        }
    }

    /** 多附件（帧拼接，流式写，不在手机内存里堆整包）：count=文件数 */
    suspend fun attachMulti(
        agentId: String,
        name: String?,
        caption: String?,
        count: Int,
        body: okhttp3.RequestBody,
    ): String = withContext(Dispatchers.IO) {
        val q = buildString {
            append("/v1/sessions/").append(enc(agentId)).append("/attach?count=").append(count)
            if (!name.isNullOrBlank()) append("&name=").append(enc(name))
            if (!caption.isNullOrBlank()) append("&text=").append(enc(caption))
        }
        http.newCall(req(q).post(body).build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HubHttpException(r.code, "POST attach(count=$count) → ${r.code} ${s.take(200)}")
            JSONObject(s).optString("msgId")
        }
    }

    /** 流式朗读：hub 边合成边推 PCM16(24k 单声道)，这里按到达顺序吐 chunk */
    fun ttsStream(text: String): Flow<ByteArray> = flow {
        val payload = JSONObject().put("text", text).toString().toRequestBody(JSON)
        val call = http.newCall(req("/v1/tts/stream").post(payload).build())
        call.execute().use { r ->
            if (!r.isSuccessful) throw HubHttpException(r.code, "POST /v1/tts/stream → ${r.code}")
            val src = r.body?.source() ?: return@flow
            while (currentCoroutineContext().isActive) {
                // 流结束时 OkHttp 有时抛 EOFException 而不是返回 null，按正常收尾处理
                val chunk = try {
                    src.readByteArray(4096)
                } catch (e: java.io.IOException) {
                    null
                } ?: break
                if (chunk.isNotEmpty()) emit(chunk)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 单 Agent 的事件流；内部无限重连，只有取消才结束。 */
    fun events(agentId: String): Flow<HubEvent> = flow {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            emit(HubEvent.Conn(agentId, ConnState.CONNECTING))
            try {
                val request = req("/v1/sessions/${enc(agentId)}/events")
                    .get()
                    .header("Accept", "text/event-stream")
                    .build()
                http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw HubHttpException(r.code, "SSE → ${r.code}")
                    val body = r.body ?: throw IOException("SSE 无响应体")
                    backoff = 1_000L
                    Log.i(TAG, "[$agentId] SSE 已建立")
                    emit(HubEvent.Conn(agentId, ConnState.OPEN))
                    val src = body.source()
                    var event = ""
                    val data = StringBuilder()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    parse(agentId, event, data.toString())?.let { emit(it) }
                                }
                                event = ""
                                data.setLength(0)
                            }
                            line.startsWith(":") -> Unit          // 心跳注释
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trim())
                            }
                        }
                    }
                }
                emit(HubEvent.Conn(agentId, ConnState.RETRYING, "服务端关闭连接"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "[$agentId] SSE 断开：${e.message}")
                emit(HubEvent.Conn(agentId, ConnState.RETRYING, e.message ?: e.javaClass.simpleName))
            }
            delay(backoff)
            // 上限 15s：hub 心跳也是 15s，断网恢复后不该再等 30s 才接回
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(agentId: String, event: String, data: String): HubEvent? {
        val o = try { JSONObject(data) } catch (e: Exception) { return null }
        val name = if (o.isNull("sessionName")) null else o.optString("sessionName")
        val ctx = if (o.isNull("replyCtx")) null else o.optString("replyCtx")
        return when (event) {
            "typing" -> HubEvent.Typing(agentId, name, o.optString("state") == "on")
            "stream" -> HubEvent.Stream(
                agentId = agentId, sessionName = name, replyCtx = ctx,
                text = o.optString("text"), done = o.optBoolean("done"), kind = o.optString("kind"),
            )
            "done" -> HubEvent.Done(agentId, name, ctx, o.optString("text"))
            "push" -> HubEvent.Push(agentId, name, ctx, o.optString("text"), o.optString("reason"))
            "card" -> HubEvent.Card(
                agentId = agentId, sessionName = name, replyCtx = ctx,
                cardJson = o.optJSONObject("card")?.toString() ?: "{}",
            )
            "buttons" -> HubEvent.Buttons(
                agentId = agentId, sessionName = name, replyCtx = ctx,
                content = o.optString("content").ifBlank { null },
                buttonsJson = o.optJSONArray("buttons")?.toString() ?: "[]",
            )
            "attachment" -> HubEvent.Attachment(
                agentId = agentId, sessionName = name,
                kind = o.optString("kind"),
                payloadJson = o.optJSONObject("payload")?.toString() ?: o.optString("payload"),
            )
            "clear_preview" -> HubEvent.ClearPreview(agentId, name, o.optString("handle").ifBlank { null })
            "notice" -> HubEvent.Notice(agentId, name, o.optString("text"))
            else -> HubEvent.Other(agentId, event, data)
        }
    }

    companion object {
        private const val TAG = "HubClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WAV = "audio/wav".toMediaType()
    }
}
