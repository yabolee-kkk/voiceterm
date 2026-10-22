package ai.local.voiceagent.net

enum class ConnState { CONNECTING, OPEN, RETRYING, ERROR }

/** hub 推给手机的事件（字段与 hub/server.js 的 broadcast 一一对应）。 */
sealed interface HubEvent {
    val agentId: String

    data class Conn(
        override val agentId: String,
        val state: ConnState,
        val detail: String? = null,
    ) : HubEvent

    data class Typing(
        override val agentId: String,
        val sessionName: String?,
        val on: Boolean,
    ) : HubEvent

    data class Stream(
        override val agentId: String,
        val sessionName: String?,
        val replyCtx: String?,
        val text: String,
        val done: Boolean,
        val kind: String?,
    ) : HubEvent

    data class Done(
        override val agentId: String,
        val sessionName: String?,
        val replyCtx: String?,
        val text: String,
    ) : HubEvent

    /** 不属于本次请求的应答：cron 主动推送 / 断线补投 */
    data class Push(
        override val agentId: String,
        val sessionName: String?,
        val replyCtx: String?,
        val text: String,
        val reason: String?,
    ) : HubEvent

    /** 卡片：{"header":{...},"elements":[{type:markdown|divider|actions|list_item|select|note}]} */
    data class Card(
        override val agentId: String,
        val sessionName: String?,
        val replyCtx: String?,
        val cardJson: String,
    ) : HubEvent

    /** 权限询问：按钮是二维数组 [[{Text,Data}]] */
    data class Buttons(
        override val agentId: String,
        val sessionName: String?,
        val replyCtx: String?,
        val content: String?,
        val buttonsJson: String,
    ) : HubEvent

    /** image / file / audio 附件，payload 为上游原始帧 */
    data class Attachment(
        override val agentId: String,
        val sessionName: String?,
        val kind: String,
        val payloadJson: String,
    ) : HubEvent

    /** 上游的过程提示（🎙 正在转录语音消息 / 📬 任务已排队），不是回答 */
    data class Notice(
        override val agentId: String,
        val sessionName: String?,
        val text: String,
    ) : HubEvent

    /** 上游要求撤掉临时预览气泡 */
    data class ClearPreview(
        override val agentId: String,
        val sessionName: String?,
        val handle: String?,
    ) : HubEvent

    data class Other(
        override val agentId: String,
        val type: String,
        val raw: String,
    ) : HubEvent
}
