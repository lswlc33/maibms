// 调试脚本：直接构造 FrameParser 并喂入短帧，打印行为
import io.github.lswlc33.maibms.protocol.*

fun main() {
    val f = Frame.control(7)
    println("frame: " + f.joinToString(" ") { "%02X".format(it) })
    val p = FrameParser()
    val out = p.feed(f)
    println("parsed: $out, buffered=${p.buffered}")

    val rr = Frame.readRealtime()
    println("realtime: " + rr.joinToString(" ") { "%02X".format(it) })
    val p2 = FrameParser()
    println("parsed2: " + p2.feed(rr))
}
