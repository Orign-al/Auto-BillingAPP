package com.moneyapp.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

class ReceiptParser {
    fun parse(text: String): ParsedReceipt {
        val normalized = text
            .replace("\u00A0", " ")
            .replace("：", ":")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val template = detectTemplate(normalized, lines)

        val amount = extractAmount(normalized, lines)
        val time = extractTime(normalized)
        val merchant = pickBestMerchant(lines, template)
        val payMethod = extractByLabels(lines, payMethodLabels(template))
        val cardTail = extractCardTail(normalized) ?: extractCardTail(payMethod ?: "")
        val platform = extractPlatform(normalized)
        val status = extractStatus(normalized)
        val orderId = extractByLabels(lines, orderIdLabels(template)) ?: extractOrderId(normalized)
        val itemName = extractByLabels(lines, itemLabels(template))
        val categoryGuess = guessCategory(normalized)
        val amountMinor = amount?.minor
        val amountConfidence = computeAmountConfidence(amountMinor, normalized)
        val merchantConfidence = computeMerchantConfidence(merchant)
        val categoryConfidence = computeCategoryConfidence(categoryGuess, normalized)
        val overallConfidence = ((amountConfidence * 0.45) + (merchantConfidence * 0.35) + (categoryConfidence * 0.20)).toInt()

        return ParsedReceipt(
            amountMinor = amountMinor,
            currency = amount?.currency,
            merchant = merchant,
            payTime = time,
            payMethod = payMethod,
            cardTail = cardTail,
            categoryGuess = categoryGuess,
            platform = platform,
            status = status,
            orderId = orderId,
            itemName = itemName,
            amountConfidence = amountConfidence,
            merchantConfidence = merchantConfidence,
            categoryConfidence = categoryConfidence,
            overallConfidence = clampScore(overallConfidence)
        )
    }

    private fun extractAmount(text: String, lines: List<String>): MoneyAmount? {
        val keywordRegex = Pattern.compile(
            "(实付|付款金额|合计|支付金额|总计|应付|实付款|消费金额|支付合计|Paid|Total|Amount)[^0-9A-Za-z\\-\\+]{0,8}([\\-+]?[-+0-9OoIl,，.]{1,16})",
            Pattern.CASE_INSENSITIVE
        )
        val keywordMatch = keywordRegex.matcher(text)
        if (keywordMatch.find()) {
            return toMoney(keywordMatch.group(2), text)
        }

        val currencyRegex = Pattern.compile("([¥￥$]|USD|CNY|RMB|HKD)\\s*([\\-+]?[-+0-9OoIl,，.]{1,16})", Pattern.CASE_INSENSITIVE)
        val currencyMatch = currencyRegex.matcher(text)
        if (currencyMatch.find()) {
            return toMoneyWithHint(currencyMatch.group(2), currencyMatch.group(1))
        }

        val amountLine = lines.firstOrNull { line ->
            line.matches(Regex("^[\\-+]?[0-9OoIl,，.]+$"))
        }
        if (amountLine != null) {
            return toMoney(amountLine, text)
        }

        val fallbackRegex = Pattern.compile("([\\-+]?[0-9OoIl,，.]{1,16})")
        val matches = mutableListOf<String>()
        val matcher = fallbackRegex.matcher(text)
        while (matcher.find()) {
            val value = matcher.group(1) ?: continue
            if (!looksLikeId(value)) {
                matches.add(value)
            }
        }
        return matches
            .mapNotNull { candidate -> normalizeAmountValue(candidate)?.toDoubleOrNull()?.let { candidate to it } }
            .maxByOrNull { kotlin.math.abs(it.second) }
            ?.let { toMoney(it.first, text) }
    }

    private fun extractTime(text: String): Long? {
        val cnMatcher = Pattern.compile("(\\d{4}年\\d{1,2}月\\d{1,2}日\\s?\\d{1,2}:\\d{2}(?::\\d{2})?)").matcher(text)
        if (cnMatcher.find()) {
            val raw = cnMatcher.group(1) ?: ""
            val parsed = parseWithPatterns(
                raw,
                listOf(
                    "yyyy年M月d日H:mm:ss",
                    "yyyy年M月d日HH:mm:ss",
                    "yyyy年M月d日H:mm",
                    "yyyy年M月d日HH:mm"
                )
            )
            if (parsed != null) return parsed
        }

        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy.MM.dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy年MM月dd日 HH:mm",
            "yyyy年MM月dd日 HH:mm:ss",
            "MM-dd HH:mm",
            "MM/dd HH:mm"
        )

