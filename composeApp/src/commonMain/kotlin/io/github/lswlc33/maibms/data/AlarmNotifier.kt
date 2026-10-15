package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.BitDict

/** 一条该通知的告警：[isProtect] 区分保护域与告警域（位号在两域里会重复） */
data class Alert(val bit: Int, val name: String, val isProtect: Boolean)

/**
 * 保护/告警的**上升沿**检测（只报"新出现的"，不报持续状态）。
 *
 * 位域是电平语义（位为 1 = 此刻成立），直接按位报会在每一拍都命中同一个保护；
 * 用户要的是"什么时候出的问题"，所以只在 0→1 的那一拍报一次。
 *
 * 时间由调用方传入（[nowMs]），这里不读时钟，方便单测。
 */
class AlarmNotifier(
    /** 同一位在冷却期内不重复通知：既挡住反复抖动（保护→恢复→保护），又不屏蔽真正的新一次触发 */
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var lastProtect = 0UL
    private var lastWarn = 0UL
    private val lastAlertAt = HashMap<Int, Long>()

    /**
     * 喂一帧位域，返回**本次该通知**的条目（可能为空）。
     *
     * 覆盖范围与告警卡/详情弹窗同一套规则：保护位全体 + 告警位里**非状态位**的那些
     * （[BitDict.decodeForDisplayPairs] 用的就是同一个过滤，所以"MOS 开/待机中/放电中"
     * 这类常态位不会把人半夜吵醒）。
     *
     * 冷却期内被压掉的上升沿**算已消费**：位仍保持置位、只是不再通知（没有新信息）；
     * 若位落下后再一次置起且已过冷却，则算"又一次触发"，照常报。
     */
    fun onFrame(protect: ULong, warn: ULong, nowMs: Long): List<Alert> {
        val risen = risenOf(lastProtect, protect, BitDict.protectNames, isProtect = true) +
                risenOf(lastWarn, warn, BitDict.warnNames, isProtect = false)
        lastProtect = protect
        lastWarn = warn
        val out = ArrayList<Alert>(risen.size)
        for (a in risen) {
            val key = (if (a.isProtect) DOMAIN_PROTECT else DOMAIN_WARN) * KEY_DOMAIN_STRIDE + a.bit
            val last = lastAlertAt[key]
            if (last != null && nowMs - last < cooldownMs) continue
            lastAlertAt[key] = nowMs
            out += a
        }
        return out
    }

    /** 会话切换/断开时清空：换设备不能让上一台的位域当成本台的"已有状态" */
    fun reset() {
        lastProtect = 0UL
        lastWarn = 0UL
        lastAlertAt.clear()
    }

    private fun risenOf(
        prev: ULong,
        now: ULong,
        dict: Map<Int, String>,
        isProtect: Boolean,
    ): List<Alert> {
        val pairs = if (isProtect) BitDict.decodePairs(now, dict)
        else BitDict.decodeForDisplayPairs(now, dict)
        // 只留"上一帧没有、这一帧有"的。首帧（prev 全 0）时设备当前的置位都算新出现——
        // 那正是"连上时已经在保护"的情形，是否打扰由调用方按前台/后台决定
        return pairs
            .filter { (prev shr it.first) and 1UL == 0UL }
            .map { Alert(it.first, it.second, isProtect) }
    }

    companion object {
        /**
         * 同一位的通知冷却：5 分钟。
         *
         * 取 5 分钟而不是更长：保护在阈值附近来回抖动（保护→恢复→保护）是同一个事故，
         * 一次提醒就够；但真正隔了几分钟的第二次触发是有价值的信息，不能被"同类合并"吃掉。
         * 同一个通知 id 也让重复通知在通知栏里互相替换，不会堆成一摞。
         */
        const val DEFAULT_COOLDOWN_MS = 5 * 60_000L

        private const val DOMAIN_PROTECT = 0
        private const val DOMAIN_WARN = 1
        private const val KEY_DOMAIN_STRIDE = 1000
    }
}
