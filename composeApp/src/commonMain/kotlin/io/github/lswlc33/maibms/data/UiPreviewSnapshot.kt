package io.github.lswlc33.maibms.data

/**
 * UI 预览快照：内置的一张**特殊静态快照**（不经真实连接产生，也不会写进快照库），
 * 给「设置 → 外观 → UI 预览」用——没接保护板时也能看一整套真实排版的界面。
 *
 * 预设机型：72V 级 21 串三元锂 114Ah（放电中、均衡中、带一条告警），
 * 数据自洽（单体×串数≈总压、最高−最低=压差），用于检查 UI 在不同数值/长度下的表现。
 */
object UiPreview {

    const val DEVICE_NAME = "UI 预览 · 72114 锂电池"
    const val ID = -1L   // 内置快照的固定 id：永不入库（AppStore 快照 id 都是正数）

    fun snapshot(): BmsSnapshot {
        val cells = listOf(
            4.178, 4.186, 4.212, 4.181, 4.190, 4.188, 4.175, 4.153, 4.184, 4.190, 4.187,
            4.182, 4.176, 4.189, 4.191, 4.185, 4.180, 4.186, 4.183, 4.188, 4.185,
        )
        val status = BmsStatus(
            deviceName = DEVICE_NAME,
            runtime = "12天 03:26:15",
            swVersion = "MAIBMS-SW-1.0.0",
            hwVersion = "MAIBMS-HW-1.2",
            batteryType = "三元锂",
            connected = false,
            hasData = true,
            permissionLevel = 3,
            soc = 76,
            totalCapAh = 114.0,
            remainCapAh = 86.6,
            soh = 98,
            cycles = 96,
            totalVoltage = 87.87,
            // 卡3 的口径：正=放电（进度条按放电功率推进）、负=充电（待机）。
            // 预设是「放电中」，功率取正才能把换挡条画出来；早先配 -1090 会让进度条恒为 0，
            // 与「放电」状态自相矛盾
            current = 12.4,
            power = 1090,
            maxCell = "4.212", minCell = "4.153", avgCell = "4.184", deltaCell = "0.059",
            totalCycleAh = 68,
            battState = "放电",
            chMos = "关闭", disMos = "开启", balance = "均衡中",
            protectList = emptyList(),
            alarmList = listOf("放电过流告警"),
            protectPairs = emptyList(),
            alarmPairs = listOf(11 to "放电过流告警"),
            temps = listOf("MOS" to 41.0, "均衡" to 33.0, "T1" to 28.5, "T2" to 31.2, "T3" to 29.8, "T4" to 27.6),
            cells = cells.mapIndexed { i, v ->
                CellV(
                    index = i + 1, volt = v,
                    isMax = v == cells.max(), isMin = v == cells.min(),
                    balancing = i + 1 == 5 || i + 1 == 12,
                )
            },
            // 预设的归一化趋势形状：放电电流的锯齿 + 缓慢爬升的电压
            trendCurrent = (0 until 40).map { i -> (0.58f + 0.30f * kotlin.math.sin(i / 5.5f)).coerceIn(0f, 1f) },
            trendVolt = (0 until 40).map { i -> 0.72f + 0.22f * (i / 39f) },
        )
        return BmsSnapshot(
            id = ID,
            deviceAddress = null,
            deviceName = DEVICE_NAME,
            timeLabel = "预设",
            status = status,
            liveParams = PREVIEW_PARAMS,
            identity = mapOf(
                "boot" to "MAIBMS-BOOT-1.0.0",
                "codeKey" to "PREVIEW",
                "hwVersion" to "MAIBMS-HW-1.2",
                "swVersion" to "MAIBMS-SW-1.0.0",
                "packId" to "72114-UI-PREVIEW-0001",
            ),
        )
    }

    /** 参数区：沿用真机读回的一组实测值，容量改成 114Ah（162/164 是 u32 低/高字） */
    private val PREVIEW_PARAMS = mapOf(
        0 to 4300, 2 to 4250, 4 to 4400, 6 to 4300, 8 to 870, 10 to 860,
        12 to 2900, 14 to 3200, 16 to 2000, 18 to 2200, 20 to 10, 22 to 10,
        24 to 10, 26 to 10, 32 to 4250, 34 to 4200, 36 to 1008, 38 to 996,
        40 to 3200, 42 to 3300, 44 to 10, 46 to 10, 48 to 800, 50 to 700,
        56 to 60, 58 to 55, 60 to 60, 62 to 55, 64 to 80, 66 to 65,
        68 to 65534, 70 to 2, 72 to 65526, 74 to 65531,
        104 to 500, 106 to 5, 108 to 2000, 110 to 5, 112 to 3000, 114 to 1000,
        116 to 3000, 118 to 200, 124 to 45, 126 to 40, 128 to 150, 130 to 120,
        132 to 20, 134 to 10, 140 to 4300, 142 to 3900, 144 to 10, 146 to 2,
        148 to 180, 150 to 100, 152 to 0xFAF1, 154 to 20, 156 to 10, 158 to 3100,
        160 to 200, 162 to 10752, 164 to 1739,   // 114.0Ah = 114000000µAh 的低/高字
        174 to 4200, 176 to 4100,
        298 to 3900, 300 to 1800, 302 to 3547, 304 to 15, 306 to 0, 308 to 20,
        310 to 2998,
    )
}
