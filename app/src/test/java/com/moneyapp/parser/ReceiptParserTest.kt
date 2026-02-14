package com.moneyapp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser()

    @Test
    fun parseWechatBill() {
        val text = """
            12:20 2/13
            智慧商户通
            茅台酱香
            -3.00
            当前状态
            支付成功
            支付时间
            2026年2月11日11:12:43
            商品
            209850020192971555
            商户全称
            商户_王亚超
            收单机构
            易生支付有限公司
            支付方式
            中国银行储蓄卡(3303)
            交易单号
            4200003019202602118844168674
            商户单号
            9941776513675870396416
        """.trimIndent()

        val result = parser.parse(text)
        assertEquals(-300L, result.amountMinor)
        assertEquals("CNY", result.currency)
        assertEquals("王亚超", result.merchant)
        assertEquals("3303", result.cardTail)
        assertEquals("Success", result.status)
        assertNotNull(result.payTime)
        assertEquals("4200003019202602118844168674", result.orderId)
    }

    @Test
    fun parseAlipayBill() {
        val text = """
            账单详情
            福福饼店·金牌酥皮菠萝包 (嘉定宝龙店)
            -9.00
            交易成功
            支付时间
            2026-02-05 18:17:42
            付款方式
            中国银行储蓄卡(3303）>
            商品说明
            美团收银909700209213949975
            收单机构
            北京钱袋宝支付技术有限公司
            收款方全称
            上海市嘉定区菊园新区颖福面包坊(个体工商户)
        """.trimIndent()

        val result = parser.parse(text)
        assertEquals(-900L, result.amountMinor)
        assertEquals("CNY", result.currency)
        assertEquals("福福饼店·金牌酥皮菠萝包 (嘉定宝龙店)", result.merchant)
        assertEquals("3303", result.cardTail)
        assertEquals("Success", result.status)
        assertNotNull(result.payTime)
        assertEquals("餐饮", result.categoryGuess)
    }

    @Test
    fun parseIncomeAmountWithPlusSign() {
        val text = """
            收款成功
            金额
            +3O00
            收款方
            ****大*店
            商户全称
            上海某某大卖场有限公司
        """.trimIndent()

        val result = parser.parse(text)
        assertEquals(300000L, result.amountMinor)
        assertTrue(result.merchant.orEmpty().contains("上海").not())
        assertTrue(result.amountConfidence >= 70)
    }

    @Test
    fun normalizeMajorMerchantAliases() {
        val text = """
            账单详情
            美团收银909700209213949975
            -29.90
            交易成功
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals("美团", result.merchant)
    }

    @Test
    fun normalizeCoffeeBrandAlias() {
        val text = """
            支付成功
            Luckin Coffee 瑞幸咖啡(软件园店)
            -18.00
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals("瑞幸咖啡", result.merchant)
    }

    @Test
    fun shouldNotUseOrderDetailAsMerchant() {
        val text = """
            订单详情
            -19.90
            交易成功
            收款方
            美团收银
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals("美团", result.merchant)
    }
}
