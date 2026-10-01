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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 配置缓存（≠ 快照）：自动重连设备「上次成功连接」的**设置项**（参数区+身份区），
 * 不含任何实时数据。覆盖：按设备存取 / 连接成功后自动写缓存 / 重启未连接时载入缓存 /
 * 删除设备级联清缓存。
 */
class ParamsCacheTest {

    private val ADDR = "F9:99:1B:2B:1B:70"

    @Test fun cacheRoundTrip() {
        AppStore.deleteParamsCache(ADDR)
        assertNull(AppStore.loadParamsCache(ADDR))
        AppStore.saveParamsCache(ADDR, AppStore.ParamsCache(
            savedAt = 12345L, params = mapOf(0 to 4300, 162 to 10752), identity = mapOf("swVersion" to "X"),
        ))
        val loaded = AppStore.loadParamsCache(ADDR)!!
        assertEquals(12345L, loaded.savedAt)
        assertEquals(mapOf(0 to 4300, 162 to 10752), loaded.params)
        assertEquals("X", loaded.identity["swVersion"])
        AppStore.deleteParamsCache(ADDR)
        assertNull(AppStore.loadParamsCache(ADDR))
    }

    /** 连接 → 参数区读回成功 → 该设备的配置缓存自动落盘 */
    @Test fun successfulConnectionPersistsCache() {
        AppStore.deleteParamsCache(ADDR)
        AppStore.savedAddress = ADDR
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val engine = MockBmsEngine(scope)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(MockBmsTransport(engine, scope))
                repo.start()   // start() 会以 savedAddress 为目标自动连接
                // 等 postConnectFlow 的参数区读回完成（缓存出现即代表成功）
                var cache: AppStore.ParamsCache? = null
                repeat(50) {
                    cache = AppStore.loadParamsCache(ADDR)
                    if (cache != null) return@repeat
                    delay(200)
                }
                assertTrue(cache != null && cache.params.isNotEmpty(), "连接成功后应写出配置缓存")
                assertFalse(MockBms.paramsFromCache.value, "连接会话里是实时数据，不是缓存")
                repo.disconnect()
            }
        } finally {
            scope.cancel()
        }
    }

    /** 重启后未连接：start() 载入重连目标的配置缓存，配置页显示缓存态 */
    @Test fun startWithoutConnectionLoadsCache() {
        AppStore.deleteParamsCache(ADDR)
        AppStore.savedAddress = ADDR
        AppStore.saveParamsCache(ADDR, AppStore.ParamsCache(
            savedAt = 777L, params = mapOf(0 to 4300, 162 to 10752, 164 to 1739),
            identity = mapOf("swVersion" to "PREV"),
        ))
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val repo = BmsRepository(scope, MockBmsEngine(scope))
                repo.setRealTransport(MockBmsTransport(MockBmsEngine(scope), scope))
                AppStore.autoReconnect = false   // 模拟「重启后不连，只想看缓存」
                repo.start()
                delay(200)
                assertTrue(MockBms.paramsFromCache.value, "未连接时应处于缓存展示态")
                assertEquals(4300, MockBms.liveParams.value[0])
                assertEquals("PREV", MockBms.identity.value["swVersion"])
            }
        } finally {
            scope.cancel()
        }
    }

    /** 删除历史设备时级联清配置缓存 */
    @Test fun deleteDeviceClearsCache() {
        AppStore.saveParamsCache("DEV-X", AppStore.ParamsCache(1L, mapOf(0 to 1), emptyMap()))
        val scope = CoroutineScope(Dispatchers.Default)
        val repo = BmsRepository(scope, MockBmsEngine(scope))
        repo.deleteDevice("DEV-X")
        assertNull(AppStore.loadParamsCache("DEV-X"))
    }
}

private fun assertFalse(actual: Boolean, message: String? = null) = kotlin.test.assertTrue(!actual, message)
