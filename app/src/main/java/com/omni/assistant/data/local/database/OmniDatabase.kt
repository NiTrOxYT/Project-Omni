package com.omni.assistant.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.omni.assistant.data.local.dao.ChatMessageDao
import com.omni.assistant.data.local.dao.TaskDao
import com.omni.assistant.data.local.entity.ChatMessageEntity
import com.omni.assistant.data.local.entity.TaskEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        TaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OmniDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun taskDao(): TaskDao

    companion object {
        private const val DATABASE_NAME = "omni_assistant.db"

        @Volatile
        private var INSTANCE: OmniDatabase? = null

        fun getInstance(context: Context): OmniDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
