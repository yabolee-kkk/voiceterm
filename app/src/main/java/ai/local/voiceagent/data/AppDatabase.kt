package ai.local.voiceagent.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        /** v1 → v2：M3 加了卡片/按钮/附件三种消息，需要 kind / payload / replyCtx */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE messages ADD COLUMN payload TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyCtx TEXT")
            }
        }
    }
}
