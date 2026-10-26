package com.kirdev.kiromindbot.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kirdev.kiromindbot.feature.telegram.data.local.FileIdDao
import com.kirdev.kiromindbot.feature.protection.data.BlockedUserDao
import com.kirdev.kiromindbot.feature.protection.data.BlockedUserEntity
import com.kirdev.kiromindbot.feature.telegram.data.local.FileIdEntity
import com.kirdev.kiromindbot.feature.answers.data.local.AnswerDao
import com.kirdev.kiromindbot.feature.answers.data.local.AnswerEntity
import com.kirdev.kiromindbot.feature.answers.data.local.MediaEntity
import com.kirdev.kiromindbot.feature.answers.data.local.VariantEntity
import com.kirdev.kiromindbot.feature.learning.data.local.UnknownQuestionDao
import com.kirdev.kiromindbot.feature.learning.data.local.UnknownQuestionEntity

/**
 * Local KiroMind storage. Schema history is exported to app/schemas and checked by
 * migration tests. Every version bump needs an explicit [Migration] in [MIGRATIONS]:
 * there is deliberately no destructive fallback, user answers must never be wiped silently.
 */
@Database(
    entities = [AnswerEntity::class, VariantEntity::class, MediaEntity::class, UnknownQuestionEntity::class, FileIdEntity::class, BlockedUserEntity::class],
    version = KiroMindDatabase.VERSION,
    exportSchema = true
)
abstract class KiroMindDatabase : RoomDatabase() {
    abstract fun answers(): AnswerDao
    abstract fun unknownQuestions(): UnknownQuestionDao
    abstract fun telegramFiles(): FileIdDao
    abstract fun blockedUsers(): BlockedUserDao

    companion object {
        const val VERSION = 3
        const val NAME = "kiromind.db"

        /** v2: cache of Telegram file_ids for uploaded local files. Existing data is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `telegram_file_cache` (" +
                        "`source` TEXT NOT NULL, `method` TEXT NOT NULL, `file_id` TEXT NOT NULL, `cached_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`source`, `method`))"
                )
            }
        }

        /** v3: Telegram blacklist. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blocked_users` (" +
                        "`user_id` INTEGER NOT NULL, `reason` TEXT NOT NULL, `blocked_at` INTEGER NOT NULL, `until` INTEGER, " +
                        "PRIMARY KEY(`user_id`))"
                )
            }
        }

        /** Every schema version needs an explicit step here. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun create(context: Context): KiroMindDatabase =
            Room.databaseBuilder(context.applicationContext, KiroMindDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
