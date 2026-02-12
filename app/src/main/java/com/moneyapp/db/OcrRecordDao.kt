package com.moneyapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: OcrRecord): Long

    @Update
    suspend fun update(record: OcrRecord)

    @Query("SELECT * FROM ocr_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OcrRecord>>

    @Query("SELECT * FROM ocr_records WHERE id = :id")
    suspend fun getById(id: Long): OcrRecord?
}
