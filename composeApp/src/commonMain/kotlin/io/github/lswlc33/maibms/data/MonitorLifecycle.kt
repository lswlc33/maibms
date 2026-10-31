package io.github.lswlc33.maibms.data

/** 平台启动/停止都是异步的；意图变化不能把待停止的服务当成仍在监控。调用方串行访问。 */
internal class MonitorLifecycle {
    sealed class Action {
        data class Start(val text: String) : Action()
        data class Update(val text: String) : Action()
        data object Stop : Action()
        data object None : Action()
    }

    private enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }
    private var phase = Phase.STOPPED
    var requestedText: String? = null
        private set

    fun request(text: String?): Action {
        requestedText = text
        return when (phase) {
            Phase.STOPPED -> if (text == null) Action.None else {
                phase = Phase.STARTING
                Action.Start(text)
            }
            Phase.STARTING -> if (text == null) {
                phase = Phase.STOPPING
                Action.Stop
            } else Action.None
            Phase.RUNNING -> if (text == null) {
                phase = Phase.STOPPING
                Action.Stop
            } else Action.Update(text)
            Phase.STOPPING -> Action.None
        }
    }

    fun started(): Action {
        // 关闭时启动请求可能已在系统队列里；仍需等真正销毁，不能提前再起一轮。
        if (phase == Phase.STOPPING || requestedText == null) {
            phase = Phase.STOPPING
            return Action.Stop
        }
        phase = Phase.RUNNING
        return Action.Update(requestedText!!)
    }

    fun stopped(): Action {
        val resume = phase == Phase.STOPPING && requestedText != null
        phase = Phase.STOPPED
        return if (resume) request(requestedText) else Action.None
    }

    /** 失败不自动无限重试；回前台的 refreshStatus 可重试保留的意图。 */
    fun failed() {
        phase = Phase.STOPPED
    }
}
