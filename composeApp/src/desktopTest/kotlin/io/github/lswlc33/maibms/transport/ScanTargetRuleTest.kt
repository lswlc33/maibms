package io.github.lswlc33.maibms.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「扫描到的那台是不是要连的那台」的判定回归。
 *
 * 两个平台的传输各自持有这条判断（Android `getRemoteDevice(MAC)` / iOS
 * `retrievePeripheralsWithIdentifiers`），但规则只有一条、且决定会话归属：
 * 没有目标标识时任何目标设备都可以；**指定了标识就只能连它**。
 *
 * 真机事故形态：iOS 上按标识取回失败（被「忽略此设备」）+ 扫描没命中时，曾经拿"扫到的另一台
 * 目标设备"顶上——仓库仍按原设备的地址、密码库、家族与档案建会话，等于把 A 的密码与配置写到
 * B 的档案里（见 IosBleTransport.findPeripheral / AndroidBleTransport.connect）。
 */
class ScanTargetRuleTest {

    private val mac = "C4:24:8A:78:A5:81"

    @Test fun noTargetAcceptsAnyTargetDevice() {
        assertTrue(acceptsPeripheralId(null, mac), "没指定设备时，扫到哪台就是哪台")
        assertTrue(acceptsPeripheralId("", mac))
        assertTrue(acceptsPeripheralId("   ", mac))
    }

    @Test fun specifiedTargetOnlyAcceptsItsOwnId() {
        assertTrue(acceptsPeripheralId(mac, mac))
        // iOS 的标识大小写形态不统一（UUIDString 大写），比较必须忽略大小写
        assertTrue(acceptsPeripheralId(mac, mac.lowercase()))
        assertTrue(acceptsPeripheralId(mac.lowercase(), mac))
        // 别的设备（哪怕同厂同型号、名字前缀一模一样）一律不接受
        assertFalse(acceptsPeripheralId(mac, "C4:24:8A:78:A5:82"))
        assertFalse(acceptsPeripheralId(mac, "A1B2C3D4-0000-1111-2222-333344445555"))
    }
}
