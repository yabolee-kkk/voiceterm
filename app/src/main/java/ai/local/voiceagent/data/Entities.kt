package ai.local.voiceagent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val STATUS_IDLE = "idle"        // 空闲
const val STATUS_RUNNING = "running"  // 运行中
const val STATUS_WAITING = "waiting"  // 等你回话

const val ROLE_USER = "user"
const val ROLE_AGENT = "agent"

/** 消息种类：决定详情页用哪个渲染器 */
const val KIND_TEXT = "text"
const val KIND_CARD = "card"          // 卡片（六种元素）
const val KIND_BUTTONS = "buttons"    // 权限询问（二维按钮组）
const val KIND_IMAGE = "image"
const val KIND_FILE = "file"
const val KIND_AUDIO = "audio"
const val KIND_VOICE = "voice"        // 用户按住说话的语音消息（text 存转写文本）

/**
 * 会话 = (Agent, 命名)。name 为空串表示该 Agent 的主线会话。
 * 用空串而不是 NULL：SQLite 唯一索引把 NULL 当互不相等，会导致主线重复插入。
 */
@Entity(
    tableName = "sessions",
    indices = [Index(value = ["agentId", "name"], unique = true)],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String,
    val name: String = "",
    val title: String,
    val lastText: String = "",
    val lastAt: Long = 0,
    val unread: Int = 0,
    val status: String = STATUS_IDLE,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["sessionId"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val text: String,
    val at: Long,
    /** 用户消息 = 发送回执 msgId；Agent 消息 = replyCtx */
    val msgId: String? = null,
    /** text / card / buttons / image / file / audio */
    val kind: String = KIND_TEXT,
    /** 卡片或按钮的原始 JSON（kind != text 时使用） */
    val payload: String? = null,
    /** 卡片按钮回传时要用到的 reply_ctx */
    val replyCtx: String? = null,
)
