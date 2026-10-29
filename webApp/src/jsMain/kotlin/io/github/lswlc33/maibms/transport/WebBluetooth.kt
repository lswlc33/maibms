package io.github.lswlc33.maibms.transport

import kotlin.js.Promise

/**
 * Web Bluetooth 的最小类型声明（只声明本仓库用到的那几个成员）。
 *
 * 为什么手写而不是引一个 Kotlin 包装库：需要的面很窄，手写能把"Chrome 的实现细节"
 * （写特征的分片上限、CCCD 由 startNotifications 隐式处理）写进注释里，也避免多一个依赖
 * 与主工程的 Kotlin 版本打架（本项目 Kotlin 2.1.0）。
 */
@JsName("BluetoothDevice")
external class WebBluetoothDevice {
    val id: String
    val name: String?
    val gatt: WebBluetoothGattServer?

    fun addEventListener(type: String, listener: (dynamic) -> Unit)
    fun removeEventListener(type: String, listener: (dynamic) -> Unit)
}

@JsName("BluetoothRemoteGATTServer")
external class WebBluetoothGattServer {
    val connected: Boolean
    val device: WebBluetoothDevice

    fun connect(): Promise<WebBluetoothGattServer>
    fun disconnect()
    fun getPrimaryService(service: String): Promise<WebBluetoothGattService>
}

@JsName("BluetoothRemoteGATTService")
external class WebBluetoothGattService {
    fun getCharacteristic(characteristic: String): Promise<WebBluetoothGattCharacteristic>
    fun getCharacteristics(): Promise<Array<WebBluetoothGattCharacteristic>>
}

/**
 * BluetoothCharacteristicProperties（Web Bluetooth 规范里的成员）。
 *
 * **必须用 external 类型访问，不能写 `js("c.properties.write")`**：
 * `js()` 是编译期原样输出的 JS 字面量，里面的标识符不会被 Kotlin 解析——
 * 写 `c` / `n` 这类 Kotlin 侧变量名，编译后它们根本不存在（或被压缩成别的名字），
 * 结果恒为 undefined。曾因此让"特征是否可写/可通知"判定全部为 false，
 * 表现为任何设备都连不上（"设备没有可用的通信通道"）。
 */
@JsName("BluetoothCharacteristicProperties")
external class WebBluetoothCharacteristicProperties {
    val read: Boolean?
    val write: Boolean?
    val writeWithoutResponse: Boolean?
    val notify: Boolean?
    val indicate: Boolean?
    val broadcast: Boolean?
    val authenticatedSignedWrites: Boolean?
    val reliableWrite: Boolean?
    val writableAuxiliaries: Boolean?
}

@JsName("BluetoothRemoteGATTCharacteristic")
external class WebBluetoothGattCharacteristic {
    val uuid: String

    /** BluetoothCharacteristicProperties（notify / indicate / write / writeWithoutResponse / read） */
    val properties: WebBluetoothCharacteristicProperties

    /** 最近一次通知值（DataView）；仅在上一次读之后有新值时可用 */
    val value: dynamic

    fun startNotifications(): Promise<WebBluetoothGattCharacteristic>
    fun stopNotifications(): Promise<WebBluetoothGattCharacteristic>
    fun writeValueWithResponse(value: dynamic): Promise<Unit>
    fun writeValueWithoutResponse(value: dynamic): Promise<Unit>

    fun addEventListener(type: String, listener: (dynamic) -> Unit)
    fun removeEventListener(type: String, listener: (dynamic) -> Unit)
}

/** 通知事件：`event.target.value` 是 DataView */
@JsName("BluetoothCharacteristicValueChangedEvent")
external class BluetoothCharacteristicValueChangedEvent {
    val target: WebBluetoothGattCharacteristic
}

/** DataView 的最小声明（只用到取字节长度与按索引读字节） */
@JsName("DataView")
external class WebDataView {
    val byteLength: Int
    fun getUint8(byteOffset: Int): Int
}

@JsName("Bluetooth")
external class WebBluetoothAdapter {
    fun requestDevice(options: dynamic): Promise<WebBluetoothDevice>
    fun getAvailability(): Promise<Boolean>
}

/**
 * `navigator.bluetooth`；不支持 Web Bluetooth 的环境（非 Chrome、非 HTTPS）返回 null。
 *
 * 注意可用性的两个硬前提：**安全上下文**（https 或 localhost）+ **用户手势**
 * （requestDevice 必须在点击事件里调用，不能在页面加载时自动弹）。
 */
internal fun bluetoothAdapter(): WebBluetoothAdapter? =
    js("(navigator.bluetooth || null)").unsafeCast<WebBluetoothAdapter?>()

/** 当前环境能否用 Web Bluetooth（用于界面给出"换个浏览器/改用 https"的提示） */
internal fun webBluetoothSupported(): Boolean = bluetoothAdapter() != null