package com.moneyapp.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ocr_records")
data class OcrRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val displayName: String,
    val rawText: String,
    val createdAt: Long,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val payTime: Long?,
    val payMethod: String?,
    val cardTail: String?,
    val categoryGuess: String?,
    val platform: String?,
    val status: String?,
    val orderId: String?,
    val itemName: String?,
    val accountId: String?,
    val categoryId: String?,
    val comment: String?,
    val uploadStatus: String?
)
