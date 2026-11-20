package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.protocol.board.ReadOnlyBoardProtocol

/** 通知订阅只证明 GATT 可用；收到校验正确的实时读数后才确认板型和通道。 */
internal class BoardConnectionProbe(family: DeviceFamily, password: String?, macAddress: String?) {
    private val protocol = requireNotNull(ReadOnlyBoardProtocol.forFamily(family))
    var ready: Boolean = false
        private set
    val error: String? get() = protocol.error
    val modbusAddress: Int? get() = if (ready) protocol.modbusAddress else null
    val protocolLayoutHint: String? get() = if (ready) protocol.protocolLayoutHint else null

    init {
        protocol.configureAccess(password, macAddress)
    }

    fun configureAccess(password: String?, macAddress: String?) {
        protocol.configureAccess(password, macAddress)
    }

    fun nextRequest(nowMs: Long): ByteArray? = if (ready) null else protocol.nextRequest(nowMs)

    fun onNotification(bytes: ByteArray, nowMs: Long): Boolean {
        if (!ready) ready = protocol.onNotification(bytes, nowMs).any { it.hasLiveReading }
        return ready
    }
}

internal fun boardConnectionHint(family: DeviceFamily, detail: String? = null): String {
    val help = if (family == DeviceFamily.Jbd)
        "请确认选择了嘉佰达板型、关闭原厂 App；需要配对的模块请完成系统配对，旧密码模块可在只读参数中填写已有密码和真实 MAC"
    else if (family == DeviceFamily.Jikong) "请确认选择了极空板型并关闭原厂 App；支持 JK02 / JK04，实际字段按设备区分"
    else "请确认选择了彦阳板型并关闭原厂 App；应用会探测 B 代或旧 A 兼容布局"
    return "未收到${family.label}有效数据。${detail?.let { "$it。" }.orEmpty()}$help"
}
