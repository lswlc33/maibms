package io.github.lswlc33.maibms.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通道规则回归（docs/02-蓝牙链路.md §2.2）：
 * - 候选生成只认「有没有可写 FFF5」，与官方实现一致（原稿把条件写反过，见附录 E 第八轮）；
 * - 通道表与备用通道的代价文案跟文档一致。
 */
class ChannelRulesTest {

    @Test fun onlyWritableFff5HardwareGetsBackupChannels() {
        // 只提供 FFE1 的设备：只有默认通道（官方在这种设备上直接跳过自动轮切）
        assertEquals(listOf(BleChannel.Default), bleChannelCandidates(hasWritableFff5 = false))
        // 暴露可写 FFF5 的硬件：三条候选，顺序为 默认 → 备用 A → 备用 B
        assertEquals(
            listOf(BleChannel.Default, BleChannel.BackupA, BleChannel.BackupB),
            bleChannelCandidates(hasWritableFff5 = true),
        )
    }

    @Test fun channelIdsMatchDocs() {
        assertEquals("ffe1", BleChannel.Default.id)
        assertEquals("fff3-fff4", BleChannel.BackupA.id)
        assertEquals("fff5-fff6", BleChannel.BackupB.id)
        // 写/通知特征与 docs/02 的通道表逐字一致
        assertEquals("FFE1" to "FFE1", BleChannel.Default.writeUuid to BleChannel.Default.notifyUuid)
        assertEquals("FFF3" to "FFF4", BleChannel.BackupA.writeUuid to BleChannel.BackupA.notifyUuid)
        assertEquals("FFF5" to "FFF6", BleChannel.BackupB.writeUuid to BleChannel.BackupB.notifyUuid)
    }

    @Test fun lookupByIdToleratesUnknownValues() {
        assertEquals(BleChannel.Default, BleChannel.byId("ffe1"))
        assertEquals(BleChannel.BackupA, BleChannel.byId("fff3-fff4"))
        assertEquals(BleChannel.BackupB, BleChannel.byId("fff5-fff6"))
        assertNull(BleChannel.byId(null))
        assertNull(BleChannel.byId("fff9"))
    }

    @Test fun backupChannelsCarryInterfaceWarning() {
        assertNull(BleChannel.Default.warn)
        assertEquals("fff3发-fff4收", BleChannel.BackupA.tag)
        assertEquals("fff5发-fff6收", BleChannel.BackupB.tag)
        assertTrue(BleChannel.BackupA.warn!!.contains("蓝牙屏"))
        assertTrue(BleChannel.BackupB.warn!!.contains("充电器"))
        assertTrue(BleChannel.BackupA.warn!!.contains("不支持固件升级"))
    }
}
