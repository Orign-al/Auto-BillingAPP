package com.moneyapp.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

class ReceiptParser {
    fun parse(text: String): ParsedReceipt {
        val normalized = text.replace("\u00A0", " ")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val amount = extractAmount(normalized)
        val time = extractTime(normalized)
        val merchant = extractByLabels(
            lines,
            listOf("收款方", "商家", "对方", "付款给", "收款单位", "商户名称", "商户", "对方账户")
        ) ?: extractMerchantFallback(lines)
        val payMethod = extractByLabels(lines, listOf("支付方式", "付款方式", "银行卡", "信用卡", "支付工具"))
        val cardTail = extractCardTail(normalized)
        val platform = extractPlatform(normalized)
        val status = extractStatus(normalized)
        val orderId = extractByLabels(lines, listOf("订单号", "交易号", "商户单号", "交易订单号"))
        val itemName = extractByLabels(lines, listOf("商品", "商品名称", "服务", "商品说明", "商品详情"))
        val categoryGuess = guessCategory(normalized)

        return ParsedReceipt(
            amountMinor = amount?.minor,
            currency = amount?.currency,
            merchant = merchant,
            payTime = time,
            payMethod = payMethod,
            cardTail = cardTail,
            categoryGuess = categoryGuess,
            platform = platform,
            status = status,
            orderId = orderId,
            itemName = itemName
        )
    }

    private fun extractAmount(text: String): MoneyAmount? {
        val keywordRegex = Pattern.compile("(实付|付款金额|合计|支付金额|总计|应付|实付款|消费金额)[^0-9]{0,6}([0-9]+(?:\\.[0-9]{1,2})?)")
        val keywordMatch = keywordRegex.matcher(text)
        if (keywordMatch.find()) {
            return toMoney(keywordMatch.group(2))
        }

        val fallbackRegex = Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)")
        val matches = mutableListOf<String>()
        val matcher = fallbackRegex.matcher(text)
        while (matcher.find()) {
            matches.add(matcher.group(1))
        }
        return matches.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }?.let { toMoney(it) }
    }

    private fun extractTime(text: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy.MM.dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )

        for (pattern in patterns) {
            val regex = Pattern.compile(patternToRegex(pattern))
            val matcher = regex.matcher(text)
            if (matcher.find()) {
                val value = matcher.group(0)
                return parseToEpochSeconds(value, pattern)
            }
        }
        return null
    }

    private fun extractByLabels(lines: List<String>, labels: List<String>): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val label = labels.firstOrNull { line.contains(it) }
            if (label != null) {
                val remainder = line.substringAfter(label).replace(":", "").trim()
                if (remainder.isNotBlank()) return remainder
                if (i + 1 < lines.size) return lines[i + 1]
            }
        }
        return null
    }

    private fun extractMerchantFallback(lines: List<String>): String? {
        val skipKeywords = listOf("支付", "金额", "订单", "时间", "收款方", "商家", "对方")
        return lines.firstOrNull { line ->
            line.length in 2..20 && skipKeywords.none { line.contains(it) }
        }
    }

    private fun extractCardTail(text: String): String? {
        val regex = Pattern.compile("尾号\\s?([0-9]{3,4})")
        val matcher = regex.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
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
            text.contains("退款") -> "Refund"
            text.contains("支付成功") || text.contains("交易成功") -> "Success"
            else -> null
        }
    }

    private fun guessCategory(text: String): String? {
        val map = mapOf(
            "餐" to "餐饮",
            "咖啡" to "餐饮",
            "外卖" to "餐饮",
            "地铁" to "出行",
            "打车" to "出行",
            "滴滴" to "出行",
            "超市" to "购物",
            "便利店" to "购物",
            "酒店" to "住宿",
            "话费" to "生活缴费",
            "电费" to "生活缴费",
            "水费" to "生活缴费",
            "医院" to "医疗",
            "药" to "医疗"
        )
        return map.entries.firstOrNull { text.contains(it.key) }?.value
    }

    private fun toMoney(value: String?): MoneyAmount? {
        val amount = value?.toDoubleOrNull() ?: return null
        val minor = (amount * 100).toLong()
        return MoneyAmount(minor, "CNY")
    }

    private fun parseToEpochSeconds(value: String, pattern: String): Long? {
        return try {
            val formatter = SimpleDateFormat(pattern, Locale.getDefault())
            formatter.timeZone = TimeZone.getDefault()
            val date = formatter.parse(value) ?: return null
            date.time / 1000
        } catch (ex: Exception) {
            null
        }
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
        val itemName: String?
    )

    data class MoneyAmount(val minor: Long, val currency: String)
}
