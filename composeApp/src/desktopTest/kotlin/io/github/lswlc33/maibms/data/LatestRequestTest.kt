package io.github.lswlc33.maibms.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用正式 UI 的 helper 测竞态，不在测试里复制 seq/渠道防护。 */
class LatestRequestTest {
    @Test fun slowStableCannotReplaceFastPreview() = runBlocking {
        withTimeout(5_000) {
            val latest = LatestRequest<UpdateChecker.UpdateChannel>()
            val entered = CompletableDeferred<Unit>()
            lateinit var oldIo: Continuation<String>
            var result: String? = null
            var checking = false
            val finished = mutableListOf<String>()
            val old = latest.launch(
                scope = this,
                key = UpdateChecker.UpdateChannel.STABLE,
                onStart = { checking = true; result = null },
                request = { suspendCoroutine<String> { oldIo = it; entered.complete(Unit) } },
                onResult = { result = it },
                onFinished = { checking = false; finished += "stable" },
            )
            entered.await()
            val current = latest.launch(
                scope = this,
                key = UpdateChecker.UpdateChannel.PREVIEW,
                onStart = { checking = true; result = null },
                request = { "preview" },
                onResult = { result = it },
                onFinished = { checking = false; finished += "preview" },
            )
            current.join()
            assertTrue(old.isCancelled, "切渠道必须取消旧 Job")
            assertEquals("preview", result)
            assertFalse(checking)
            // 模拟阻塞 IO 忽略取消，直到 B 完成之后才把 A 返回给调用方。
            oldIo.resume("stable")
            old.join()
            assertEquals("preview", result)
            assertEquals(listOf("preview"), finished)
        }
    }

    @Test fun staleFinallyCannotStopNewCheckingEvenWhenKeyRepeats() = runBlocking {
        withTimeout(5_000) {
            val latest = LatestRequest<String>()
            val entered = CompletableDeferred<Unit>()
            lateinit var oldIo: Continuation<String>
            val currentIo = CompletableDeferred<String>()
            var result: String? = null
            var checking = false
            var finishes = 0
            val old = latest.launch(
                this, "stable", { checking = true },
                { suspendCoroutine<String> { oldIo = it; entered.complete(Unit) } },
                { result = it }, { checking = false; finishes++ },
            )
            entered.await()
            val current = latest.launch(
                this, "stable", { checking = true }, { currentIo.await() },
                { result = it }, { checking = false; finishes++ },
            )
            oldIo.resume("old stable")
            old.join()
            assertTrue(checking, "同渠道重查也必须靠 seq 防旧 finally")
            assertEquals(null, result)
            assertEquals(0, finishes)
            currentIo.complete("new stable")
            current.join()
            assertEquals("new stable", result)
            assertFalse(checking)
            assertEquals(1, finishes)
        }
    }

    @Test fun disposalInvalidatesUncancellableResultAndFinally() = runBlocking {
        withTimeout(5_000) {
            val latest = LatestRequest<String>()
            val entered = CompletableDeferred<Unit>()
            lateinit var io: Continuation<String>
            var results = 0
            var finishes = 0
            val job = latest.launch(
                this, "stable", {},
                { suspendCoroutine<String> { io = it; entered.complete(Unit) } },
                { results++ }, { finishes++ },
            )
            entered.await()
            latest.cancel()
            assertTrue(job.isCancelled)
            io.resume("late result")
            job.join()
            assertEquals(0, results)
            assertEquals(0, finishes)
        }
    }

    @Test fun currentCancellationStillFinishesChecking() = runBlocking {
        withTimeout(5_000) {
            val latest = LatestRequest<String>()
            val entered = CompletableDeferred<Unit>()
            val io = CompletableDeferred<String>()
            var checking = false
            var results = 0
            val job: Job = latest.launch(
                this, "stable", { checking = true },
                { entered.complete(Unit); io.await() },
                { results++ }, { checking = false },
            )
            entered.await()
            job.cancel()
            job.join()
            assertFalse(checking)
            assertEquals(0, results)
        }
    }
}
