package com.moneyapp.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.moneyapp.data.SettingsRepository
import com.moneyapp.db.AccountEntity
import com.moneyapp.db.AppDatabase
import com.moneyapp.db.CategoryEntity
import com.moneyapp.db.OcrRecord
import com.moneyapp.db.TagEntity
import com.moneyapp.parser.MerchantAliasDictionary
import com.moneyapp.parser.ReceiptParser
import com.moneyapp.worker.UploadRetryWorker
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
import kotlin.math.abs

class OcrRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val parser = ReceiptParser()
    private val http = OkHttpClient()
    private val settingsRepository = SettingsRepository(appContext)
    private val uploadPrefs = appContext.getSharedPreferences(PREF_UPLOAD_ERRORS, Context.MODE_PRIVATE)

    fun observeRecords(): Flow<List<OcrRecord>> = db.ocrRecordDao().observeAll()
    fun observeAccounts(): Flow<List<AccountEntity>> = db.accountDao().observeAll()
    fun observeCategories(): Flow<List<CategoryEntity>> = db.categoryDao().observeAll()
    fun observeTags(): Flow<List<TagEntity>> = db.tagDao().observeAll()

    suspend fun getMetadataCounts(): MetadataCounts {
        return MetadataCounts(
            accounts = db.accountDao().observeAll().first().size,
            categories = db.categoryDao().observeAll().first().size,
            tags = db.tagDao().observeAll().first().size
        )
    }

    suspend fun addRecord(
        imageUri: String,
        displayName: String,
        rawText: String,
        screenshotEpochSeconds: Long?
    ): Long {
        val parsedPrimary = parser.parse(rawText)
        val preprocessedText = preprocessOcrText(rawText)
        val parsedSecondary = if (preprocessedText != rawText) parser.parse(preprocessedText) else parsedPrimary
        val parsed = chooseBestParsed(parsedPrimary, parsedSecondary)
        val autoCategoryId = resolveAutoCategoryId(parsed, rawText)
        val autoTagId = resolveAutoTagId(parsed, rawText, autoCategoryId)
        val timeDecision = decidePayTime(parsed.payTime, screenshotEpochSeconds)
        val finalCategoryConfidence = if (autoCategoryId != null) maxOf(parsed.categoryConfidence, 72) else parsed.categoryConfidence
        val finalOverallConfidence = ((parsed.amountConfidence * 0.45) + (parsed.merchantConfidence * 0.35) + (finalCategoryConfidence * 0.20)).toInt().coerceIn(0, 100)
        val record = OcrRecord(
            imageUri = imageUri,
            displayName = displayName,
            rawText = rawText,
            createdAt = System.currentTimeMillis(),
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            merchant = parsed.merchant,
            payTime = timeDecision.first,
            payTimeSource = timeDecision.second,
            payMethod = parsed.payMethod,
            cardTail = parsed.cardTail,
            categoryGuess = parsed.categoryGuess,
            platform = parsed.platform,
            status = parsed.status,
            orderId = parsed.orderId,
            itemName = parsed.itemName,
            amountConfidence = parsed.amountConfidence,
            merchantConfidence = parsed.merchantConfidence,
            categoryConfidence = finalCategoryConfidence,
            overallConfidence = finalOverallConfidence,
            accountId = null,
            categoryId = autoCategoryId,
            tagId = autoTagId,
            comment = null,
            uploadStatus = "draft"
        )
        return db.ocrRecordDao().insert(record)
    }

    suspend fun updateRecord(record: OcrRecord) {
        db.ocrRecordDao().update(record)
    }

    fun setUploadError(recordId: Long, message: String?) {
        if (message.isNullOrBlank()) {
            uploadPrefs.edit().remove(recordId.toString()).apply()
        } else {
            uploadPrefs.edit().putString(recordId.toString(), message).apply()
        }
    }

    fun getUploadError(recordId: Long): String? {
        return uploadPrefs.getString(recordId.toString(), null)
    }

    suspend fun getRecordById(id: Long): OcrRecord? {
        return db.ocrRecordDao().getById(id)
    }

    suspend fun clearLatestRecord(): Boolean {
        val latest = db.ocrRecordDao().getLatest() ?: return false
        db.ocrRecordDao().deleteById(latest.id)
        return true
    }

    suspend fun syncMetadata(): Boolean {
        val settings = settingsRepository.settingsFlow.first()
        val baseUrl = normalizeBaseUrl(settings.host)
        val token = normalizeToken(settings.token)
        if (baseUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "Missing host or token")
            return false
        }

        val accountsJson = getJson("$baseUrl/api/v1/accounts/list.json", token) ?: return false
        val categoriesJson = getJson("$baseUrl/api/v1/transaction/categories/list.json", token)
            ?: return false
        val tagsJson = getJson("$baseUrl/api/v1/transaction/tags/list.json", token)
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
        return uploadRecordDetailed(record).success
    }

    suspend fun uploadRecordDetailed(record: OcrRecord): UploadResult {
        val settings = settingsRepository.settingsFlow.first()
        val baseUrl = normalizeBaseUrl(settings.host)
        val token = normalizeToken(settings.token)

        if (baseUrl.isBlank() || token.isBlank()) {
            val message = "Missing host or token"
            Log.w(TAG, message)
            return UploadResult(false, message)
        }

        val sourceAccountId = resolveSourceAccountId(record, settings.defaultAccountId)
            ?: return UploadResult(false, "Please choose a valid child account (not parent account)")

        val url = "$baseUrl/api/v1/transactions/add.json"
        val timezoneOffset = TimeZone.getDefault().rawOffset / 60000
        val resolvedTagId = record.tagId ?: resolveTagId(record.platform)
        val normalizedAmount = abs(record.amountMinor ?: 0)
        val resolvedCategory = resolveCategory(record, settings.defaultCategoryId)
            ?: return UploadResult(false, "Please choose a valid child category (not primary category)")
        if (resolvedCategory.type == 3) {
            return UploadResult(false, "Transfer category is not supported for this upload flow")
        }
        val inferredType = inferCategoryType(
            amountMinor = record.amountMinor,
            status = record.status,
            text = listOfNotNull(record.merchant, record.itemName, record.rawText).joinToString(" ")
        )
        val categoryForUpload = if (resolvedCategory.type != inferredType) {
            val switched = findFallbackCategoryByType(inferredType, resolvedCategory.id)
            switched ?: return UploadResult(
                false,
                "Category conflicts with amount direction. Please choose a ${if (inferredType == 1) "income" else "expense"} child category."
            )
        } else {
            resolvedCategory
        }
        val transactionType = mapCategoryTypeToTransactionType(categoryForUpload.type)
            ?: return UploadResult(false, "transaction category type is invalid: ${categoryForUpload.type}")
        val payload = JSONObject().apply {
            put("type", transactionType)
            put("categoryId", categoryForUpload.id)
            put("time", record.payTime ?: System.currentTimeMillis() / 1000)
            put("utcOffset", timezoneOffset)
            put("sourceAccountId", sourceAccountId)
            put("sourceAmount", normalizedAmount)
            put("comment", record.comment ?: record.merchant ?: "")
            resolvedTagId?.let { put("tagIds", JSONArray(listOf(it))) }
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Timezone-Offset", timezoneOffset.toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        UploadResult(true, "OK")
                    } else {
                        val apiMessage = extractApiError(bodyText)
                        val message = "HTTP ${response.code} ${response.message} ${apiMessage}".trim()
                        Log.w(TAG, "Upload failed: $message")
                        UploadResult(false, message)
                    }
                }
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "Upload exception", error)
                UploadResult(false, message)
            }
        }
    }

    fun enqueueRetryUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadRetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueue(request)
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
        parseAccountArray(result, null, list)
        return list
    }

    private fun parseAccountArray(
        array: JSONArray,
        parentIdFallback: String?,
        list: MutableList<AccountEntity>
    ) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val category = obj.optInt("category")
            val parentId = obj.optString("parentId").ifBlank { parentIdFallback }
            if (id.isNotBlank()) {
                list.add(AccountEntity(id = id, name = name, category = category, parentId = parentId))
            }
            val subs = obj.optJSONArray("subAccounts")
            if (subs != null && id.isNotBlank()) {
                parseAccountArray(subs, id, list)
            }
        }
    }

    private fun parseCategories(json: JSONObject): List<CategoryEntity> {
        val result = json.optJSONObject("result") ?: JSONObject()
        val list = mutableListOf<CategoryEntity>()
        val keys = result.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = result.optJSONArray(key) ?: continue
            val typeFromKey = key.toIntOrNull() ?: 0
            parseCategoryArray(array, typeFromKey, null, list)
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

    private suspend fun resolveAutoTagId(
        parsed: ReceiptParser.ParsedReceipt,
        rawText: String,
        categoryId: String?
    ): String? {
        val tags = db.tagDao().observeAll().first()
        if (tags.isEmpty()) return null

        val byPlatform = resolveTagId(parsed.platform)
        if (byPlatform != null) return byPlatform

        val merchantKey = normalizeMerchantKey(parsed.merchant)
        if (merchantKey.isNotBlank()) {
            val preferredByHistory = db.ocrRecordDao().getRecentTagged(200).firstOrNull { rec ->
                val key = normalizeMerchantKey(rec.merchant)
                key.isNotBlank() &&
                    (key.contains(merchantKey) || merchantKey.contains(key) || merchantSimilarity(key, merchantKey) >= 0.70f)
            }?.tagId
            if (!preferredByHistory.isNullOrBlank()) return preferredByHistory
        }

        val hintText = listOfNotNull(parsed.merchant, parsed.itemName, parsed.categoryGuess, rawText).joinToString(" ").lowercase()
        val tokenToTag = listOf(
            "餐" to listOf("餐饮", "吃", "外卖", "咖啡", "奶茶"),
            "出行" to listOf("出行", "交通", "打车", "地铁"),
            "购物" to listOf("购物", "超市", "淘宝", "京东"),
            "医疗" to listOf("医疗", "健康", "药"),
            "缴费" to listOf("缴费", "水电", "话费"),
            "娱乐" to listOf("娱乐", "电影", "游戏")
        )
        for ((_, keywords) in tokenToTag) {
            if (keywords.any { hintText.contains(it) }) {
                val tag = tags.firstOrNull { t ->
                    val n = t.name.lowercase()
                    keywords.any { k -> n.contains(k) || k.contains(n) }
                }
                if (tag != null) return tag.id
            }
        }

        if (!categoryId.isNullOrBlank()) {
            val category = db.categoryDao().getById(categoryId)
            if (category != null) {
                val name = category.name.lowercase()
                val tag = tags.firstOrNull { t ->
                    val n = t.name.lowercase()
                    n.contains(name) || name.contains(n)
                }
                if (tag != null) return tag.id
            }
        }
        return null
    }

    private suspend fun resolveAutoCategoryId(
        parsed: ReceiptParser.ParsedReceipt,
        rawText: String
    ): String? {
        val categories = db.categoryDao().observeAll().first()
        if (categories.isEmpty()) return null
        val leaves = leafCategories(categories)
        if (leaves.isEmpty()) return null
        val targetType = inferCategoryType(
            amountMinor = parsed.amountMinor,
            status = parsed.status,
            text = listOfNotNull(parsed.merchant, parsed.itemName, rawText).joinToString(" ")
        )
        val preferredLeaves = leaves.filter { it.type == targetType }.ifEmpty { leaves }
        val byHistory = resolveCategoryFromHistory(parsed.merchant, preferredLeaves)
        if (byHistory != null) return byHistory
        val hintText = listOfNotNull(parsed.merchant, parsed.itemName, rawText).joinToString(" ").lowercase()
        val keyword = parsed.categoryGuess?.trim().orEmpty().lowercase()
        val hintTokens = buildCategoryHintTokens(keyword, hintText)
        if (hintTokens.isEmpty()) return resolveCategoryByBrandHint(parsed, preferredLeaves, hintText)

        val historyVotes = buildHistoryVotes(parsed.merchant, preferredLeaves)

        val scored = preferredLeaves.map { category ->
            val name = category.name.lowercase()
            var score = 0
            if (keyword.isNotBlank() && (name.contains(keyword) || keyword.contains(name))) score += 20
            for (token in hintTokens) {
                if (name.contains(token)) score += 8
                if (token.contains(name)) score += 4
            }
            score += historyVotes[category.id] ?: 0
            score += scoreCategoryByBrand(category.name.lowercase(), hintText)
            category to score
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second >= 18) best.first.id else resolveCategoryByBrandHint(parsed, preferredLeaves, hintText)
    }

    private suspend fun resolveCategoryFromHistory(
        merchant: String?,
        preferredLeaves: List<CategoryEntity>
    ): String? {
        val key = normalizeMerchantKey(merchant)
        if (key.isBlank()) return null
        val preferredIds = preferredLeaves.map { it.id }.toSet()
        val history = db.ocrRecordDao().getRecentLabeled(200)
        val hit = history.firstOrNull { record ->
            val hk = normalizeMerchantKey(record.merchant)
            hk.isNotBlank() &&
                (hk.contains(key) || key.contains(hk) || merchantSimilarity(hk, key) >= 0.72f) &&
                record.categoryId in preferredIds
        }
        return hit?.categoryId
    }

    private suspend fun buildHistoryVotes(
        merchant: String?,
        preferredLeaves: List<CategoryEntity>
    ): Map<String, Int> {
        val key = normalizeMerchantKey(merchant)
        if (key.isBlank()) return emptyMap()
        val preferredIds = preferredLeaves.map { it.id }.toSet()
        val votes = mutableMapOf<String, Int>()
        val history = db.ocrRecordDao().getRecentLabeled(300)
        for (record in history) {
            val cid = record.categoryId ?: continue
            if (cid !in preferredIds) continue
            val hk = normalizeMerchantKey(record.merchant)
            if (hk.isBlank()) continue
            val sim = merchantSimilarity(hk, key)
            val contain = hk.contains(key) || key.contains(hk)
            if (contain || sim >= 0.60f) {
                val score = if (contain) 24 else (sim * 20).toInt()
                votes[cid] = (votes[cid] ?: 0) + score
            }
        }
        return votes
    }

    private fun normalizeMerchantKey(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val canonical = MerchantAliasDictionary.canonicalize(input)
        return canonical
            .replace(Regex("商户|收款方|付款方|对方"), "")
            .replace(Regex("有限公司|有限责任公司|个体工商户"), "")
            .replace(Regex("[^\\p{IsHan}A-Za-z0-9]"), "")
            .lowercase()
            .trim()
    }

    private fun merchantSimilarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size.toFloat()
        val union = sa.union(sb).size.toFloat().coerceAtLeast(1f)
        return inter / union
    }

    private fun resolveCategoryByBrandHint(
        parsed: ReceiptParser.ParsedReceipt,
        preferredLeaves: List<CategoryEntity>,
        hintText: String
    ): String? {
        val merchant = parsed.merchant.orEmpty().lowercase()
        val text = "$merchant ${parsed.itemName.orEmpty().lowercase()} $hintText"
        val keywordToCat = listOf(
            "美团" to listOf("餐", "外卖"),
            "饿了么" to listOf("餐", "外卖"),
            "肯德基" to listOf("餐"),
            "麦当劳" to listOf("餐"),
            "瑞幸" to listOf("餐", "咖啡"),
            "星巴克" to listOf("餐", "咖啡"),
            "滴滴" to listOf("出行", "交通", "打车"),
            "曹操出行" to listOf("出行", "交通"),
            "高德打车" to listOf("出行", "交通"),
            "地铁" to listOf("出行", "交通"),
            "淘宝" to listOf("购", "购物"),
            "京东" to listOf("购", "购物"),
            "拼多多" to listOf("购", "购物"),
            "盒马" to listOf("超市", "购"),
            "山姆" to listOf("超市", "购"),
            "沃尔玛" to listOf("超市", "购"),
            "医院" to listOf("医", "疗"),
            "药店" to listOf("医", "疗", "药"),
            "电费" to listOf("缴", "费", "水电"),
            "水费" to listOf("缴", "费", "水电")
        )
        val hints = keywordToCat.firstOrNull { text.contains(it.first) }?.second ?: return null
        var best: CategoryEntity? = null
        var bestScore = Int.MIN_VALUE
        for (category in preferredLeaves) {
            val name = category.name.lowercase()
            var score = 0
            for (token in hints) {
                score += when {
                    name.contains(token) -> 8
                    token.contains(name) -> 4
                    else -> 0
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = category
            }
        }
        return best?.id
    }

    private fun scoreCategoryByBrand(categoryName: String, hintText: String): Int {
        return when {
            listOf("美团", "饿了么", "咖啡", "奶茶", "面包", "快餐").any { hintText.contains(it) } &&
                categoryName.contains("餐") -> 10
            listOf("滴滴", "打车", "地铁", "公交", "高铁").any { hintText.contains(it) } &&
                (categoryName.contains("出行") || categoryName.contains("交通")) -> 10
            listOf("淘宝", "京东", "拼多多", "超市", "便利店").any { hintText.contains(it) } &&
                (categoryName.contains("购") || categoryName.contains("超市")) -> 10
            listOf("医院", "药", "挂号").any { hintText.contains(it) } &&
                (categoryName.contains("医") || categoryName.contains("药")) -> 10
            listOf("电费", "水费", "燃气", "话费").any { hintText.contains(it) } &&
                (categoryName.contains("缴") || categoryName.contains("费")) -> 10
            else -> 0
        }
    }

    private fun buildCategoryHintTokens(keyword: String, hintText: String): Set<String> {
        val tokens = linkedSetOf<String>()
        if (keyword.isNotBlank()) {
            tokens += keyword
            if (keyword.length >= 2) {
                tokens += keyword.substring(0, 1)
            }
        }
        if (listOf("奶茶", "咖啡", "外卖", "美团", "饿了么", "面包", "餐").any { hintText.contains(it) }) {
            tokens += listOf("餐", "饮", "食", "外卖", "咖啡", "奶茶")
        }
        if (listOf("滴滴", "打车", "地铁", "公交", "加油").any { hintText.contains(it) }) {
            tokens += listOf("出行", "交通", "行", "打车", "地铁")
        }
        if (listOf("超市", "便利店", "淘宝", "京东", "拼多多").any { hintText.contains(it) }) {
            tokens += listOf("购", "物", "超市", "日用", "百货")
        }
        if (listOf("医院", "药", "挂号").any { hintText.contains(it) }) {
            tokens += listOf("医", "疗", "药", "健康")
        }
        if (listOf("电费", "水费", "燃气", "话费", "缴费").any { hintText.contains(it) }) {
            tokens += listOf("缴", "费", "生活", "水电")
        }
        if (listOf("电影", "游戏", "娱乐", "网吧").any { hintText.contains(it) }) {
            tokens += listOf("娱", "乐", "游戏", "电影")
        }
        return tokens.filter { it.isNotBlank() }.toSet()
    }

    private fun leafCategories(categories: List<CategoryEntity>): List<CategoryEntity> {
        val parentIds = categories.mapNotNull { it.parentId }.toSet()
        return categories.filter { it.id !in parentIds }
    }

    private fun parseCategoryArray(
        array: JSONArray,
        typeFallback: Int,
        parentIdFallback: String?,
        list: MutableList<CategoryEntity>
    ) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            val type = obj.optInt("type", typeFallback)
            val parentId = obj.optString("parentId").ifBlank { parentIdFallback }
            if (id.isNotBlank()) {
                list.add(CategoryEntity(id = id, name = name, type = type, parentId = parentId))
            }
            val subs = obj.optJSONArray("subCategories")
            if (subs != null) {
                parseCategoryArray(subs, type, id.ifBlank { parentId }, list)
            }
        }
    }

    private fun normalizeBaseUrl(host: String): String {
        val cleaned = host.trim().trimEnd('/')
        if (cleaned.isBlank()) return ""
        return if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            cleaned
        } else {
            "https://$cleaned"
        }
    }

    private fun normalizeToken(token: String): String {
        return token.replace("\\s+".toRegex(), "")
    }

    private fun extractApiError(bodyText: String): String {
        if (bodyText.isBlank()) return ""
        return try {
            val json = JSONObject(bodyText)
            when {
                json.has("message") -> json.optString("message")
                json.has("msg") -> json.optString("msg")
                json.has("errorMessage") -> json.optString("errorMessage")
                else -> bodyText.take(120)
            }
        } catch (_: Exception) {
            bodyText.take(120)
        }
    }

    private suspend fun resolveSourceAccountId(record: OcrRecord, defaultAccountId: String): String? {
        val preferred = (record.accountId ?: defaultAccountId).trim()
        if (preferred.isBlank()) return null
        val accounts = db.accountDao().observeAll().first()
        if (accounts.isEmpty()) return preferred
        val exact = db.accountDao().getById(preferred) ?: return null
        val child = db.accountDao().getFirstChild(exact.id)
        if (child != null) return child.id
        return if (exact.parentId.isNullOrBlank()) null else exact.id
    }

    private suspend fun resolveCategory(record: OcrRecord, defaultCategoryId: String): CategoryEntity? {
        val preferred = (record.categoryId ?: defaultCategoryId).trim()
        val categories = db.categoryDao().observeAll().first()
        if (categories.isEmpty()) return null
        if (preferred.isNotBlank()) {
            val exact = db.categoryDao().getById(preferred)
            if (exact != null) {
                val child = db.categoryDao().getFirstChild(exact.id)
                val resolved = child ?: if (exact.parentId.isNullOrBlank()) null else exact
                if (resolved != null) return resolved
            }
        }
        val leaves = leafCategories(categories)
        if (leaves.isEmpty()) return null
        val targetType = inferCategoryType(
            amountMinor = record.amountMinor,
            status = record.status,
            text = listOfNotNull(record.merchant, record.itemName, record.rawText).joinToString(" ")
        )
        return leaves.firstOrNull { it.type == targetType } ?: leaves.firstOrNull()
    }

    private fun mapCategoryTypeToTransactionType(categoryType: Int): Int? {
        return when (categoryType) {
            1 -> 2 // income category -> income transaction
            2 -> 3 // expense category -> expense transaction
            3 -> 4 // transfer category -> transfer transaction
            else -> null
        }
    }

    private suspend fun findFallbackCategoryByType(type: Int, currentCategoryId: String): CategoryEntity? {
        val categories = db.categoryDao().observeAll().first()
        val leaves = leafCategories(categories).filter { it.type == type }
        if (leaves.isEmpty()) return null
        val current = categories.firstOrNull { it.id == currentCategoryId }
        if (current != null && !current.parentId.isNullOrBlank()) {
            val sibling = leaves.firstOrNull { it.parentId == current.parentId }
            if (sibling != null) return sibling
        }
        return leaves.firstOrNull()
    }

    private fun preprocessOcrText(text: String): String {
        return text
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('，', ',')
            .replace('。', '.')
            .replace(Regex("([+\\-])\\s+([0-9])"), "$1$2")
    }

    private fun decidePayTime(ocrPayTime: Long?, screenshotEpochSeconds: Long?): Pair<Long, String> {
        val now = System.currentTimeMillis() / 1000
        if (ocrPayTime != null && ocrPayTime in 946684800L..(now + 600)) {
            return ocrPayTime to "OCR"
        }
        if (screenshotEpochSeconds != null && screenshotEpochSeconds in 946684800L..(now + 600)) {
            return screenshotEpochSeconds to "SCREENSHOT"
        }
        return now to "NOW"
    }

    private fun chooseBestParsed(
        primary: ReceiptParser.ParsedReceipt,
        secondary: ReceiptParser.ParsedReceipt
    ): ReceiptParser.ParsedReceipt {
        if (secondary === primary) return primary
        return if (secondary.overallConfidence > primary.overallConfidence) secondary else primary
    }

    private fun inferCategoryType(amountMinor: Long?, status: String?, text: String): Int {
        val lower = text.lowercase()
        if (status == "Refund") return 1
        val explicitSign = detectExplicitAmountSign(text)
        if (explicitSign < 0) return 2
        if (explicitSign > 0) return 1
        val incomeHints = listOf("收款", "收入", "到账", "退款成功", "转入", "工资", "奖金", "红包", "入账")
        val expenseHints = listOf("支付", "付款", "消费", "支出", "买单", "扣款", "交易成功", "商户")
        val hasIncomeHint = incomeHints.any { lower.contains(it) }
        val hasExpenseHint = expenseHints.any { lower.contains(it) }
        if (hasIncomeHint && !hasExpenseHint) return 1
        if (hasExpenseHint && !hasIncomeHint) return 2
        // Amount sign is a weak signal because OCR often misses +/-.
        val amount = amountMinor ?: 0L
        if (amount < 0L) return 2
        if (amount > 0L && hasIncomeHint) return 1
        return 2
    }

    private fun detectExplicitAmountSign(text: String): Int {
        val normalized = text
            .replace('＋', '+')
            .replace('－', '-')
            .replace("：", ":")
        val signPattern = Regex("([+-])\\s*[0-9OoIl][0-9OoIl,，.]*")
        val match = signPattern.find(normalized) ?: return 0
        return if (match.groupValues[1] == "-") -1 else 1
    }

    companion object {
        private const val TAG = "OcrRepository"
        private const val PREF_UPLOAD_ERRORS = "upload_errors"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class UploadResult(
    val success: Boolean,
    val message: String
)

data class MetadataCounts(
    val accounts: Int,
    val categories: Int,
    val tags: Int
)
