package io.github.lswlc33.maibms.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertIs
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

    @Test fun betaSequenceComparesNumerically() {
        // 真实线上形态（tag v0.1.1-beta.N）：序号按数值比，beta.20 > beta.9（不是字符串比较）
        assertTrue(UpdateChecker.isNewer("0.1.1-beta.20", "0.1.1-beta.9"))
        assertTrue(UpdateChecker.isNewer("0.1.1-beta.10", "0.1.1-beta.9"))
        assertFalse(UpdateChecker.isNewer("0.1.1-beta.9", "0.1.1-beta.10"))
        // 同版本号之间 beta 序号不同不算「已是最新」误判
        assertFalse(UpdateChecker.isNewer("0.1.1-beta.9", "0.1.1-beta.9"))
    }

    @Test fun releaseTagWithVersionCodePrefix() {
        // 真实线上形态：稳定版 tag = <versionCode>-<versionName>（如 2-0.1.1）。
        // 曾直接把 tag 去掉 v 后比较，"2" 被当成主版本号 → 所有 0.1.1 用户被提示「发现 2-0.1.1」
        assertEquals("0.1.1", UpdateChecker.versionFromTag("2-0.1.1"))
        assertEquals("0.1.1", UpdateChecker.versionFromTag("v2-0.1.1"))
        assertEquals("0.1.1-beta.20", UpdateChecker.versionFromTag("v0.1.1-beta.20"))
        assertEquals("0.1.1", UpdateChecker.versionFromTag("v0.1.1"))
        assertEquals("0.1.1", UpdateChecker.versionFromTag("0.1.1"))
        // 归一化后不再误报
        assertFalse(UpdateChecker.isNewer(UpdateChecker.versionFromTag("2-0.1.1"), "0.1.1"))
        // code 前缀里的数字不影响版本本身
        assertTrue(UpdateChecker.isNewer(UpdateChecker.versionFromTag("3-0.1.2"), "0.1.1"))
    }

    @Test fun vPrefixAndSegmentPadding() {
        // tagName 前缀在 check() 里剥，isNewer 层面允许带 v 的容错由调用方保证；
        // 段数不同补 0：0.2 == 0.2.0
        assertTrue(UpdateChecker.isNewer("0.2", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
    }

    @Test fun prereleaseSupersededBeatsUpdate() = runBlocking {
        val result = UpdateChecker.check(UpdateChecker.UpdateChannel.STABLE, "0.1.9-beta.60") {
            """{"tag_name":"10-0.1.9","html_url":"${UpdateChecker.REPO_URL}/releases/tag/10-0.1.9"}"""
        }
        assertEquals("0.1.9", assertIs<UpdateChecker.Result.PrereleaseSuperseded>(result).official)
        val newer = UpdateChecker.check(UpdateChecker.UpdateChannel.STABLE, "0.1.9-beta.60") {
            """{"tag_name":"11-0.1.10"}"""
        }
        assertEquals("0.1.10", assertIs<UpdateChecker.Result.Update>(newer).latest)
    }

    @Test fun cancellationPropagatesWithoutMirrorFallback() = runBlocking {
        val cancellation = CancellationException("cancel old channel")
        var calls = 0
        val thrown = try {
            UpdateChecker.check(UpdateChecker.UpdateChannel.STABLE, "0.1.9") {
                calls++
                throw cancellation
            }
            null
        } catch (e: CancellationException) {
            e
        }
        assertIs<CancellationException>(thrown, "广捕 Exception 不得把取消改成 Failed")
        assertEquals(1, calls, "取消后不得继续访问镜像")
    }

    @Test fun cancelledUncancellableIoDoesNotTryNextMirror() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            lateinit var io: Continuation<String?>
            var calls = 0
            val request = async {
                UpdateChecker.check(UpdateChecker.UpdateChannel.STABLE, "0.1.9") {
                    calls++
                    suspendCoroutine<String?> { io = it; entered.complete(Unit) }
                }
            }
            entered.await()
            request.cancel()
            // 平台 IO 迟到的空响应不能触发下一个源。
            io.resume(null)
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(1, calls)
        }
    }

    @Test fun ordinaryFailureStillFallsBackAndUsesOfficialDecoder() = runBlocking {
        val urls = mutableListOf<String>()
        val result = UpdateChecker.check(UpdateChecker.UpdateChannel.PREVIEW, "0.1.9-beta.9") { url ->
            urls += url
            if (urls.size == 1) throw IllegalStateException("mirror unavailable")
            """[{"tag_name":"v0.1.9-beta.20","html_url":"https://evil.example/download","prerelease":true}]"""
        }
        val update = assertIs<UpdateChecker.Result.Update>(result)
        assertEquals("0.1.9-beta.20", update.latest)
        assertTrue(update.info.prerelease)
        assertEquals("${UpdateChecker.REPO_URL}/releases", update.info.htmlUrl)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.endsWith(UpdateChecker.apiUrlFor(UpdateChecker.UpdateChannel.PREVIEW)) })
    }

    @Test fun allOrdinaryFailuresReturnFailed() = runBlocking {
        var calls = 0
        val result = UpdateChecker.check(UpdateChecker.UpdateChannel.STABLE, "0.1.9") {
            calls++
            throw IllegalStateException("unavailable-$calls")
        }
        assertEquals(3, calls)
        val failure = assertIs<UpdateChecker.Result.Failed>(result)
        assertTrue(failure.reason.contains("unavailable-1"))
        assertTrue(failure.reason.contains("unavailable-3"))
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

    @Test fun foreignHtmlUrlFallsBackToRepoReleases() {
        // 更新检查会走第三方镜像；镜像返回的 html_url 不能直接拿去 openUri
        assertEquals(
            "https://github.com/lswlc33/maibms/releases",
            UpdateChecker.sanitizeHtmlUrl("https://evil.example/phish"),
        )
        assertEquals(
            "https://github.com/lswlc33/maibms/releases",
            UpdateChecker.sanitizeHtmlUrl(""),
        )
        assertEquals(
            "https://github.com/lswlc33/maibms/releases/tag/v1.0.0",
            UpdateChecker.sanitizeHtmlUrl("https://github.com/lswlc33/maibms/releases/tag/v1.0.0"),
        )
        // 前缀对但带路径穿越的也不能放行
        assertEquals(
            "https://github.com/lswlc33/maibms/releases",
            UpdateChecker.sanitizeHtmlUrl("https://github.com/lswlc33/maibms/releases/../../evil"),
        )
    }

    @Test fun channelFromKeyDefaultsToStable() {
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey("stable"))
        assertEquals(UpdateChecker.UpdateChannel.PREVIEW, UpdateChecker.UpdateChannel.fromKey("preview"))
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey(null))
        assertEquals(UpdateChecker.UpdateChannel.STABLE, UpdateChecker.UpdateChannel.fromKey("whatever"))
    }
}
