package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 历史事件日志 / 保护动作计数回归（docs/08、附录C ＋ 官方反编译实现）。
 *
 * 没有真机可测，所以这里的期望值来自**两处可核对的硬证据**：
 * 1. 官方小程序自己算出来的请求帧字节（旧版 `helper.js ReadData` 跑出来的
 *    `7e a1 03 00 00 00 99 9d aa 55` / `7e a1 05 00 00 28 99 0b aa 55`）；
 * 2. 文档 §8.6 的样例应答（数据区逐字节给出）。
 * 其余边界（空记录、截断、未知项、保留码）按防御式解析的期望行为断言。
 */
class EventLogTest {

    // ---- 请求帧：与官方逐字节一致 ----

    @Test fun readLogRequestMatchesOfficialBytes() {
        // 官方 ReadData(0,0,3)：7e a1 03 00 00 00 99 9d aa 55
        assertEquals("7E A1 03 00 00 00 99 9D AA 55", hex(EventLogCodec.request(0)))
        // 序号 7（读第 8 条）时只有寄存器两字节变，长度字段仍是 00
        val f = EventLogCodec.request(7)
        assertEquals(listOf(0x7E, 0xA1, 0x03, 0x07, 0x00, 0x00), (0 until 6).map { f[it].toInt() and 0xFF })
        assertEquals(10, f.size)
    }

    @Test fun counterRequestLengthIs40NotZero() {
        // 文档写的是长度 00，官方 ReadData(0,40,5) 实际是 0x28 —— 按官方，否则设备不认
        assertEquals("7E A1 05 00 00 28 99 0B AA 55", hex(EventLogCodec.countersRequest()))
    }

    // ---- 应答解析：文档 §8.6 样例 ----

    @Test fun parsesDocumentedSampleRecord() {
        // 02 00 日志序号=2 | 03 条数=3 | 02 单体过压保护 | 时间戳 | 3 个数据项
        val data = hex("02 00 03 02 6E 5C 3F 68 0A 00 78 0E 01 00 84 03 03 00 4A 00")
        val rec = EventLogCodec.parse(parseOne(2, data))!!
        assertEquals(2, rec.index, "序号取帧头回显（官方两个 BLE 解析器都不读数据区那 2 字节）")
        assertEquals(2, rec.eventCode)
        assertEquals("单体过压保护", rec.eventName)
        assertEquals(1748982894L, rec.timeRaw, "0x683F5C6E；文档 §8.6 写的 1748986990 是算错了（附录E 已记）")
        assertEquals(
            listOf(
                Triple(10, "最大单体电压", "3.704V"),
                Triple(1, "总压", "90.0V"),
                Triple(3, "SOC", "74%"),
            ),
            rec.items.map { Triple(it.id, it.name, it.value) },
        )
    }

    @Test fun timeIsEpochWhenPlausibleUnixSeconds() {
        val rec = EventLogCodec.parse(parseOne(0, hex("00 00 01 02 6E 5C 3F 68")))!!
        assertTrue(EventLogCodec.isUnixTime(rec.timeRaw))
        // 不做时区相关的逐字符断言（测试机时区不定）：只要不是「运行 …」且形如 MM-dd HH:mm 即可
        assertFalse(rec.timeText.startsWith("运行"))
        assertTrue(Regex("""\d\d-\d\d \d\d:\d\d""").matches(rec.timeText), "实际=${rec.timeText}")
    }

    @Test fun timeIsRuntimeWhenTooSmallForUnixSeconds() {
        // 固件按"设备运行时长"上报时（三个官方实现都这么显示），值远小于 1.6e9
        assertFalse(EventLogCodec.isUnixTime(90_061))
        assertEquals("运行 1天01:01:01", EventLogCodec.formatTime(90_061))
        assertEquals("运行 01:15:30", EventLogCodec.formatTime(4_530))
        assertEquals("运行 00:00:05", EventLogCodec.formatTime(5))
    }

    @Test fun emptyRecordMeansEndOfLog() {
        // 官方判据是帧长度字段为 0 —— 这是"读到到底了"，不是解析失败
        assertNull(EventLogCodec.parse(parseOne(9, ByteArray(0))))
        // 项数 K = 0 同样算空
        assertNull(EventLogCodec.parse(parseOne(9, hex("09 00 00 02 00 00 00 00"))))
        // 数据区不足 8 字节头也当空，不抛异常
        assertNull(EventLogCodec.parse(parseOne(9, hex("09 00 00"))))
    }

