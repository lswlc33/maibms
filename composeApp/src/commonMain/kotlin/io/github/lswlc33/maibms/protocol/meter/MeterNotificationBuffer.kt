package io.github.lswlc33.maibms.protocol.meter

/**
 * Fixed-capacity notification prefix shared by the two meter framers.
 * frameLength returns -1 for noise, 0 for an undecided prefix, or a frame's byte length.
 * Input is processed in bounded chunks so a large notification never truncates later frames.
 */
internal class MeterNotificationBuffer(
    private val maxFrameLength: Int,
    private val frameLength: (ByteArray, Int) -> Int,
) {
    private val prefix = ByteArray(maxFrameLength)
    private var size = 0

    fun clear() {
        size = 0
    }

    fun append(bytes: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(bytes.size - offset, prefix.size - size)
            bytes.copyInto(prefix, size, offset, offset + count)
            size += count
            offset += count

            while (size > 0) {
                val length = frameLength(prefix, size)
                when {
                    length < 0 || length > maxFrameLength -> discard(1)
                    length == 0 || length > size -> {
                        if (size == maxFrameLength) discard(1) else break
                    }
                    else -> {
                        frames.add(prefix.copyOfRange(0, length))
                        discard(length)
                    }
                }
            }
        }
        return frames
    }

    private fun discard(count: Int) {
        prefix.copyInto(prefix, 0, count, size)
        size -= count
    }
}
