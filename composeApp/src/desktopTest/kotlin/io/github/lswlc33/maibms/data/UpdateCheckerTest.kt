package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 更新检查：版本比较（isNewer）的判定矩阵。
 * 语义化版本逐段比，预发布（-beta.N）视为比同号正式版小。
 */
class UpdateCheckerTest {

    @Test fun strictlyNewerDetected() {
        assertTrue(UpdateChecker.isNewer("0.1.2", "0.1.1"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.1"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"), "数字段按数值比较，不是字符串")
    }

    @Test fun sameOrOlderNotDetected() {
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.2.0"))
    }

    @Test fun prereleaseBelowSameOfficial() {
        // 同版本号的 beta 不算更新（避免每次发 beta 都提示正式版用户）
        assertFalse(UpdateChecker.isNewer("0.1.1-beta.1", "0.1.1"))
        // 但更新的 beta 比旧正式版大
        assertTrue(UpdateChecker.isNewer("0.1.2-beta.1", "0.1.1"))
        // 正式版相对 beta 是更新
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.1-beta.9"))
    }

    @Test fun vPrefixAndSegmentPadding() {
        // tagName 前缀在 check() 里剥，isNewer 层面允许带 v 的容错由调用方保证；
        // 段数不同补 0：0.2 == 0.2.0
        assertTrue(UpdateChecker.isNewer("0.2", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
    }

    @Test fun failedResultCarriesAllSources() {
        // 源列表：两个国内镜像 + GitHub 直连兜底，顺序即回退优先级
        assertEquals(3, UpdateChecker::class.java.getDeclaredField("sources").apply { isAccessible = true }
            .get(null).let { it as List<*> }.size)
    }

    // ---- 更新渠道 ----

    @Test fun channelPicksApiUrl() {
        // 稳定版走 /releases/latest（官方接口天然排除预发布）；
        // 预览版走列表接口取最近一次发布（可能含 Prerelease）
        assertEquals("https://api.github.com/repos/lswlc33/maibms/releases/latest",
            UpdateChecker.apiUrlFor(UpdateChecker.UpdateChannel.STABLE))
        assertEquals("https://api.github.com/repos/lswlc33/maibms/releases?per_page=10",
            UpdateChecker.apiUrlFor(UpdateChecker.UpdateChannel.PREVIEW))
    }

    @Test fun previewChannelDecodesListAndKeepsPrereleaseFlag() {
        // 预览渠道的响应是 release 数组，取第一个（最近发布）；prerelease 标记必须透传到 UI
        val body = """[
            {"tag_name":"v0.1.1-beta.9","html_url":"https://github.com/lswlc33/maibms/releases/tag/beta-9","prerelease":true},
            {"tag_name":"v0.1.1","html_url":"https://github.com/lswlc33/maibms/releases/tag/0.1.1","prerelease":false}
        ]"""
        val list = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(kotlinx.serialization.builtins.ListSerializer(UpdateChecker.ReleaseInfo.serializer()), body)
        val first = list.first()
        assertEquals("v0.1.1-beta.9", first.tagName)
        assertTrue(first.prerelease)
        assertFalse(list[1].prerelease)
    }

    @Test fun channelFromKeyDefaultsToStable() {
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey("stable"))
        assertEquals(UpdateChecker.UpdateChannel.PREVIEW, UpdateChecker.UpdateChannel.fromKey("preview"))
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey(null))
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey("whatever"))
    }
}
