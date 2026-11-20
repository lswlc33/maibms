package io.github.lswlc33.maibms.transport

/** 完整 UUID 与 16 位 UUID 统一比较；自定义 128 位 UUID 保持原样。 */
fun bleUuid(value: String): String = if (value.length == 4) {
    "0000${value.uppercase()}-0000-1000-8000-00805F9B34FB"
} else value.uppercase()

fun sameBleUuid(a: String, b: String): Boolean = bleUuid(a) == bleUuid(b)

fun boardBleChannels(family: DeviceFamily): List<BleChannel> = when (family) {
    DeviceFamily.YanYang -> listOf(BleChannel.YanYang, BleChannel.YanYangFee9, BleChannel.YanYangFee9Uart, BleChannel.YanYangFfe1)
    DeviceFamily.Jbd -> listOf(BleChannel.Jbd, BleChannel.JbdFfe0)
    DeviceFamily.Jikong -> listOf(BleChannel.Jikong)
    DeviceFamily.Yadi -> listOf(BleChannel.Yadi)
    DeviceFamily.Mingtang -> listOf(BleChannel.Mingtang)
    else -> emptyList()
}

/** 名称优先；FF00/FEE9 模块及极空的 FFE0 + 公司 ID 仅作名称不明时的提示。 */
fun advertisedFamily(name: String?, serviceUuids: List<String>, manufacturerIds: List<Int> = emptyList()): DeviceFamily? =
    if (name?.trim()?.uppercase()?.startsWith(DeviceFamily.CONTROLLER_PREFIX) == true) null
    else DeviceFamily.matchName(name) ?: when {
        serviceUuids.any { sameBleUuid(it, "FF00") } -> DeviceFamily.Jbd
        serviceUuids.any { sameBleUuid(it, "FEE9") } -> DeviceFamily.YanYang
        serviceUuids.any { sameBleUuid(it, "FFE0") } && manufacturerIds.any { it == 0x0B65 || it == 0x4B4A } -> DeviceFamily.Jikong
        serviceUuids.any { sameBleUuid(it, "FA00") } -> DeviceFamily.Mingtang
        // NUS 是通用串口服务，不能仅凭服务把别的外设标为亚迪。
        serviceUuids.any { sameBleUuid(it, BleChannel.Yadi.serviceUuid) } -> DeviceFamily.Unknown
        serviceUuids.any { sameBleUuid(it, "FFE0") } -> DeviceFamily.Unknown
        else -> null
    }
