package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.transport.MockBmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 写参数端到端（虚拟设备 + 真实 BmsRepository 路径）。
 *
 * 覆盖三件真机上要么没验过、要么曾静默失败的事：
 * - 权限门槛（<3 级写被拒，设备侧的值不能变）；
 * - 结果判定（虚拟设备与 docs/06 §6.4 的口径一致：成功时不追加 0xFF 段，
 *   此时必须靠回读兜底给出结论，而不是什么都不显示）；
 * - 容量类 u32 拆两帧（否则 113.0Ah 会被截成低 16 位）。
 *
 * 注意：仓库的连接态收集/轮询是常驻协程，必须跑在**用例自有的 scope** 上并在结束时取消——
 * 挂在 runBlocking 的 scope 上会让 runBlocking 永远等不到子协程结束（测试进程直接卡死）。
 */
class WritePathTest {

    /** 用例脚手架：复位全局状态、连上虚拟设备、跑用例、收尾取消常驻协程 */
    private fun withRepo(permission: Int, body: suspend (MockBmsEngine, BmsRepository) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                MockBms.clearForRealDevice()
                MockBms.plainPasswords.value = emptyMap()
                MockBms.lastWriteResult.value = -1
                MockBms.lastWriteDetail.value = null
                val engine = MockBmsEngine(scope)
                engine.currentPermission = permission
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(MockBmsTransport(engine, scope))
                repo.start()
                repo.connect(null)
                delay(300)   // 等第一拍轮询把链路态落到 Connected
                body(engine, repo)
                repo.disconnect()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun writeRejectedBelowLevel3() = withRepo(2) { engine, repo ->
        val before = engine.paramStore[0]

        val code = repo.writeParam(0, 4300)

        assertEquals(1, code, "二级权限写参数应被设备拒绝（0xFF 结果码 1）")
        assertEquals(1, MockBms.lastWriteResult.value)
        assertEquals(before, engine.paramStore[0], "被拒绝时设备侧的参数值不能变")
    }

    /**
     * 三级权限写成功。虚拟设备按 docs §6.4 的口径**不追加 0xFF 结果段**（只回 0x42 回显），
     * 正是真机上没验证过的那种情形 —— 兜底回读必须给出「已写入」的结论。
     */
    @Test fun writeSucceedsAtLevel3WithReadbackFallback() = withRepo(3) { engine, repo ->
        val code = repo.writeParam(0, 4300)

        assertEquals(10, code, "未收到结果段时应回读确认，按成功处理（码 10）")
        assertEquals(4300, engine.paramStore[0], "回读一致说明设备侧确实写进去了")
        assertTrue(
            MockBms.lastWriteDetail.value?.contains("回读确认") == true,
            "界面要能看出这是回读兜底的结论，实际：${MockBms.lastWriteDetail.value}",
        )
    }

    /** 容量类 u32：低字 @162、高字 @164 各写一帧，113.0Ah 不能被截断 */
    @Test fun writeCapacitySplitsIntoTwoFrames() = withRepo(3) { engine, repo ->
        val code = repo.writeParam(162, 113_000_000)

        assertEquals(10, code)
        assertEquals(15936, engine.paramStore[162], "低字 113_000_000 and 0xFFFF")
        assertEquals(1724, engine.paramStore[164], "高字 113_000_000 ushr 16")
    }

    /** 五级密码校验走 12 字节槽（虚拟设备按槽位比对），此前帧长写死 8 字节时这条路走不通 */
    @Test fun authLevel5ThroughRepository() = withRepo(1) { engine, repo ->
        assertEquals(5, repo.auth(5, "12345678"))
        assertEquals(5, engine.currentPermission)
    }
}
