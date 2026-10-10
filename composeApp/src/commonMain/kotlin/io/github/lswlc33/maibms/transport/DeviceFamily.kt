package io.github.lswlc33.maibms.transport

import kotlinx.serialization.Serializable

/** 设备大类：保护板（原生 BMS）/ 电量计（“中继器”，自己连保护板再把数据转发给手机）。 */
enum class DeviceKind { Board, Meter }

/**
 * 设备家族：**按蓝牙广播名判定**。
 *
 * 名称规则来自官方小程序解包（`unpack/解包结果/`）与真机确认：
 * - 蚂蚁保护板：名字以 `ANT` 开头（本仓库既有实现）；
 * - 蓝宝电量计：`utils/btls/ble.js` 的 `regName = "BlueBabe"`，品牌前缀 `blue` / `lb`
 *   （陆行小程序 `pages/mianEM02/EM02.js` 的 `batteryOptions` 里 `蓝宝=blue/lb`）；
 * - 陆行电量计：`pages/DeviceConnect_EM/eviceConnect.js` 里 `name.startsWith("EM2APP")`。
 *
 * 匹配规则：广播名转大写后按**最长前缀优先**匹配，避免 `lb` 这类短前缀误伤
 * （`BlueBabe` 先于 `lb` 命中）。
 *
 * 注：电领（`DL`）因读数必须联网登录 + 正版校验（含防克隆自毁），已按需求**移除**，不再识别。
 */
@Serializable
enum class DeviceFamily(
    val label: String,
    val kind: DeviceKind,
    /** 广播名前缀（大写）；按长度降序参与匹配 */
    val namePrefixes: List<String>,
) {
    Ant("蚂蚁保护板", DeviceKind.Board, listOf("ANT")),
    LanBao("蓝宝电量计", DeviceKind.Meter, listOf("BLUEBABE", "BLUE", "LB")),
    LuXing("陆行电量计", DeviceKind.Meter, listOf("EM2APP")),
    Unknown("未知设备", DeviceKind.Board, emptyList());

    val isMeter: Boolean get() = kind == DeviceKind.Meter

    companion object {
        /**
         * 陆行同厂控制器（EM 产品同厂的“车机/控制器”型号）广播名以 `CJ01` 开头，
         * 不是本方案要连的目标——扫描时显式排除。
         */
        const val CONTROLLER_PREFIX = "CJ01"

        /** 前缀 → 家族，按前缀长度降序（最长/最具体优先） */
        private val byPrefix: List<Pair<String, DeviceFamily>> =
            DeviceFamily.values().toList()
                .flatMap { f -> f.namePrefixes.map { it.uppercase() to f } }
                .sortedByDescending { it.first.length }

        /** 广播名 → 家族；未命中任何已知前缀返回 null（扫描时丢弃） */
        fun matchName(name: String?): DeviceFamily? {
            val n = name?.trim()?.uppercase() ?: return null
            if (n.isEmpty()) return null
            return byPrefix.firstOrNull { n.startsWith(it.first) }?.second
        }

        /** 该广播名是否是要连接的目标（已知家族且不是控制器） */
        fun isTarget(name: String?): Boolean {
            val n = name?.trim()?.uppercase() ?: return false
            if (n.startsWith(CONTROLLER_PREFIX)) return false
            return matchName(n) != null
        }
    }
}
