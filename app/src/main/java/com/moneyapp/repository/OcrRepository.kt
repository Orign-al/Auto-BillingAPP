package com.moneyapp.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.moneyapp.data.SettingsRepository
import com.moneyapp.db.AccountEntity
import com.moneyapp.db.AppDatabase
import com.moneyapp.db.CategoryEntity
import com.moneyapp.db.OcrRecord
import com.moneyapp.db.TagEntity
import com.moneyapp.parser.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

class OcrRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val parser = ReceiptParser()
    private val http = OkHttpClient()
    private val settingsRepository = SettingsRepository(context)

    fun observeRecords(): Flow<List<OcrRecord>> = db.ocrRecordDao().observeAll()
    fun observeAccounts(): Flow<List<AccountEntity>> = db.accountDao().observeAll()
    fun observeCategories(): Flow<List<CategoryEntity>> = db.categoryDao().observeAll()
    fun observeTags(): Flow<List<TagEntity>> = db.tagDao().observeAll()

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
            tagId = null,
            comment = null,
            uploadStatus = "draft"
        )
        db.ocrRecordDao().insert(record)
    }

    suspend fun updateRecord(record: OcrRecord) {
        db.ocrRecordDao().update(record)
    }

    suspend fun syncMetadata(): Boolean {
        val settings = settingsRepository.settingsFlow.first()
        if (settings.host.isBlank() || settings.token.isBlank()) {
            Log.w(TAG, "Missing host or token")
            return false
        }

        val base = settings.host.trimEnd('/')
        val accountsJson = getJson("$base/api/v1/accounts/list.json", settings.token) ?: return false
        val categoriesJson = getJson("$base/api/v1/transaction/categories/list.json", settings.token)
            ?: return false
        val tagsJson = getJson("$base/api/v1/transaction/tags/list.json", settings.token)
            ?: return false

        val accounts = parseAccounts(accountsJson)
        val categories = parseCategories(categoriesJson)
        val tags = parseTags(tagsJson)

        db.withTransaction {
            db.accountDao().clear()
            db.accountDao().insertAll(accounts)
            db.categoryDao().clear()
            db.categoryDao().insertAll(categories)
            db.tagDao().clear()
            db.tagDao().insertAll(tags)
        }

        return true
    }

    suspend fun uploadRecord(record: OcrRecord): Boolean {
        val settings = settingsRepository.settingsFlow.first()

        if (settings.host.isBlank() || settings.token.isBlank()) {
            Log.w(TAG, "Missing host or token")
            return false
        }

        val url = settings.host.trimEnd('/') + "/api/v1/transactions/add.json"
        val timezoneOffset = TimeZone.getDefault().rawOffset / 60000
        val resolvedTagId = record.tagId ?: resolveTagId(record.platform)
        val payload = JSONObject().apply {
            put("type", 3)
            put("categoryId", record.categoryId ?: settings.defaultCategoryId)
            put("time", record.payTime ?: System.currentTimeMillis() / 1000)
            put("utcOffset", timezoneOffset)
            put("sourceAccountId", record.accountId ?: settings.defaultAccountId)
            put("sourceAmount", record.amountMinor ?: 0)
            put("comment", record.comment ?: record.merchant ?: "")
            resolvedTagId?.let { put("tagIds", JSONArray(listOf(it))) }
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

    private suspend fun getJson(url: String, token: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        }
    }

    private fun parseAccounts(json: JSONObject): List<AccountEntity> {
        val result = json.optJSONArray("result") ?: JSONArray()
        val list = mutableListOf<AccountEntity>()
        for (i in 0 until result.length()) {
            val obj = result.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val category = obj.optInt("category")
            val parentId = obj.optString("parentId").ifBlank { null }
            if (id.isNotBlank()) {
                list.add(AccountEntity(id = id, name = name, category = category, parentId = parentId))
            }
        }
        return list
    }

    private fun parseCategories(json: JSONObject): List<CategoryEntity> {
        val result = json.optJSONObject("result") ?: JSONObject()
        val list = mutableListOf<CategoryEntity>()
        val keys = result.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = result.optJSONArray(key) ?: continue
            val typeFromKey = key.toIntOrNull() ?: 0
            parseCategoryArray(array, typeFromKey, list)
        }
        return list
    }

    private fun parseTags(json: JSONObject): List<TagEntity> {
        val result = json.optJSONArray("result") ?: JSONArray()
        val list = mutableListOf<TagEntity>()
        for (i in 0 until result.length()) {
            val obj = result.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val groupId = obj.optString("groupId").ifBlank { null }
            if (id.isNotBlank()) {
                list.add(TagEntity(id = id, name = name, groupId = groupId))
            }
        }
        return list
    }

    private suspend fun resolveTagId(platform: String?): String? {
        val name = when (platform?.lowercase()) {
            "wechat" -> listOf("微信支付", "微信", "WeChat Pay", "WeChat")
            "alipay" -> listOf("支付宝", "Alipay")
            else -> emptyList()
        }
        for (candidate in name) {
            val tag = db.tagDao().findByName(candidate)
            if (tag != null) return tag.id
        }
        return null
    }

    private fun parseCategoryArray(array: JSONArray, typeFallback: Int, list: MutableList<CategoryEntity>) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val type = obj.optInt("type", typeFallback)
            val parentId = obj.optString("parentId").ifBlank { null }
            if (id.isNotBlank()) {
                list.add(CategoryEntity(id = id, name = name, type = type, parentId = parentId))
            }
            val subs = obj.optJSONArray("subCategories")
            if (subs != null) {
                parseCategoryArray(subs, type, list)
            }
        }
    }

    companion object {
        private const val TAG = "OcrRepository"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
