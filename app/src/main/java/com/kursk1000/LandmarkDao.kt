package com.kursk1000

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LandmarkDao {
    @Query("SELECT * FROM landmarks")
    abstract fun observeAll(): Flow<List<LandmarkEntity>>

    @Transaction
    open suspend fun replaceAll(items: List<LandmarkEntity>) {
        clear()
        upsert(items)
    }

    @Upsert
    abstract suspend fun upsert(items: List<LandmarkEntity>)

    @Query("DELETE FROM landmarks")
    abstract suspend fun clear()
}
