package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ParamTableTest {

    @Test
    fun temperature_minus_one_is_not_mistaken_for_permission_sentinel() {
        val def = ParamTable.byAddr(68)!!   // 电池低温充电保护，min=-40，按 s16 解释
        // 同一块参数里还有其他正常值 → 0xFFFF 是合法的 -1℃，不能当“未披露”
        assertEquals("-1", ParamTable.format(mapOf(68 to 0xFFFF, 70 to 0x0000), def))
    }

    @Test
    fun all_ffff_block_is_treated_as_not_disclosed() {
        val def = ParamTable.byAddr(68)!!
        assertEquals("--", ParamTable.format(mapOf(68 to 0xFFFF, 70 to 0xFFFF), def))
    }

    @Test
    fun unsigned_param_still_treats_ffff_as_not_disclosed() {
        val def = ParamTable.byAddr(8)!!    // 总压过压保护电压，min>=0
        assertEquals("--", ParamTable.format(mapOf(8 to 0xFFFF, 10 to 0x0000), def))
    }

    @Test
    fun u32_capacity_all_ffff_is_not_disclosed() {
        val def = ParamTable.byAddr(162)!!  // 电池物理容量，u32 / 1e6
        // 权限不足时低/高字都是 0xFFFF；不能拼成 0xFFFFFFFF 显示 4295.0 Ah
        assertEquals("--", ParamTable.format(mapOf(162 to 0xFFFF, 164 to 0xFFFF), def))
    }

    @Test
    fun u32_capacity_reads_low_and_high_words() {
        val def = ParamTable.byAddr(162)!!
        // 113.0 Ah = 113_000_000 = 0x06BC3E40 → 低字 0x3E40、高字 0x06BC
        assertEquals("113.0", ParamTable.format(mapOf(162 to 0x3E40, 164 to 0x06BC), def))
    }
}
