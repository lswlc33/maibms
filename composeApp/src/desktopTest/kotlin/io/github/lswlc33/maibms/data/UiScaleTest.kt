package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 界面大小四档预设：0.85 / 1.0 / 1.15 / 1.3，默认 1.0（标准）；读写都吸附到最近档位，
 * 非法值/空值兜底回标准。缩放本身的落地在 `ui/UiScale.kt`（覆盖 LocalDensity）。
 *
 * 键名写字符串字面量：AppStore 里的常量是 private，测试要直接摆布原始存储。
 */
class UiScaleTest {

    private fun reset() = AppStore.put("ui.scale", null)

    @Test fun defaultIsStandardAndOrderIsUiOrder() {
        reset()
        assertEquals(1.0f, AppStore.uiScale)
        assertEquals(1.0f, UI_SCALE_DEFAULT)
        assertEquals(
            listOf(0.85f to "小", 1.0f to "标准", 1.15f to "大", 1.3f to "特大"),
            UI_SCALE_PRESETS,
            "顺序即界面展示顺序；档位名与比例成对存放，防止两者错位",
        )
    }

    @Test fun writesSnapToNearestPreset() {
        reset()
        AppStore.uiScale = 1.15f
        assertEquals(1.15f, AppStore.uiScale)
        AppStore.uiScale = 0.9f
        assertEquals(0.85f, AppStore.uiScale)
        AppStore.uiScale = 1.2f
        assertEquals(1.15f, AppStore.uiScale)
        AppStore.uiScale = 5f
        assertEquals(1.3f, AppStore.uiScale)
        AppStore.uiScale = 0.1f
        assertEquals(0.85f, AppStore.uiScale)
        reset()
    }

    @Test fun garbageOrMissingFallsBackToStandard() {
        reset()
        assertEquals(1.0f, AppStore.uiScale, "未设置 = 标准档")
        AppStore.put("ui.scale", "abc")
        assertEquals(1.0f, AppStore.uiScale, "非法值兜底回标准档，不能把界面缩放搞成 0 或 NaN")
        AppStore.put("ui.scale", "1.05")
        assertEquals(1.0f, AppStore.uiScale)
        reset()
    }
}
