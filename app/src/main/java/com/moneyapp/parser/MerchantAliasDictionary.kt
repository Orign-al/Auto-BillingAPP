package com.moneyapp.parser

object MerchantAliasDictionary {
    private val brandAliases: List<Pair<String, List<String>>> = listOf(
        "美团" to listOf("美团", "美团外卖", "美团买菜", "美团收银", "美团闪购"),
        "饿了么" to listOf("饿了么", "饿了么外卖", "蜂鸟配送"),
        "京东" to listOf("京东", "京东到家", "京东商城", "京东零售"),
        "淘宝" to listOf("淘宝", "淘宝网", "taobao"),
        "天猫" to listOf("天猫", "tmall", "天猫超市"),
        "拼多多" to listOf("拼多多", "pdd", "多多买菜"),
        "抖音" to listOf("抖音", "douyin", "抖音生活服务"),
        "滴滴出行" to listOf("滴滴", "滴滴出行", "didi"),
        "高德打车" to listOf("高德打车", "高德", "gaode"),
        "曹操出行" to listOf("曹操出行", "曹操"),
        "哈啰出行" to listOf("哈啰", "哈啰出行", "hello bike"),
        "瑞幸咖啡" to listOf("瑞幸", "瑞幸咖啡", "luckin"),
        "星巴克" to listOf("星巴克", "starbucks"),
        "麦当劳" to listOf("麦当劳", "mcdonald", "mcdonalds"),
        "肯德基" to listOf("肯德基", "kfc"),
        "喜茶" to listOf("喜茶", "heytea"),
        "奈雪的茶" to listOf("奈雪", "奈雪的茶", "naixue"),
        "蜜雪冰城" to listOf("蜜雪冰城", "mxbc"),
        "盒马" to listOf("盒马", "盒马鲜生", "hema"),
        "山姆会员店" to listOf("山姆", "山姆会员店", "sam's club", "sams club"),
        "沃尔玛" to listOf("沃尔玛", "walmart"),
        "永辉超市" to listOf("永辉", "永辉超市"),
        "华润万家" to listOf("华润万家", "万家mart"),
        "全家" to listOf("全家", "familymart"),
        "罗森" to listOf("罗森", "lawson"),
        "7-ELEVEN" to listOf("7-eleven", "7eleven", "7-11", "seven eleven"),
        "朴朴超市" to listOf("朴朴", "朴朴超市"),
        "叮咚买菜" to listOf("叮咚买菜", "叮咚")
    )

    private val indexedAliases: List<AliasEntry> = brandAliases.flatMap { (brand, aliases) ->
        aliases.mapNotNull { alias ->
            val key = toKey(alias)
            if (key.isBlank()) null else AliasEntry(brand = brand, aliasKey = key)
        }
    }.sortedByDescending { it.aliasKey.length }

    fun canonicalize(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return clean
        val key = toKey(clean)
        if (key.isBlank()) return clean
        val hit = indexedAliases.firstOrNull { entry ->
            key == entry.aliasKey || key.contains(entry.aliasKey) || entry.aliasKey.contains(key)
        }
        return hit?.brand ?: clean
    }

    fun isKnownBrand(name: String): Boolean {
        val key = toKey(name)
        if (key.isBlank()) return false
        return indexedAliases.any { entry -> key == entry.aliasKey || key.contains(entry.aliasKey) }
    }

    private fun toKey(input: String): String {
        return input
            .lowercase()
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("[^\\p{IsHan}a-z0-9]"), "")
    }

    private data class AliasEntry(
        val brand: String,
        val aliasKey: String
    )
}
