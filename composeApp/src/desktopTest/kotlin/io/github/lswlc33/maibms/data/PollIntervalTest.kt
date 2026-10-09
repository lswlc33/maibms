package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 轮询频率三档预设：900/600/300，默认 600；读写都吸附到最近档位；
 * 旧测试项键（test.pollIntervalMs）里的任意值读取时吸附迁移。
 * 键名写字符串字面量：AppStore 里的常量是 private，测试要直接摆布原始存储。
 */
class PollIntervalTest {

    private fun reset() {
        AppStore.put("device.pollIntervalMs", null)
        AppStore.put("test.pollIntervalMs", null)
    }

    @Test fun defaultIs600AndOrderIsUiOrder() {
        reset()
        assertEquals(600, AppStore.pollIntervalMs)
        assertEquals(600, POLL_INTERVAL_DEFAULT_MS)
        assertEquals(listOf(900, 600, 300), POLL_INTERVAL_PRESETS_MS, "顺序即界面展示顺序")
    }

    @Test fun writesSnapToNearestPreset() {
        reset()
        AppStore.pollIntervalMs = 600
        assertEquals(600, AppStore.pollIntervalMs)
        AppStore.pollIntervalMs = 50
        assertEquals(300, AppStore.pollIntervalMs)
        AppStore.pollIntervalMs = 880
        assertEquals(900, AppStore.pollIntervalMs)
        AppStore.pollIntervalMs = 640
        assertEquals(600, AppStore.pollIntervalMs)
        AppStore.pollIntervalMs = 2000
        assertEquals(900, AppStore.pollIntervalMs)
        reset()
    }

    @Test fun legacyTestKeyMigratesBySnapping() {
        reset()
        AppStore.put("test.pollIntervalMs", "450")   // 与 300/600 并列等距 → 取靠前的 600
        assertEquals(600, AppStore.pollIntervalMs)
        AppStore.put("test.pollIntervalMs", "100")
        assertEquals(300, AppStore.pollIntervalMs)
        AppStore.put("test.pollIntervalMs", "990")
        assertEquals(900, AppStore.pollIntervalMs)
        reset()
    }

    @Test fun newKeyWinsOverLegacy() {
        reset()
        AppStore.put("test.pollIntervalMs", "300")
        AppStore.pollIntervalMs = 900
        assertEquals(900, AppStore.pollIntervalMs)
        reset()
    }
}
