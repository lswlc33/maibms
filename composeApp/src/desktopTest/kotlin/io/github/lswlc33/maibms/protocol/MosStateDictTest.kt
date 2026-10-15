package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MOS / 均衡状态码表回归（docs/13 §13.3 ＋ 新版 `dict.js` 的 `SyslogDataItemEn` 第 20/21 项）。
 *
 * 盯住两件事：
 * 1. 表值不能错位 —— 这张表是「MOS 为什么关了」的唯一解释来源，串一位就会给出错误结论；
 * 2. 保留码不许编含义 —— 官方表里 11/19/21/22/24… 是占位数字，只能原样报码。
 */
class MosStateDictTest {

    @Test fun chargeMosTableMatchesDocs13() {
        val table = mapOf(
            2 to "单体过压", 3 to "过流保护", 4 to "电池充满", 5 to "总压过压",
            6 to "电池过温", 7 to "功率过温", 8 to "电流异常", 9 to "均衡线掉串",
            10 to "主板过温", 12 to "开启失败", 13 to "放电管异常", 14 to "等待",
            15 to "手动关闭", 16 to "二级过压", 17 to "低温保护", 18 to "压差超限",
            20 to "自检异常", 23 to "与控制开关2关联",
        )
        for ((code, name) in table) {
            assertEquals(name, MosStateDict.chargeReason(code), "充电 MOS 状态码 $code")
        }
    }

    @Test fun dischargeMosTableMatchesDocs13() {
        val table = mapOf(
            2 to "单体欠压", 3 to "过流保护", 4 to "二级过流", 5 to "总压欠压",
            6 to "电池过温", 7 to "功率过温", 8 to "电流异常", 9 to "均衡线掉串",
            10 to "主板过温", 11 to "充电开启", 12 to "短路保护", 13 to "放电管异常",
            14 to "开启失败", 15 to "手动关闭", 16 to "二级欠压", 17 to "低温保护",
            18 to "压差超限", 19 to "自检异常", 23 to "与控制开关2关联",
        )
        for ((code, name) in table) {
            assertEquals(name, MosStateDict.dischargeReason(code), "放电 MOS 状态码 $code")
        }
    }

    @Test fun openAndCloseHaveNoReason() {
        // 0=关闭、1=开启由开关读数本身表达；再附一句「关闭·关闭」只是噪声
        for (code in listOf(0, 1)) {
            assertNull(MosStateDict.chargeReason(code))
            assertNull(MosStateDict.dischargeReason(code))
        }
        assertNull(MosStateDict.balanceMode(0))
    }

    @Test fun reservedCodesAreNeverGivenAMeaning() {
        // 官方表里这些是占位数字（"11"/"19"/"21"/"22"/"24"…），不是含义；
        // 特别注意充电 11 是保留、放电 11 是「充电开启」，两张表**不能共用**一份码表
        for (code in listOf(11, 19, 21, 22, 24, 25, 31, 99)) {
            assertNull(MosStateDict.chargeReason(code), "充电 MOS 保留码 $code 不该有文案")
        }
        assertNull(MosStateDict.dischargeReason(22))
        assertNull(MosStateDict.dischargeReason(31))
        assertEquals("充电开启", MosStateDict.dischargeReason(11))
    }

    @Test fun detailTextKeepsTheRawCode() {
        // 详情弹窗要能看到原始码：保留码/超范围码也不丢信息
        assertEquals("开启", MosStateDict.chargeText(1))
        assertEquals("关闭", MosStateDict.chargeText(0))
        assertEquals("关闭 · 单体过压（状态码 2）", MosStateDict.chargeText(2))
        assertEquals("关闭 · 状态码 11（未定义）", MosStateDict.chargeText(11))
        assertEquals("状态码 200（未定义）", MosStateDict.balanceText(200))
        assertEquals("自动均衡", MosStateDict.balanceText(4))
        assertEquals("关闭", MosStateDict.balanceText(0))
    }

    @Test fun balanceModesMatchDocs13() {
        val table = mapOf(1 to "极限均衡", 2 to "充电压差", 3 to "均衡过温", 4 to "自动均衡", 5 to "手动平衡")
        for ((code, name) in table) assertEquals(name, MosStateDict.balanceMode(code), "均衡状态码 $code")
        assertNull(MosStateDict.balanceMode(6))
        assertNull(MosStateDict.balanceMode(15))
    }
}
