package ai.local.voiceagent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY lastAt DESC, id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE agentId = :agentId AND name = :name LIMIT 1")
    suspend fun find(agentId: String, name: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE agentId = :agentId")
    suspend fun byAgent(agentId: String): List<SessionEntity>

    /** 某个 Agent 下的全部会话（新的在前）—— 首页点进 Agent 后看到的就是这个列表 */
    @Query("SELECT * FROM sessions WHERE agentId = :agentId ORDER BY lastAt DESC, id DESC")
    fun observeByAgent(agentId: String): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Insert
    suspend fun insert(s: SessionEntity): Long

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE sessions SET lastText = :text, lastAt = :at, status = :status, unread = unread + :unreadInc WHERE id = :id")
    suspend fun touch(id: Long, text: String, at: Long, status: String, unreadInc: Int)

    @Query("UPDATE sessions SET unread = 0 WHERE id = :id")
    suspend fun clearUnread(id: Long)

    /** 删会话（消息由 MessageDao.deleteBySession 一起清，表间没有外键级联） */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY at ASC, id ASC")
    fun observe(sessionId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Query("UPDATE messages SET msgId = :msgId WHERE id = :id")
    suspend fun setMsgId(id: Long, msgId: String)

    /** 同一条回复是否已经入库：断线重投时用来去重，避免同一条答案落两次 */
    @Query("SELECT COUNT(*) FROM messages WHERE msgId = :msgId AND role = 'agent'")
    suspend fun countAgentMsg(msgId: String): Int

    /** 这条回复是不是「我这台手机发出去的那条消息」引来的（多机同上下文时用来决定要不要响铃） */
    @Query("SELECT COUNT(*) FROM messages WHERE msgId = :msgId AND role = 'user'")
    suspend fun countMyMsg(msgId: String): Int

    /** 附件发送状态/回执：改 payload（里面带 status 和 msgId） */
    @Query("UPDATE messages SET payload = :payload WHERE id = :id")
    suspend fun setPayload(id: Long, payload: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
