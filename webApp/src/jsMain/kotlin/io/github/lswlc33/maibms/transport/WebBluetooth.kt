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

@JsName("BluetoothRemoteGATTCharacteristic")
external class WebBluetoothGattCharacteristic {
    val uuid: String

    /** BluetoothCharacteristicProperties（notify / indicate / write / writeWithoutResponse / read） */
    val properties: dynamic

    /** 最近一次通知值（DataView）；仅在上一次读之后有新值时可用 */
    val value: dynamic

    fun startNotifications(): Promise<WebBluetoothGattCharacteristic>
    fun stopNotifications(): Promise<WebBluetoothGattCharacteristic>
    fun writeValueWithResponse(value: dynamic): Promise<Unit>
    fun writeValueWithoutResponse(value: dynamic): Promise<Unit>

    fun addEventListener(type: String, listener: (dynamic) -> Unit)
    fun removeEventListener(type: String, listener: (dynamic) -> Unit)
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