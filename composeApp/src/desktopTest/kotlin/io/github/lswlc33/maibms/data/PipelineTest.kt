package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.protocol.Frame
import io.github.lswlc33.maibms.protocol.Proto
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PipelineTest {

    @Test fun engineRespondsToRealtimeRequest() = runBlocking {
        val engine = MockBmsEngine(this)
        val req = Frame.readRealtime()
        // 用仓库同款解析器解析请求，确认请求可被解析
        val p = io.github.lswlc33.maibms.protocol.FrameParser()
        val frames = p.feed(req)
        println("parsed request frames: $frames")
        assertEquals(1, frames.size)
        val resp = engine.handle(frames[0])
        println("resp size=${resp?.size}")
        assertTrue(resp != null && resp.size > 100)
        // 解析应答（单帧，不分片）
        val p2 = io.github.lswlc33.maibms.protocol.FrameParser()
        val rsp = p2.feed(resp!!)
        println("parsed response: $rsp")
        assertEquals(1, rsp.size)
        assertEquals(Proto.RSP_REALTIME, rsp[0].func)
        val dec = io.github.lswlc33.maibms.protocol.RealtimeDecoder.decode(rsp[0].data)
        println("decoded: soc=${dec.soc} perm=${dec.permission} v=${dec.totalVoltage}")
    }

    @Test fun mockTransportRoundTrip() = runBlocking {
        val engine = MockBmsEngine(this)
        val t = io.github.lswlc33.maibms.transport.MockBmsTransport(engine, this)
        t.connect()
        val got = ArrayList<Byte>()
        val job = launch {
            t.incoming.collect { b: ByteArray -> got.addAll(b.toList()) }
        }
        delay(100)   // 等待订阅
        t.write(Frame.readRealtime())
        delay(400)
        println("received ${got.size} bytes")
        assertTrue(got.size > 100, "should receive response bytes, got ${got.size}")
        job.cancel()
    }

    @Test fun fullPipelineBackfillsUi() = runBlocking {
        // 协议管线端到端：帧 → 解析 → 解码 → MockBms 回填（演示传输已随演示模式移除，
        // 仓库的连接路径依赖平台 BLE，桌面单测里直接驱动引擎应答走同一条 handleFrame 逻辑）
        val engine = MockBmsEngine(this)
        val req = Frame.readRealtime()
        val parser = io.github.lswlc33.maibms.protocol.FrameParser()
        val parsed = parser.feed(req)
        assertEquals(1, parsed.size)
        val respBytes = engine.handle(parsed[0])!!
        val respFrames = io.github.lswlc33.maibms.protocol.FrameParser().feed(respBytes)
        assertEquals(1, respFrames.size)
        assertEquals(Proto.RSP_REALTIME, respFrames[0].func)
        val dec = io.github.lswlc33.maibms.protocol.RealtimeDecoder.decode(respFrames[0].data)
        MockBms.clearForRealDevice()
        MockBms.updateFromRealtime(dec)
        val s = MockBms.status.value
        println("perm=${s.permissionLevel} runtime=${s.runtime} soc=${s.soc} V=${s.totalVoltage} I=${s.current}")
        assertTrue(MockBms.connected.value, "UI status should be backfilled by pipeline")
        assertTrue(s.totalVoltage > 0.0, "total voltage should be decoded, got ${s.totalVoltage}")
    }
}
