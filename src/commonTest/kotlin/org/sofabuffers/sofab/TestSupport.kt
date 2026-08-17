/*
 * SofaBuffers Kotlin Multiplatform — shared test helpers.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab

/** Lowercase hex of `b[from, to)`, the form the shared vectors use. */
internal fun hex(b: ByteArray, from: Int = 0, to: Int = b.size): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder((to - from) * 2)
    for (i in from until to) {
        val v = b[i].toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0xF])
    }
    return sb.toString()
}

/** Bytes of a lowercase/uppercase hex string. */
internal fun unhex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((digit(s[i * 2]) shl 4) or digit(s[i * 2 + 1])).toByte()
    }
    return out
}

private fun digit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("not hex: $c")
}

/**
 * One-shot encode into a buffer of [room] bytes, returning exactly the bytes
 * written — the form every wire-shape assertion compares against.
 */
internal fun encode(room: Int = 512, block: (OStream) -> Unit): ByteArray {
    val buf = ByteArray(room)
    val os = OStream(buf)
    block(os)
    return buf.copyOf(os.bytesUsed)
}

/**
 * Encode through a buffer of [room] bytes plus a collecting flush sink, returning
 * the streamed-out bytes. With `room = Sofab.MIN_OUTPUT_BUFFER` this is the
 * CORELIB_PLAN §7.2 item 4 encode test: the result must be byte-identical to the
 * one-shot path.
 */
internal fun encodeStreaming(room: Int, offset: Int = 0, block: (OStream) -> Unit): ByteArray {
    val collected = ArrayList<Byte>()
    val buf = ByteArray(room)
    val os = OStream(buf, offset, { data, off, len ->
        for (i in off until off + len) collected.add(data[i])
    })
    block(os)
    os.flush()
    return collected.toByteArray()
}

/**
 * Records every decoder callback as a normalized string event, reassembling
 * chunked string/blob payloads. Two decodes of the same message must produce the
 * same event list however the bytes were chunked.
 *
 * It **copies** every string/blob chunk out of the fed buffer, which is what the
 * chunk-lifetime contract (CORELIB_PLAN §6) requires of any real consumer and what
 * makes the buffer-scrubbing test meaningful.
 */
internal class RecordingVisitor : Visitor {
    val events: MutableList<String> = mutableListOf()

    private var pendKind: String? = null
    private var pendId = 0
    private var pendTotal = 0
    private var pendBuf: ByteArray = ByteArray(0)

    override fun unsigned(id: Int, value: Long) {
        events.add("u:$id:${value.toULong()}")
    }

    override fun signed(id: Int, value: Long) {
        events.add("s:$id:$value")
    }

    override fun fp32Bits(id: Int, bits: Int) {
        events.add("f32:$id:$bits")
    }

    override fun fp64(id: Int, value: Double) {
        events.add("f64:$id:${value.toRawBits()}")
    }

    override fun string(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        chunk("str", id, total, offset, data, chunkOffset, chunkLength)
    }

    override fun blob(id: Int, total: Int, offset: Int, data: ByteArray, chunkOffset: Int, chunkLength: Int) {
        chunk("blob", id, total, offset, data, chunkOffset, chunkLength)
    }

    private fun chunk(
        kind: String,
        id: Int,
        total: Int,
        offset: Int,
        data: ByteArray,
        chunkOffset: Int,
        chunkLength: Int,
    ) {
        if (pendKind == null) {
            pendKind = kind
            pendId = id
            pendTotal = total
            pendBuf = ByteArray(total)
        }
        data.copyInto(pendBuf, offset, chunkOffset, chunkOffset + chunkLength)
        if (offset + chunkLength >= pendTotal) {
            events.add(
                if (pendKind == "str") "str:$pendId:${pendBuf.decodeToString()}"
                else "blob:$pendId:${hex(pendBuf)}",
            )
            pendKind = null
        }
    }

    override fun arrayBegin(id: Int, kind: ArrayKind, count: Int) {
        events.add("arr:$id:$kind:$count")
    }

    override fun sequenceBegin(id: Int) {
        events.add("seq{:$id")
    }

    override fun sequenceEnd() {
        events.add("seq}")
    }
}

/** Decode [wire] in one feed and return the recorded events. */
internal fun decodeEvents(wire: ByteArray): List<String> {
    val v = RecordingVisitor()
    IStream().feed(wire, v)
    return v.events
}

/** Decode [wire] in fixed-size chunks through one decoder and return the events. */
internal fun decodeEventsChunked(wire: ByteArray, chunk: Int): List<String> {
    val v = RecordingVisitor()
    val input = IStream()
    var i = 0
    while (i < wire.size) {
        val n = minOf(chunk, wire.size - i)
        input.feed(wire, i, n, v)
        i += n
    }
    return v.events
}

/** Feed [wire] whole and return the decoder, so a test can read its [IStream.status]. */
internal fun feedAll(wire: ByteArray, visitor: Visitor = RecordingVisitor()): IStream {
    val input = IStream()
    input.feed(wire, visitor)
    return input
}
