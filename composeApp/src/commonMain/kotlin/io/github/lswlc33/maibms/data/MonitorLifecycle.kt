package io.github.lswlc33.maibms.data

/** 平台启动/停止都是异步的；意图变化不能把待停止的服务当成仍在监控。调用方串行访问。 */
internal class MonitorLifecycle {
    sealed class Action {
        data class Start(val content: MonitorNotification) : Action()
        data class Update(val content: MonitorNotification) : Action()
        data object Stop : Action()
        data object None : Action()
    }

    private enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }
    private var phase = Phase.STOPPED
    var requestedContent: MonitorNotification? = null
        private set

    fun request(content: MonitorNotification?): Action {
        requestedContent = content
        return when (phase) {
            Phase.STOPPED -> if (content == null) Action.None else {
                phase = Phase.STARTING
                Action.Start(content)
            }
            Phase.STARTING -> if (content == null) {
                phase = Phase.STOPPING
                Action.Stop
            } else Action.None
            Phase.RUNNING -> if (content == null) {
                phase = Phase.STOPPING
                Action.Stop
            } else Action.Update(content)
            Phase.STOPPING -> Action.None
        }
    }

    fun started(): Action {
        // 关闭时启动请求可能已在系统队列里；仍需等真正销毁，不能提前再起一轮。
        if (phase == Phase.STOPPING || requestedContent == null) {
            phase = Phase.STOPPING
            return Action.Stop
        }
        phase = Phase.RUNNING
        return Action.Update(requestedContent!!)
    }

    fun stopped(): Action {
        val resume = phase == Phase.STOPPING && requestedContent != null
        phase = Phase.STOPPED
        return if (resume) request(requestedContent) else Action.None
    }

    /** 失败不自动无限重试；回前台的 refreshStatus 可重试保留的意图。 */
    fun failed() {
        phase = Phase.STOPPED
    }
}
