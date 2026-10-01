package io.github.lswlc33.maibms.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备数据快照：一次连接完成全量同步（实时帧 + 参数区 + 身份区）后冻结的完整状态。
 *
 * - `id` = 记录时刻的 epoch 毫秒，同时是存储 key 与同会话覆盖写的主键；
 * - `status.connected` 恒为 false——快照天然是离线数据，预览时走「未连接」的只读路径；
 * - 密码不进快照：密码库已有独立的按设备持久化（AppStore.loadPassword）。
 */
@Serializable
data class BmsSnapshot(
    val id: Long,
    val deviceAddress: String?,
    val deviceName: String,
    /** 记录时刻的可读标签，如「10-01 14:30」 */
    val timeLabel: String,
    val status: BmsStatus,
    /** 参数区（字节地址 → u16），配置页数据源 */
    val liveParams: Map<Int, Int>,
    /** 身份区（boot/codeKey/hwVersion/swVersion/packId） */
    val identity: Map<String, String>,
)

/** 快照 JSON 编解码入口。字段只增不改名：旧快照靠 ignoreUnknownKeys 向后兼容。 */
object SnapshotCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(s: BmsSnapshot): String = json.encodeToString(BmsSnapshot.serializer(), s)

    fun decode(text: String): BmsSnapshot? = runCatching {
        json.decodeFromString(BmsSnapshot.serializer(), text)
    }.getOrNull()
}
