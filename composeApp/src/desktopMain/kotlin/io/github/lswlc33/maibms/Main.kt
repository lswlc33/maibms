package io.github.lswlc33.maibms

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LogFileStore
import io.github.lswlc33.maibms.ui.App
import java.io.File

fun main() = application {
    // 桌面入口先于首帧接好持久化与日志：KV 用内存实现（日志文件才是重启后的记忆），
    // 日志写到用户目录 maibms-logs（与「导出日志」同目录），读回上次运行、按天只留 3 天
    AppStore.store = object : io.github.lswlc33.maibms.data.KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
    LogFileStore.setDir(File(System.getProperty("user.home"), "maibms-logs").absolutePath)
    BmsLog.restore()
    BmsLog.frameLogOn.value = AppStore.logFrameOn
    BmsLog.minLevel.value = BmsLog.Level.entries.firstOrNull { it.tag == AppStore.logMinLevel } ?: BmsLog.Level.INFO
    Window(
        onCloseRequest = ::exitApplication,
        title = "麻衣 BMS",
        icon = painterResource("app_icon.png"),
        state = rememberWindowState(width = 420.dp, height = 860.dp)
    ) {
        App()
    }
}
