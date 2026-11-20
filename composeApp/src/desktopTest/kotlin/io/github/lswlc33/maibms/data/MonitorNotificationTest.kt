package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorNotificationTest {
    @Test
    fun formatsStateMetricsAndAvailableTemperatures() {
        val status = BmsStatus(
            hasData = true,
            soc = 100,
            remainCapAh = 150.0,
            battState = "放电",
            totalVoltage = 86.0,
            current = 20.0,
            power = 1865,
            temps = listOf("MOS" to 45.0, "均衡" to 45.0),
            availableFields = setOf("state", "soc", "remaining", "voltage", "current", "power"),
        )

        assertEquals(
            MonitorNotification("放电 · 100% · 150Ah", "86V · 20A · 1865W\n45° · 45°", "电池", 100),
            monitorNotificationContent(status, "电池", connected = true),
        )
    }

    @Test
    fun missingFieldsAndFirstFrameUsePlaceholders() {
        assertEquals(
            MonitorNotification("等待数据", "设备", "设备"),
            monitorNotificationContent(BmsStatus(), "设备", connected = true),
        )
        val status = BmsStatus(hasData = true, availableFields = emptySet())
        assertEquals(
            MonitorNotification("监控中 · --% · --Ah", "--V · --A · --W", "设备"),
            monitorNotificationContent(status, "设备", connected = true),
        )
    }

    @Test
    fun reconnectAndZeroRemainingCapacityArePreserved() {
        val status = BmsStatus(
            hasData = true,
            remainCapAh = 0.0,
            availableFields = setOf("remaining"),
        )
        assertEquals(
            MonitorNotification("正在重连", "设备", "设备"),
            monitorNotificationContent(status, "设备", connected = false),
        )
        assertEquals(
            "监控中 · --% · 0Ah",
            monitorNotificationContent(status, "设备", connected = true).title,
        )
    }

    @Test
    fun filtersInvalidTemperaturesAndKeepsNegativeValues() {
        val status = BmsStatus(
            hasData = true,
            availableFields = emptySet(),
            temps = listOf("T1" to -12.3, "T2" to -40.0, "T3" to Double.NaN, "T4" to Double.POSITIVE_INFINITY),
        )
        assertEquals(
            "--V · --A · --W\n-12.3°",
            monitorNotificationContent(status, "设备", connected = true).text,
        )
    }

    @Test
    fun chargingUsesAbsoluteValuesAndAtMostOneDecimal() {
        val status = BmsStatus(
            hasData = true,
            battState = "充电",
            soc = 75,
            remainCapAh = 112.34,
            totalVoltage = 85.66,
            current = -20.06,
            power = -1720,
        )
        assertEquals(
            MonitorNotification("充电 · 75% · 112.3Ah", "85.7V · 20.1A · 1720W", "设备", 75),
            monitorNotificationContent(status, "设备", connected = true),
        )
    }

    @Test
    fun invalidReadingsUsePlaceholders() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val status = BmsStatus(
                hasData = true,
                soc = 101,
                remainCapAh = value,
                totalVoltage = value,
                current = Double.NaN,
                availableFields = setOf("soc", "remaining", "voltage", "current"),
            )
            assertEquals(
                MonitorNotification("监控中 · --% · --Ah", "--V · --A · --W", "设备"),
                monitorNotificationContent(status, "设备", connected = true),
            )
        }
    }
}
