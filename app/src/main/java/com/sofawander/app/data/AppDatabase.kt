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
                INSTANCE ?: Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "mockgps.db"
                            ).fallbackToDestructiveMigration(false).build().also { INSTANCE = it }
            }
        }
    }
}
