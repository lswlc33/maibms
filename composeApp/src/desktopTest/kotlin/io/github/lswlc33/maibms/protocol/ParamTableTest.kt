package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParamTableTest {

    @Test fun invalid_raw_result_has_user_facing_label() {
        assertEquals("参数输入不合法", ResultCodes.writeResult(-6))
    }

    @Test fun finite_numeric_input_only() {
        listOf("", "NaN", "Infinity", "-Infinity", "1e999", "abc").forEach {
            assertNotNull(ParamTable.parseInput(0, it).error, it)
            assertNull(ParamTable.parseInput(0, it).rawValue, it)
        }
    }

    @Test fun scale_precision_rounds_float_noise_but_never_truncates() {
        assertEquals(2300, ParamTable.parseInput(0, "2.3").rawValue)
        assertEquals(2300, ParamTable.parseInput(0, (2.1 + 0.2).toString()).rawValue)
        assertEquals(3650, ParamTable.parseInput(0, "3,650").rawValue)
        assertEquals("3.650", ParamTable.parseInput(0, "3.650").actualValue)
        assertNotNull(ParamTable.parseInput(0, "3.6505").error)
        assertNotNull(ParamTable.parseInput(154, "20.9").error)
        assertNotNull(ParamTable.parseInput(8, "40.05").error)
        assertNotNull(ParamTable.parseInput(0, "4.501").error)
        assertNotNull(ParamTable.parseInput(0, "1.999").error)
    }

    @Test fun s16_negative_temperature_supports_signed_input_and_wire_word() {
        assertEquals(-2, ParamTable.parseInput(68, "-2").rawValue)
        assertNull(ParamTable.validateRaw(68, -2))
        assertNull(ParamTable.validateRaw(68, 0xFFFE))
        assertNull(ParamTable.validateRaw(68, 0xFFFF))
        assertEquals("-2", ParamTable.formatRaw(0xFFFE, ParamTable.byAddr(68)!!))
        assertNotNull(ParamTable.validateRaw(68, -41))
        assertNotNull(ParamTable.validateRaw(68, 65536))
        assertEquals(ParamEncoding.S16, ParamTable.byAddr(64)!!.encoding)
    }

    @Test fun readonly_dictionary_unknown_and_u16_overflow_rejected() {
        assertNull(ParamTable.validateRaw(152, 0xFAF1))
        assertNotNull(ParamTable.validateRaw(152, 1))
        assertNotNull(ParamTable.parseInput(152, "1").error)
        assertNotNull(ParamTable.validateRaw(166, 113000000))
        assertNotNull(ParamTable.parseInput(166, "113").error)
        assertNotNull(ParamTable.validateRaw(1, 3000))
        assertNotNull(ParamTable.validateRaw(164, 1))
        assertNotNull(ParamTable.validateRaw(378, 65536))
        assertNotNull(ParamTable.validateRaw(378, -1))
        // 374/378 与旧密码布局重叠，保留当前官方参数表业务，不一刀切拒绝。
        assertNull(ParamTable.validateRaw(374, 10))
        assertNull(ParamTable.validateRaw(378, 1000))
    }

    @Test fun u32_capacity_exact_precision_and_unsigned_int_bits() {
        val def = ParamTable.byAddr(162)!!
        val result = ParamTable.parseInput(162, "113.123456")
        assertEquals(113123456, result.rawValue)
        assertEquals("113.123456", result.actualValue)
        assertNotNull(ParamTable.parseInput(162, "113.1234567").error)
        assertNotNull(ParamTable.parseInput(162, "4294.967296").error)
        assertEquals("3000.000000", ParamTable.formatRaw(3000000000L.toInt(), def))
        // 当前业务上限仍为 2000Ah，负 Int 先按位模式解释，再报业务范围而非负数截断。
        assertTrue(ParamTable.validateRaw(162, 3000000000L.toInt())!!.contains("允许范围"))
        assertEquals(3000000000L, ParamTable.rawFromParams(mapOf(162 to 0x5E00, 164 to 0xB2D0), def))
        assertNull(ParamTable.rawFromParams(mapOf(162 to 0x3E40), def))
    }

    @Test fun decimal_step_grid_accepts_values_near_minimum_and_at_maximum() {
        ParamTable.defs.filter { !it.readOnly && it.dict == null && it.step > 0.0 }.forEach { def ->
            listOf(def.min + def.step, def.min + def.step * 2, def.max).filter { it <= def.max }.forEach { value ->
                val result = ParamTable.parseInput(def.addr, value.toString())
                assertNull(result.error, "${def.addr}: $value")
                assertNull(ParamTable.validateRaw(def.addr, result.rawValue!!), "${def.addr}: $value")
            }
        }
    }

    @Test fun every_writable_numeric_def_accepts_its_minimum_without_float_step_false_positive() {
        ParamTable.defs.filter { !it.readOnly && it.dict == null }.forEach { def ->
            val result = ParamTable.parseInput(def.addr, def.min.toString())
            assertNull(result.error, "${def.addr} ${def.name}")
            assertNotNull(result.rawValue)
            assertNull(ParamTable.validateRaw(def.addr, result.rawValue!!), "${def.addr}")
        }
    }

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
