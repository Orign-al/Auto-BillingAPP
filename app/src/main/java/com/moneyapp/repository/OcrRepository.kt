package com.moneyapp.repository

import android.content.Context
import android.util.Log
import com.moneyapp.data.SettingsRepository
import com.moneyapp.db.AppDatabase
import com.moneyapp.db.OcrRecord
import com.moneyapp.parser.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TimeZone
class OcrRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val parser = ReceiptParser()
    private val http = OkHttpClient()
    private val settingsRepository = SettingsRepository(context)

    fun observeRecords(): Flow<List<OcrRecord>> = db.ocrRecordDao().observeAll()

    suspend fun addRecord(imageUri: String, displayName: String, rawText: String) {
        val parsed = parser.parse(rawText)
        val record = OcrRecord(
            imageUri = imageUri,
            displayName = displayName,
            rawText = rawText,
            createdAt = System.currentTimeMillis(),
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            merchant = parsed.merchant,
            payTime = parsed.payTime,
            payMethod = parsed.payMethod,
            cardTail = parsed.cardTail,
            categoryGuess = parsed.categoryGuess,
            platform = parsed.platform,
            status = parsed.status,
            orderId = parsed.orderId,
            itemName = parsed.itemName,
            accountId = null,
            categoryId = null,
            comment = null,
            uploadStatus = "draft"
        )
        db.ocrRecordDao().insert(record)
    }

    suspend fun updateRecord(record: OcrRecord) {
        db.ocrRecordDao().update(record)
    }

    suspend fun uploadRecord(record: OcrRecord): Boolean {
        val settings = settingsRepository.settingsFlow.first()

        if (settings.host.isBlank() || settings.token.isBlank()) {
            Log.w(TAG, "Missing host or token")
            return false
        }

        val url = settings.host.trimEnd('/') + "/api/v1/transactions/add.json"
        val timezoneOffset = TimeZone.getDefault().rawOffset / 60000
        val payload = JSONObject().apply {
            put("type", 3)
            put("categoryId", record.categoryId ?: settings.defaultCategoryId)
            put("time", record.payTime ?: System.currentTimeMillis() / 1000)
            put("utcOffset", timezoneOffset)
            put("sourceAccountId", record.accountId ?: settings.defaultAccountId)
            put("sourceAmount", record.amountMinor ?: 0)
            put("comment", record.comment ?: record.merchant ?: "")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.token}")
            .addHeader("X-Timezone-Offset", timezoneOffset.toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }
    }

    companion object {
        private const val TAG = "OcrRepository"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