    @Test fun unknownItemDoesNotDesyncFollowingItems() {
        // 官方两个 BLE 解析器在未知项处只吞 1 字节，后续项全错位（实测 bug）。
        // 99 是未定义项号：这里必须吞满 4 字节，末尾那个「总压」才还能解对
        val data = hex("00 00 03 02 00 00 00 00" + "0A 00 78 0E" + "63 00 34 12" + "01 00 84 03")
        val rec = EventLogCodec.parse(parseOne(0, data))!!
        assertEquals(3, rec.items.size)
        assertEquals("未知数据项 99", rec.items[1].name)
        assertEquals("4660", rec.items[1].value, "未知项给原始值（0x1234），便于对照固件新增项")
        assertEquals(Triple(1, "总压", "90.0V"), Triple(rec.items[2].id, rec.items[2].name, rec.items[2].value))
    }

    @Test fun signedItemsAreDecodedAsS16() {
        // 符号标志 0x80 → s16：电流 0xFF9C = -100 → -10.0A
        val data = hex("00 00 01 02 00 00 00 00 02 80 9C FF")
        val rec = EventLogCodec.parse(parseOne(0, data))!!
        assertEquals("-10.0A", rec.items.single().value)
    }

    @Test fun truncatedRecordKeepsWhatIsParsable() {
        // K 声明 3 项但只给了 1 项半：不能抛异常，也不能把半项当整项
        val data = hex("00 00 03 02 00 00 00 00" + "0A 00 78 0E" + "01 00 84")
        val rec = EventLogCodec.parse(parseOne(0, data))!!
        assertEquals(1, rec.items.size)
        assertEquals("3.704V", rec.items.single().value)
    }

    @Test fun garbageItemCountIsCapped() {
        // K 是设备给的字节，255 不能直接当循环次数用（防御脏数据）
        val data = ByteArray(8 + 4 * 3)
        data[2] = 0xFF.toByte()
        data[3] = 2
        val rec = EventLogCodec.parse(parseOne(0, data))!!
        assertEquals(3, rec.items.size, "有多少数据解多少，不越界")
    }

    // ---- 表：事件名 / 数据项名 ----

    @Test fun eventNamesCoverKnownRangeAndDegradeSafely() {
        assertEquals("电池类型未设置", EventLogCodec.eventName(1))
        assertEquals("单体过压保护", EventLogCodec.eventName(2))
        assertEquals("均衡线掉串", EventLogCodec.eventName(42), "官方表里带尾随制表符，需清干净")
        assertEquals("电池充电中", EventLogCodec.eventName(65))
        assertEquals("充电器连接S", EventLogCodec.eventName(69))
        assertEquals("电池充满", EventLogCodec.eventName(64))
        assertEquals("充电结束", EventLogCodec.eventName(83))
        assertEquals("模组失联", EventLogCodec.eventName(84))
        assertEquals("验证9级权限", EventLogCodec.eventName(85))
        assertEquals("保存应用参数", EventLogCodec.eventName(89))
        assertEquals("加热异常保护", EventLogCodec.eventName(94))
        // 95+ 官方就是占位，不编含义
        assertEquals("未知事件(95)", EventLogCodec.eventName(95))
        assertEquals("未知事件(0)", EventLogCodec.eventName(0))
        assertEquals("未知事件(200)", EventLogCodec.eventName(200))
    }

    @Test fun itemTableUsesNewVersionRatios() {
        // 文档标了新旧倍率冲突的两项，按新版（官方 dict.js range/unit）
        assertEquals("90.0V", itemValue(1, 900))       // 总压 ÷10（旧版表是 ÷1000，会解成 0.9V）
        assertEquals("20.0A", itemValue(28, 200))      // 最大充电电流 ÷10
        assertEquals("3.704V", itemValue(10, 3704))
        assertEquals("74%", itemValue(3, 74))
        assertEquals("1.510V", itemValue(15, 1510), "单体压差 ÷1000")
        assertEquals("22℃", itemValue(4, 22))
        assertEquals("30min", itemValue(27, 30))
    }

