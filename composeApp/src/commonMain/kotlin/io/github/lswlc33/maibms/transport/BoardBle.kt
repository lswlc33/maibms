package io.github.lswlc33.maibms.transport

/** 完整 UUID 与 16 位 UUID 统一比较；自定义 128 位 UUID 保持原样。 */
fun bleUuid(value: String): String = if (value.length == 4) {
    "0000${value.uppercase()}-0000-1000-8000-00805F9B34FB"
} else value.uppercase()

fun sameBleUuid(a: String, b: String): Boolean = bleUuid(a) == bleUuid(b)

fun boardBleChannels(family: DeviceFamily): List<BleChannel> = when (family) {
    DeviceFamily.YanYang -> listOf(BleChannel.YanYang, BleChannel.YanYangFee9)
    DeviceFamily.Jbd -> listOf(BleChannel.Jbd, BleChannel.JbdFfe0)
    else -> emptyList()
}

/** 名称只作提示；FF00/FEE9 是专用模块，通用 FFE0 则留待用户选择协议。 */
fun advertisedFamily(name: String?, serviceUuids: List<String>): DeviceFamily? =
    if (name?.trim()?.uppercase()?.startsWith(DeviceFamily.CONTROLLER_PREFIX) == true) null
    else DeviceFamily.matchName(name) ?: when {
        serviceUuids.any { sameBleUuid(it, "FF00") } -> DeviceFamily.Jbd
        serviceUuids.any { sameBleUuid(it, "FEE9") } -> DeviceFamily.YanYang
        serviceUuids.any { sameBleUuid(it, "FFE0") } -> DeviceFamily.Unknown
        else -> null
    }
