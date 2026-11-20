package io.github.lswlc33.maibms.transport

import kotlinx.serialization.Serializable

/** 设备大类：保护板（原生 BMS）/ 电量计（“中继器”，自己连保护板再把数据转发给手机）。 */
enum class DeviceKind { Board, Meter }

/**
 * 设备家族：广播名称与服务用于提示，用户可以手动指定板型；连接后的报文校验才决定可用读数。
 *
 * 原有名称规则来自官方小程序，新板型参考 docs/16-彦阳与嘉佰达只读接入.md：
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
    YanYang("彦阳保护板", DeviceKind.Board, listOf("YYBMS", "YY-", "彦阳")),
    Jbd("嘉佰达保护板", DeviceKind.Board, listOf("JBD", "SP", "DB", "嘉佰达")),
    Jikong("极空保护板", DeviceKind.Board, listOf("JK-", "JK_", "极空")),
    Yadi("亚迪智电", DeviceKind.Board, listOf("亚迪智电")),
    Xingheng("星恒星蓝", DeviceKind.Board, listOf("星恒星蓝", "星恒", "XINGHENG")),
    Mingtang("明唐保护板", DeviceKind.Board, listOf("明唐", "MINGTANG")),
    Zhiba("智霸锂电", DeviceKind.Board, listOf("智霸锂电", "智霸")),
    Shanchong("闪充智行", DeviceKind.Board, listOf("闪充智行")),
    Xiaoha("小哈锂电", DeviceKind.Board, listOf("小哈锂电", "小哈智电")),
    LanBao("蓝宝电量计", DeviceKind.Meter, listOf("BLUEBABE", "BLUE", "LB")),
    LuXing("陆行电量计", DeviceKind.Meter, listOf("EM2APP")),
    Unknown("未知设备", DeviceKind.Board, emptyList());

    val isMeter: Boolean get() = kind == DeviceKind.Meter
    val isReadOnlyBoard: Boolean get() = kind == DeviceKind.Board && !usesAntProtocol
    val usesAntProtocol: Boolean get() = this == Ant || this == Unknown
    /** 识别品牌与已实现直连协议分开，尚无协议的设备不能落入 ANT 读取/控制。 */
    val supportsDirectConnection: Boolean get() = this !in listOf(Xingheng, Zhiba, Shanchong, Xiaoha)
    val connectionSupportHint: String? get() = when (this) {
        Xiaoha -> "小哈目前仅能从 HBB…SOCxxx 广播名读取电量，尚未确认直连协议；扫描页可查看电量"
        Xingheng -> "星恒星蓝尚无已确认的蓝牙直连协议；已发现的一线通协议不能直接用于 BLE"
        Zhiba -> "智霸锂电尚缺已确认的蓝牙协议，暂不发送读取或控制命令"
        Shanchong -> "闪充智行尚缺已确认的蓝牙直连协议；通过陆行中继时请选择陆行电量计"
        else -> null
    }

    /** 广播名可以被改，扫描服务与手动板型选择共同兜底。 */
    val serviceUuids: List<String> get() = when (this) {
        Jbd -> listOf("FF00", "FFE0")
        YanYang -> listOf("FFE0", "FEE9")
        Yadi -> listOf("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        Mingtang -> listOf("FA00")
        Xingheng, Zhiba, Shanchong, Xiaoha -> emptyList()
        else -> listOf("FFE0")
    }

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

        private val jbdApName = Regex("AP2[0-9]S.*")
        private val yadiName = Regex("FD[0-9A-F]{6,}")
        private val xiaohaName = Regex("HBB[0-9]+SOC([0-9]{3})")

        /** 只认完整且电量在范围内的实测广播格式，不猜 CF/XH 等通用短前缀。 */
        fun advertisedSoc(name: String?): Int? = xiaohaName.matchEntire(name?.trim()?.uppercase().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

        /** 广播名 → 家族提示；未命中返回 null，仍可按广播服务或手动板型选择。 */
        fun matchName(name: String?): DeviceFamily? {
            val n = name?.trim()?.uppercase() ?: return null
            if (n.isEmpty()) return null
            if (yadiName.matches(n)) return Yadi
            if (advertisedSoc(n) != null) return Xiaoha
            // YYBMS 的蓝牙模块常用 BT37/BTxxxx；只接数字后缀，避免把普通 BT 音频名认成板。
            if (n.startsWith("BT") && n.drop(2).isNotEmpty() && n.drop(2).all { it.isDigit() }) return YanYang
            if (n.startsWith("XIAOXIANG") || n.startsWith("DP04S") || n.startsWith("GJ-") ||
                jbdApName.matches(n)) return Jbd
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
