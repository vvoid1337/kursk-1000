package com.kursk1000

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [LandmarkEntity::class], version = 1, exportSchema = false)
@TypeConverters(LandmarkConverters::class)
abstract class KurskDatabase : RoomDatabase() {
    abstract fun landmarkDao(): LandmarkDao
}
