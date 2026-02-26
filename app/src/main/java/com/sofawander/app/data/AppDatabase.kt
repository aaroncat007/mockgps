package com.sofawander.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RouteEntity::class,
        FavoriteEntity::class,
        RunHistoryEntity::class,
        GpsEventEntity::class
    ],
    version = 3
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun runHistoryDao(): RunHistoryDao
    abstract fun gpsEventDao(): GpsEventDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "mockgps.db"
                            )
                            .fallbackToDestructiveMigration(true) // 允許在版本不符時重置資料庫，避免崩潰
                            .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