    @Test fun itemTableCoversThirtySevenEntries() {
        // 新版表给 33~37 也定了名（文档只到 32）
        assertEquals("未跳线前均衡串数", EventLogCodec.itemName(33))
        assertEquals("跳线后均衡串数", EventLogCodec.itemName(34))
        assertEquals("均衡故障码", EventLogCodec.itemName(35))
        assertEquals("加热mos状态", EventLogCodec.itemName(36))
        assertEquals("HDSV", EventLogCodec.itemName(37))
        // 38 起仍是占位
        assertEquals("未知数据项 38", EventLogCodec.itemName(38))
        assertEquals("未知数据项 200", EventLogCodec.itemName(200))
    }

    @Test fun enumItemsAreRenderedAsText() {
        // 20/21 与实时帧尾部的 MOS 状态字节是同一套码表 → 复用 MosStateDict，别出现两份会漂移的表
        assertEquals("单体过压", itemValue(20, 2))
        assertEquals("开启", itemValue(20, 1))
        assertEquals("关闭", itemValue(20, 0))
        assertEquals("放电管异常", itemValue(20, 13))
        assertEquals("短路保护", itemValue(21, 12), "放电 12 是短路保护（充电 12 是开启失败，两张表不能混）")
        assertEquals("状态码 31", itemValue(21, 31), "保留码不编含义")
        assertEquals("弱电开关关机", itemValue(25, 2))
        assertEquals("三方协议复位", itemValue(26, 4))
        assertEquals("磷酸铁锂", itemValue(32, 0xFAF2), "电池类型沿用保护板 0xFAFx 口径")
        assertEquals("未知", itemValue(32, 0x1234))
    }

    // ---- 计数器 ----

    @Test fun countersDecodeInDocumentedOrder() {
        val data = ByteArray(40)
        for (i in 0 until 20) {
            data[2 * i] = (i + 1).toByte()          // 1..20
            data[2 * i + 1] = 0
        }
        assertEquals((1..20).toList(), EventLogCodec.parseCounters(data))
        assertEquals(20, EventLogCodec.counterNames.size)
        assertEquals("循环次数", EventLogCodec.counterNames[EventLogCodec.CYCLE_COUNTER_INDEX])
        assertEquals("单体过压保护", EventLogCodec.counterNames[0])
        assertEquals("异常复位次数", EventLogCodec.counterNames[19])
    }

    @Test fun partialCountersStillDecode() {
        // 设备只回了 3 个计数器（或帧被截断）时，返回能解出来的前缀而不是整块丢弃
        assertEquals(listOf(1, 2, 3), EventLogCodec.parseCounters(byteArrayOf(1, 0, 2, 0, 3, 0)))
        assertEquals(emptyList(), EventLogCodec.parseCounters(byteArrayOf(1)))
    }

    // ---- 工具 ----

    /** 造一条 `0x13` 长帧并过一遍真解帧器（不直接构造 ParsedFrame，顺带验证帧结构可达） */
    private fun parseOne(index: Int, data: ByteArray): ParsedFrame {
        val frame = Frame.build(Proto.ADDR_MAIN, Proto.RSP_LOG, index, data, 0)
        val segs = FrameParser().feed(frame)
        assertEquals(1, segs.size, "造的帧应能被解帧器原样解出")
        return segs.single()
    }

    private fun itemValue(id: Int, raw: Int): String {
        val data = ByteArray(8 + 4)
        data[2] = 1
        data[3] = 2
        data[8] = id.toByte()
        data[9] = 0
        data[10] = (raw and 0xFF).toByte()
        data[11] = ((raw shr 8) and 0xFF).toByte()
        return EventLogCodec.parse(parseOne(0, data))!!.items.single().value
    }

    private fun hex(s: String): ByteArray {
        // 直接抹掉所有空白再按字符对切：多段字符串拼接时不会因为漏一个空格把两个字节粘成一个
        val t = s.filterNot { it.isWhitespace() }
        require(t.length % 2 == 0) { "hex 串长度应为偶数：${t.length}" }
        return ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** 帧 → 空格分隔的十六进制串（与官方 builder 的输出逐字符比对用） */
private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