        for (pattern in patterns) {
            val regex = Pattern.compile(patternToRegex(pattern))
            val matcher = regex.matcher(text)
            if (matcher.find()) {
                val value = matcher.group(0) ?: continue
                return parseToEpochSeconds(value, pattern)
            }
        }
        return null
    }

    private fun extractByLabels(lines: List<String>, labels: List<String>): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val label = labels.firstOrNull { matchesLabel(line, it) }
            if (label != null) {
                val remainder = line.substringAfter(label).replace(":", "").trim()
                if (remainder.isNotBlank()) return remainder
                if (i + 1 < lines.size) return lines[i + 1]
            }
        }
        return null
    }

    private fun extractByLabel(lines: List<String>, label: String): String? {
        for (i in lines.indices) {
            val line = lines[i]
            if (!matchesLabel(line, label)) continue
            val remainder = line.substringAfter(label).replace(":", "").trim()
            if (remainder.isNotBlank()) return remainder
            if (i + 1 < lines.size) return lines[i + 1]
        }
        return null
    }

    private fun extractMerchantFallback(lines: List<String>): String? {
        val skipKeywords = listOf(
            "支付", "金额", "订单", "时间", "收款方", "商家", "对方",
            "交易", "成功", "账单", "详情", "分类", "标签"
        )
        val preferredMarkers = listOf("店", "商户", "超市", "便利店", "餐", "咖啡", "奶茶", ":")
        val candidates = lines.filter { line ->
            val normalized = normalizeMerchant(line)
            line.length in 2..24 &&
                skipKeywords.none { line.contains(it) } &&
                (!line.any { it.isDigit() } || MerchantAliasDictionary.isKnownBrand(normalized))
        }
        return candidates.firstNotNullOfOrNull { line ->
            val normalized = normalizeMerchant(line)
            if (normalized.isBlank()) null
            else if (preferredMarkers.any { normalized.contains(it) } || MerchantAliasDictionary.isKnownBrand(normalized)) normalized
            else null
        } ?: candidates.firstNotNullOfOrNull { line ->
            normalizeMerchant(line).takeIf { it.isNotBlank() }
        }
    }

    private fun extractCardTail(text: String): String? {
        val regex = Pattern.compile("尾号\\s?([0-9]{3,4})")
        val matcher = regex.matcher(text)
        if (matcher.find()) return matcher.group(1)

        val bracketRegex = Pattern.compile("[\\(（]([0-9]{3,4})[\\)）]")
        val bracketMatcher = bracketRegex.matcher(text)
        return if (bracketMatcher.find()) bracketMatcher.group(1) else null
    }

    private fun extractPlatform(text: String): String? {
        return when {
            text.contains("微信") || text.contains("WeChat", ignoreCase = true) -> "WeChat"
            text.contains("支付宝") || text.contains("Alipay", ignoreCase = true) -> "Alipay"
            else -> null
        }
    }

    private fun extractStatus(text: String): String? {
        return when {
            text.contains("退款") || text.contains("退款成功") -> "Refund"
            text.contains("支付成功") || text.contains("交易成功") -> "Success"
            text.contains("交易失败") || text.contains("支付失败") -> "Failed"
            else -> null
        }
    }

    private fun extractOrderId(text: String): String? {
        val regex = Pattern.compile("(订单号|交易号|商户单号)[:\\s]*([A-Za-z0-9\\-]{8,})")
        val matcher = regex.matcher(text)
        return if (matcher.find()) matcher.group(2) else null
    }

    private fun guessCategory(text: String): String? {
        val map = mapOf(
            "餐" to "餐饮",
            "咖啡" to "餐饮",
            "奶茶" to "餐饮",
            "外卖" to "餐饮",
            "美团" to "餐饮",
            "饿了么" to "餐饮",
            "面包" to "餐饮",
            "地铁" to "出行",
            "打车" to "出行",
            "滴滴" to "出行",
            "公交" to "出行",
            "加油" to "出行",
            "超市" to "购物",
            "便利店" to "购物",
            "淘宝" to "购物",
            "京东" to "购物",
            "拼多多" to "购物",
            "酒店" to "住宿",
            "民宿" to "住宿",
            "话费" to "生活缴费",
            "电费" to "生活缴费",
            "水费" to "生活缴费",
            "燃气" to "生活缴费",
            "医院" to "医疗",
            "药" to "医疗",
            "挂号" to "医疗",
            "电影" to "娱乐",
            "游戏" to "娱乐"
        )
        return map.entries.firstOrNull { text.contains(it.key) }?.value
    }

    private fun toMoney(value: String?, context: String): MoneyAmount? {
        val amount = normalizeAmountValue(value)?.toDoubleOrNull() ?: return null
        val minor = (amount * 100).toLong()
        return MoneyAmount(minor, detectCurrency(context))
    }

    private fun toMoneyWithHint(value: String?, currencyHint: String?): MoneyAmount? {
        val amount = normalizeAmountValue(value)?.toDoubleOrNull() ?: return null
        val minor = (amount * 100).toLong()
        val currency = detectCurrency(currencyHint ?: "")
        return MoneyAmount(minor, currency)
    }

    private fun parseToEpochSeconds(value: String, pattern: String): Long? {
        return try {
            val (finalValue, finalPattern) = if (!pattern.contains("yyyy")) {
                val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                "$year-$value" to "yyyy-$pattern"
            } else {
                value to pattern
            }
            val formatter = SimpleDateFormat(finalPattern, Locale.getDefault())
            formatter.timeZone = TimeZone.getDefault()
            val date = formatter.parse(finalValue) ?: return null
            date.time / 1000
        } catch (ex: Exception) {
            null
        }
    }

    private fun parseWithPatterns(value: String, patterns: List<String>): Long? {
        for (pattern in patterns) {
            val parsed = parseToEpochSeconds(value, pattern)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun patternToRegex(pattern: String): String {
        return pattern
            .replace("yyyy", "\\d{4}")
            .replace("MM", "\\d{2}")
            .replace("dd", "\\d{2}")
            .replace("HH", "\\d{2}")
            .replace("mm", "\\d{2}")
            .replace("ss", "\\d{2}")
    }

    private fun matchesLabel(line: String, label: String): Boolean {
        val idx = line.indexOf(label)
        if (idx < 0) return false
        val end = idx + label.length
        if (end >= line.length) return true
        val next = line[end]
        return next == ':' || next == '：' || next.isWhitespace() || next == '(' || next == '（'
    }

    private fun detectCurrency(text: String): String {
        val lower = text.lowercase(Locale.getDefault())
        return when {
            lower.contains("usd") || text.contains("$") -> "USD"
            lower.contains("hkd") || text.contains("hk$") -> "HKD"
            lower.contains("cny") || lower.contains("rmb") || text.contains("¥") || text.contains("￥") -> "CNY"
            else -> "CNY"
        }
    }

    private fun looksLikeId(value: String): Boolean {
        return value.length >= 8 && !value.contains(".")
    }

    private fun detectTemplate(text: String, lines: List<String>): Template {
        if (text.contains("账单详情") || text.contains("支付宝")) return Template.ALIPAY
        if (text.contains("收单机构") || text.contains("商户全称") || lines.any { it.contains("支付成功") }) {
            return Template.WECHAT
        }
        return Template.GENERIC
    }

    private fun merchantLabels(template: Template): List<String> {
        return when (template) {
            Template.WECHAT -> listOf("商户名称", "商户", "收款方", "对方", "商户全称")
            Template.ALIPAY -> listOf("商户名称", "收款方", "商户", "收款方全称")
            Template.GENERIC -> listOf("收款方", "商家", "对方", "付款给", "收款单位", "商户名称", "商户", "对方账户")
        }
    }

    private fun payMethodLabels(template: Template): List<String> {
        return when (template) {
            Template.WECHAT -> listOf("支付方式", "银行卡", "信用卡", "支付工具")
            Template.ALIPAY -> listOf("付款方式", "支付方式", "付款账户", "银行卡", "信用卡")
            Template.GENERIC -> listOf("支付方式", "付款方式", "银行卡", "信用卡", "支付工具", "支付渠道", "付款账户")
        }
    }

    private fun orderIdLabels(template: Template): List<String> {
        return when (template) {
            Template.WECHAT -> listOf("交易单号", "商户单号", "交易号", "订单号")
            Template.ALIPAY -> listOf("交易号", "订单号", "商户订单号", "交易单号")
            Template.GENERIC -> listOf("订单号", "交易号", "商户单号", "交易订单号", "交易单号", "商户订单号")
        }
    }

    private fun itemLabels(template: Template): List<String> {
        return when (template) {
            Template.WECHAT -> listOf("商品", "商品名称")
            Template.ALIPAY -> listOf("商品说明", "商品", "商品名称", "商品描述")
            Template.GENERIC -> listOf("商品", "商品名称", "服务", "商品说明", "商品详情", "商品描述")
        }
    }

    private fun extractMerchantNearAmount(lines: List<String>): String? {
        val amountIndex = lines.indexOfFirst { line -> normalizeAmountValue(line) != null }
        if (amountIndex > 0) {
            // Prefer the line immediately above amount, then fallback within 3 lines window.
            for (offset in 1..3) {
                val idx = amountIndex - offset
                if (idx < 0) break
                val candidate = lines[idx].trim()
                if (candidate.length !in 2..40) continue
                val normalized = normalizeMerchant(candidate)
                if (normalized.isBlank()) continue
                val knownBrand = MerchantAliasDictionary.isKnownBrand(normalized)
                if (!isMerchantUsable(normalized) && !knownBrand) continue
                if (isLikelyUiNoise(candidate) || isLikelyUiNoise(normalized) || isMerchantNoiseName(normalized)) continue
                return normalized
            }
        }
        return null
    }

    private fun pickBestMerchant(lines: List<String>, template: Template): String? {
        val candidates = mutableListOf<Pair<String, Int>>()
        merchantLabels(template).forEachIndexed { index, label ->
            val value = extractByLabel(lines, label)
            if (!value.isNullOrBlank()) {
                candidates.add(value to (100 - index))
            }
        }
        extractMerchantNearAmount(lines)?.let { candidates.add(it to 130) }
        extractMerchantFallback(lines)?.let { candidates.add(it to 70) }

        return candidates
            .map { (value, baseScore) ->
                val cleaned = normalizeMerchant(value)
                cleaned to (baseScore + merchantQualityScore(cleaned))
            }
            .filter { it.first.isNotBlank() && isMerchantUsable(it.first) && !isMerchantNoiseName(it.first) }
            .maxByOrNull { it.second }
            ?.first
            ?: candidates
                .map { normalizeMerchant(it.first) }
                .firstOrNull { it.isNotBlank() && !isMerchantNoiseName(it) }
    }

    private fun normalizeMerchant(raw: String): String {
        val cleaned = raw
            .replace("商户_", "")
            .replace("商户-", "")
            .replace("商户:", "")
            .replace("收款方:", "")
            .replace("收款方全称:", "")
            .replace(Regex("([\\p{IsHan}A-Za-z]{2,20})[0-9]{6,}$"), "$1")
            .replace(Regex("[>＞]+$"), "")
            .replace(Regex("^[:：\\-\\s]+"), "")
            .replace(Regex("\\b(交易成功|支付成功|账单详情|当前状态)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return MerchantAliasDictionary.canonicalize(cleaned)
    }

    private fun merchantQualityScore(name: String): Int {
        var score = 0
        if (name.length in 2..22) score += 6
        if (name.length > 30) score -= 4
        if (name.any { it.isDigit() }) score -= 4
        if (listOf("店", "超市", "便利店", "餐", "咖啡", "奶茶", "面包").any { name.contains(it) }) score += 10
        if (MerchantAliasDictionary.isKnownBrand(name)) score += 12
        if (listOf("有限公司", "有限责任公司", "个体工商户").any { name.contains(it) }) score -= 10
        if (Regex("^(上海|北京|深圳|广州|天津|重庆).*(区|县)").containsMatchIn(name)) score -= 4
        if (name.contains("收单机构") || name.contains("账单详情")) score -= 6
        if (isMerchantNoiseName(name)) score -= 30
        val starCount = name.count { it == '*' || it == '＊' }
        if (starCount >= 2) score -= 12
        if (!isMerchantUsable(name)) score -= 20
        return score
    }

    private fun isMerchantUsable(name: String): Boolean {
        if (name.length < 2) return false
        val stars = name.count { it == '*' || it == '＊' }
        if (stars * 2 >= name.length) return false
        val validChars = name.count { it.isLetterOrDigit() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (validChars < 2) return false
        if (validChars * 2 < name.length) return false
        return true
    }

    private fun isLikelyUiNoise(line: String): Boolean {
        val noise = listOf(
            "账单详情", "订单详情", "当前状态", "交易成功", "支付成功", "支付时间", "付款方式",
            "收单机构", "账单管理", "智慧商户通", "账单服务", "服务", "详情", "分类", "标签", "更多"
        )
        return noise.any { line.contains(it) }
    }

    private fun isMerchantNoiseName(name: String): Boolean {
        val normalized = name.trim()
        val exactNoise = setOf(
            "账单详情", "订单详情", "当前状态", "交易成功", "支付成功",
            "支付时间", "付款方式", "账单管理", "账单服务", "收单机构", "更多"
        )
        if (normalized in exactNoise) return true
        return isLikelyUiNoise(normalized) && normalized.length <= 8
    }

    private fun normalizeAmountValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var s = value.trim()
            .replace("＋", "+")
            .replace("－", "-")
            .replace("，", ",")
            .replace("O", "0")
            .replace("o", "0")
            .replace("I", "1")
            .replace("l", "1")
            .replace("元", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace(Regex("\\s+"), "")
        s = s.replace(Regex("[^0-9+\\-.,]"), "")
        if (s.isBlank()) return null

        val sign = when {
            s.startsWith("-") -> "-"
            s.startsWith("+") -> "+"
            else -> ""
        }
        s = s.removePrefix("-").removePrefix("+")
        if (s.isBlank()) return null

        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimalSep = when {
            lastDot < 0 && lastComma < 0 -> null
            lastDot > lastComma -> '.'
            else -> ','
        }

        val normalized = if (decimalSep == null) {
            s.replace(",", "").replace(".", "")
        } else {
            val idx = if (decimalSep == '.') lastDot else lastComma
            val left = s.substring(0, idx).replace(",", "").replace(".", "")
            val right = s.substring(idx + 1).replace(",", "").replace(".", "")
            when {
                right.isEmpty() -> left
                right.length <= 2 -> "$left.$right"
                else -> left + right
            }
        }
        if (normalized.isBlank()) return null
        return sign + normalized
    }

    private fun computeAmountConfidence(amountMinor: Long?, normalizedText: String): Int {
        if (amountMinor == null) return 20
        var score = 70
        if (kotlin.math.abs(amountMinor) >= 100000) score += 10
        if (kotlin.math.abs(amountMinor) in 100..99999) score += 8
        if (Regex("[+-][0-9OoIl,，.]{1,16}").containsMatchIn(normalizedText)) score += 8
        if (Regex("([OoIl])").containsMatchIn(normalizedText)) score -= 8
        return clampScore(score)
    }

    private fun computeMerchantConfidence(merchant: String?): Int {
        if (merchant.isNullOrBlank()) return 20
        val base = 60 + merchantQualityScore(merchant)
        return clampScore(base)
    }

    private fun computeCategoryConfidence(categoryGuess: String?, normalizedText: String): Int {
        if (categoryGuess.isNullOrBlank()) return 25
        var score = 65
        if (normalizedText.contains(categoryGuess)) score += 15
        return clampScore(score)
    }

    private fun clampScore(score: Int): Int = score.coerceIn(0, 100)

    private enum class Template {
        WECHAT,
        ALIPAY,
        GENERIC
    }

    data class ParsedReceipt(
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
        val amountConfidence: Int,
        val merchantConfidence: Int,
        val categoryConfidence: Int,
        val overallConfidence: Int
    )

    data class MoneyAmount(val minor: Long, val currency: String)
}
