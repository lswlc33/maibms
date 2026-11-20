package io.github.lswlc33.maibms.data

import kotlin.math.abs

data class MonitorNotification(
    val title: String,
    val text: String,
    val deviceName: String,
    val soc: Int? = null,
)

internal fun monitorNotificationContent(
    status: BmsStatus,
    deviceName: String,
    connected: Boolean,
): MonitorNotification {
    if (!connected) return MonitorNotification("正在重连", deviceName, deviceName)
    if (!status.hasData) return MonitorNotification("等待数据", deviceName, deviceName)

    val soc = status.soc.takeIf { status.hasReading("soc") && it in 0..100 }
    val remainingAh = status.remainCapAh.takeIf {
        status.hasReading("remaining") && it.isFinite() && it >= 0.0
    }
    val state = status.battState.takeIf { status.hasReading("state") } ?: "监控中"
    val title = "$state · ${soc?.let { "$it%" } ?: "--%"} · " +
            "${remainingAh?.let { "${formatMonitorNumber(it)}Ah" } ?: "--Ah"}"

    val voltage = status.totalVoltage.takeIf {
        status.hasReading("voltage") && it.isFinite() && it > 0.0
    }?.let { "${formatMonitorNumber(it)}V" } ?: "--V"
    val current = if (status.hasReading("current") && status.current.isFinite()) {
        "${formatMonitorNumber(abs(status.current))}A"
    } else "--A"
    val power = if (status.hasReading("power")) {
        "${formatMonitorNumber(abs(status.power.toDouble()))}W"
    } else "--W"
    val firstLine = "$voltage · $current · $power"
    val temperatures = status.temps.asSequence()
        .map { it.second }
        .filter { it.isFinite() && it != -40.0 }
        .map { "${formatMonitorNumber(it)}°" }
        .toList()
    val text = if (temperatures.isEmpty()) firstLine else "$firstLine\n${temperatures.joinToString(" · ")}"

    return MonitorNotification(title, text, deviceName, soc)
}

private fun formatMonitorNumber(value: Double): String = "%.1f".fmt(value).removeSuffix(".0")
