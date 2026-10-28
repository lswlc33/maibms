package io.github.lswlc33.maibms.data

/**
 * 平台能力开关（网页端）：把"这个平台有没有某项能力"集中一处，界面据此显隐，
 * 免得散落的 `if (平台 == 网页)` 判断四处漂移。
 *
 * 与既有的"注入实现、未注入即空"是同一思路的另一种落地：那些能力（通知/后台监控）
 * 在网页端**从根本上不成立**，不是"还没接"：
 *  - 通知：页面关掉就没有执行环境，Web Notification 也只在页面活着时有效；
 *  - 后台监控：浏览器不给网页常驻 BLE 连接，切走标签页/锁屏后链路必然被挂起；
 *  - 检查更新：网页每次打开都是最新构建，没有"安装的版本"可更新；
 *  - 程序化锁横屏/沉浸状态栏：浏览器不提供（用户自己转手机）；
 *  - 日志落盘/导出文件：浏览器不能写用户磁盘，改为复制到剪贴板或下载。
 */
object PlatformInfo {

    /** 运行在网页端 */
    val isWeb: Boolean = true

    /** 是否支持系统通知（决定"告警通知"开关是否显示） */
    val canNotify: Boolean get() = false

    /** 是否支持后台保活监控（决定"后台监控"开关是否显示） */
    val canBackgroundMonitor: Boolean get() = false

    /** 是否有"应用内检查更新"这回事 */
    val canSelfUpdate: Boolean get() = false

    /** 能否程序化锁定横屏（决定仪表视图入口的提示文案） */
    val canLockOrientation: Boolean get() = false

    /** 能否把日志写成本地文件（不能则退化为复制/下载） */
    val canExportLogFile: Boolean get() = false
}